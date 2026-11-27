// MagicPower 发热自检 —— 后端
//
// 浏览器读不到 /proc，也拿不到电池温度，所以检查必须在能读系统节点的地方做。
// 这里用 Go 直接读 /proc 和 /sys，把结果以 JSON 交给前端渲染。
//
// 判定逻辑与 tools/thermal-check.sh 保持一致：
//   单核满载 CPU >= 80%、内核态占比 >= 50%、孤儿进程 PPid == 1。
package main

import (
	"crypto/rand"
	"embed"
	"encoding/hex"
	"encoding/json"
	"flag"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed static
var staticFS embed.FS

// clockTicks 是 sysconf(_SC_CLK_TCK)：Linux/Android 固定 100，1 jiffy = 10ms。
// Go 里没有 sysconf，这里按平台常量处理；仅在非 100 的内核上会偏低/偏高。
const clockTicks = 100

const (
	batPath     = "/sys/class/power_supply/battery"
	thermalGlob = "/sys/class/thermal/thermal_zone*"

	cpuHeavyPercent = 80.0 // 单核满载阈值（单核 100%）
	kernelRatioMin  = 50.0 // 内核态占比阈值
	sensorWarnC     = 75.0 // 传感器告警阈值
	batteryWarnC    = 45.0 // 未充电时的电池告警阈值
	chargingInfoC   = 40.0 // 充电时只做提示
)

// analyzeMu 串行化采样：两个浏览器同时刷新会让 CPU 读数互相污染。
var analyzeMu sync.Mutex

// reportTTL 是停止接口认可检查结果的时限，过期的报告不再允许停止进程。
const reportTTL = 5 * time.Minute

var startAt = time.Now()

var (
	// stopToken 启动时随机生成，只随检查结果下发，用于挡住任意网页的 POST。
	stopToken = newToken()

	reportMu   sync.Mutex
	lastReport *Report
	lastAt     time.Time
)

func newToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(b)
}

// Service 描述检查服务自身状态，让页面能显示它是否以 root 运行。
// 非 root 时 /proc 读不到其他 uid 的进程，判定会漏掉真正的元凶。
type Service struct {
	PID       int  `json:"pid"`
	Root      bool `json:"root"`
	UptimeSec int  `json:"uptimeSec"`
}

type Battery struct {
	Temperature float64 `json:"temperature"`
	Current     int     `json:"current"`
	Unit        string  `json:"unit"`
	Status      string  `json:"status"`
	Capacity    int     `json:"capacity"`
	Charging    bool    `json:"charging"`
}

type Sensor struct {
	Type        string  `json:"type"`
	Temperature float64 `json:"temperature"`
}

type CPUTotal struct {
	UsedPercent   float64 `json:"usedPercent"`
	UsedCores     float64 `json:"usedCores"`
	TotalCores    float64 `json:"totalCores"`
	KernelPercent float64 `json:"kernelPercent"`
}

type ProcUsage struct {
	PID           int     `json:"pid"`
	CPUPercent    float64 `json:"cpuPercent"`
	KernelPercent int     `json:"kernelPercent"`
	PPid          int     `json:"ppid"`
	Name          string  `json:"name"`
}

type Finding struct {
	Level  string `json:"level"` // danger | warn | info | ok
	PID    int    `json:"pid,omitempty"`
	Title  string `json:"title"`
	Detail string `json:"detail"`
	Action string `json:"action,omitempty"`
}

type Report struct {
	GeneratedAt string      `json:"generatedAt"`
	WindowSec   int         `json:"windowSec"`
	Battery     Battery     `json:"battery"`
	Sensors     []Sensor    `json:"sensors"`
	MaxSensor   *Sensor     `json:"maxSensor"`
	CPU         CPUTotal    `json:"cpu"`
	Top         []ProcUsage `json:"top"`
	Findings    []Finding   `json:"findings"`
	Log         string      `json:"log"`
	// StopToken 只随检查结果下发。停止接口要求带上它，防止任意网页
	// 直接向 127.0.0.1 发 POST 误杀进程（本机服务没有 CSRF 防护）。
	StopToken string `json:"stopToken"`
	Service   Service `json:"service"`
}

// procTimes 是单进程一次采样的累计时间（单位 jiffy，均已包含子进程之外的部分）。
type procTimes struct {
	utime int64
	stime int64
	ppid  int
}

// cpuTimes 是 /proc/stat 首行的前 8 个计数：user nice system idle iowait irq softirq steal。
type cpuTimes [8]int64

// ---------- 系统节点读取 ----------

func readTrim(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func readInt(path string) (int, bool) {
	s := readTrim(path)
	if s == "" {
		return 0, false
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return 0, false
	}
	return n, true
}

func readBattery() Battery {
	var b Battery
	b.Status = readTrim(filepath.Join(batPath, "status"))
	if n, ok := readInt(filepath.Join(batPath, "temp")); ok {
		b.Temperature = float64(n) / 10.0 // 节点名是 temp（不是 temperature），单位 0.1℃
	}
	if n, ok := readInt(filepath.Join(batPath, "capacity")); ok {
		b.Capacity = n
	}
	b.Charging = b.Status == "Charging" || b.Status == "Full"

	// 本机 current_now 单位是 mA（荣耀 BRT-AN00 实测），标准 Linux 是 µA。
	// 只在绝对值大到不可能是 mA 时按 µA 换算，避免把 1840mA 当成 1.8mA。
	b.Unit = "mA"
	if n, ok := readInt(filepath.Join(batPath, "current_now")); ok {
		abs := n
		if abs < 0 {
			abs = -abs
		}
		if abs > 100000 {
			n /= 1000
			b.Unit = "mA(由µA换算)"
		}
		b.Current = n
	}
	return b
}

// readSensors 读全部热区，并过滤 trip/lvl/bcl —— 这些是过温跳闸阈值和电量门限，
// 不是温度读数（本机 cpu-hw-trip-0/1 恒为 105℃，直接读会误报“105℃ 过热”）。
func readSensors() []Sensor {
	dirs, err := filepath.Glob(thermalGlob)
	if err != nil {
		return nil
	}
	out := make([]Sensor, 0, len(dirs))
	for _, dir := range dirs {
		name := readTrim(filepath.Join(dir, "type"))
		if name == "" {
			continue
		}
		lower := strings.ToLower(name)
		if strings.Contains(lower, "trip") ||
			strings.Contains(lower, "lvl") ||
			strings.Contains(lower, "bcl") {
			continue
		}
		n, ok := readInt(filepath.Join(dir, "temp"))
		if !ok {
			continue
		}
		out = append(out, Sensor{Type: name, Temperature: float64(n) / 1000.0})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Temperature > out[j].Temperature })
	return out
}

func readCPUStat() (cpuTimes, bool) {
	var ct cpuTimes
	b, err := os.ReadFile("/proc/stat")
	if err != nil {
		return ct, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(line, "cpu ") {
			continue
		}
		f := strings.Fields(line)
		for i := 0; i < 8 && i+1 < len(f); i++ {
			ct[i], _ = strconv.ParseInt(f[i+1], 10, 64)
		}
		return ct, true
	}
	return ct, false
}

// readProcStat 解析 /proc/PID/stat。
// comm 字段可能含空格和右括号，所以必须从最后一个 ") " 之后切分；
// 切分后剩余字段: [0]=state [1]=ppid ... [11]=utime [12]=stime。
func readProcStat(pid int) (procTimes, bool) {
	var pt procTimes
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return pt, false
	}
	s := string(b)
	idx := strings.LastIndex(s, ")")
	if idx < 0 || idx+1 >= len(s) {
		return pt, false
	}
	f := strings.Fields(s[idx+1:])
	if len(f) < 13 {
		return pt, false
	}
	ppid, err := strconv.Atoi(f[1])
	if err != nil {
		return pt, false
	}
	utime, _ := strconv.ParseInt(f[11], 10, 64)
	stime, _ := strconv.ParseInt(f[12], 10, 64)
	pt.ppid = ppid
	pt.utime = utime
	pt.stime = stime
	return pt, true
}

func snapshot() map[int]procTimes {
	out := make(map[int]procTimes, 1024)
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return out
	}
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(e.Name())
		if err != nil {
			continue
		}
		if pt, ok := readProcStat(pid); ok {
			out[pid] = pt
		}
	}
	return out
}

// procName 优先用 cmdline（更可读），空则退回 comm（内核线程）。
func procName(pid int) string {
	if b, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid)); err == nil {
		s := strings.TrimSpace(strings.ReplaceAll(string(b), "\x00", " "))
		if s != "" {
			return truncate(s, 72)
		}
	}
	return strings.TrimSpace(readTrim(fmt.Sprintf("/proc/%d/comm", pid)))
}

func truncate(s string, max int) string {
	r := []rune(s)
	if len(r) <= max {
		return s
	}
	return string(r[:max]) + "…"
}

// ---------- 分析 ----------

func collectTop(s1, s2 map[int]procTimes, window int) []ProcUsage {
	type row struct {
		pid   int
		delta int64
		kern  int64
		ppid  int
	}
	rows := make([]row, 0, len(s2))
	for pid, after := range s2 {
		before, ok := s1[pid]
		if !ok {
			continue // 窗口内新起的进程无法算全程占用，跳过
		}
		du := after.utime - before.utime
		ds := after.stime - before.stime
		if du < 0 || ds < 0 {
			continue // 进程被重启导致计数归零
		}
		total := du + ds
		if total <= 0 {
			continue
		}
		rows = append(rows, row{pid: pid, delta: total, kern: ds, ppid: after.ppid})
	}
	sort.Slice(rows, func(i, j int) bool { return rows[i].delta > rows[j].delta })

	if len(rows) > 5 {
		rows = rows[:5]
	}
	out := make([]ProcUsage, 0, len(rows))
	for _, r := range rows {
		out = append(out, ProcUsage{
			PID:           r.pid,
			CPUPercent:    float64(r.delta) / float64(window*clockTicks) * 100.0,
			KernelPercent: int(float64(r.kern) / float64(r.delta) * 100.0),
			PPid:          r.ppid,
			Name:          procName(r.pid),
		})
	}
	return out
}

func analyze(window int) Report {
	analyzeMu.Lock()
	defer analyzeMu.Unlock()

	rep := Report{
		GeneratedAt: time.Now().Format("2006-01-02 15:04:05"),
		WindowSec:   window,
		Service: Service{
			PID:       os.Getpid(),
			Root:      os.Geteuid() == 0,
			UptimeSec: int(time.Since(startAt).Seconds()),
		},
	}
	rep.Battery = readBattery()

	all := readSensors()
	if len(all) > 3 {
		all = all[:3]
	}
	rep.Sensors = all
	if len(all) > 0 {
		max := all[0]
		rep.MaxSensor = &max
	}

	c1, ok1 := readCPUStat()
	s1 := snapshot()
	time.Sleep(time.Duration(window) * time.Second)
	c2, ok2 := readCPUStat()
	s2 := snapshot()

	if ok1 && ok2 {
		var d [8]int64
		var total, idle, kern int64
		for i := 0; i < 8; i++ {
			d[i] = c2[i] - c1[i]
			total += d[i]
		}
		idle = d[3] + d[4]                  // idle + iowait
		kern = d[2] + d[6] + d[7]           // system + irq + softirq
		used := total - idle
		if total > 0 {
			rep.CPU.TotalCores = float64(total) / float64(window*clockTicks)
			rep.CPU.UsedCores = float64(used) / float64(window*clockTicks)
			rep.CPU.UsedPercent = float64(used) / float64(total) * 100.0
		}
		if used > 0 {
			rep.CPU.KernelPercent = float64(kern) / float64(used) * 100.0
		}
	}

	rep.Top = collectTop(s1, s2, window)
	rep.Findings = judge(rep)
	rep.Log = renderLog(rep)
	return rep
}

func judge(rep Report) []Finding {
	findings := make([]Finding, 0, 4)

	for _, p := range rep.Top {
		if p.CPUPercent < cpuHeavyPercent {
			continue
		}
		switch {
		case p.KernelPercent >= int(kernelRatioMin) && p.PPid == 1:
			findings = append(findings, Finding{
				Level:  "danger",
				PID:    p.PID,
				Title:  fmt.Sprintf("PID %d 单核 %.1f%% + 内核态 %d%% + 孤儿进程", p.PID, p.CPUPercent, p.KernelPercent),
				Detail: "原父进程已退出（PPid=1），疑似卡在 syscall 自旋空转。这类进程在耗电排行里看不出来，但会持续吃满一个核。",
				Action: fmt.Sprintf("核实后处置：kill -9 %d", p.PID),
			})
		case p.KernelPercent >= int(kernelRatioMin):
			findings = append(findings, Finding{
				Level:  "warn",
				PID:    p.PID,
				Title:  fmt.Sprintf("PID %d 单核 %.1f%% + 内核态 %d%%", p.PID, p.CPUPercent, p.KernelPercent),
				Detail: fmt.Sprintf("父进程为 %d。疑似 syscall 自旋，先确认它是否应当常驻。", p.PPid),
				Action: fmt.Sprintf("观察确认后可 kill -9 %d", p.PID),
			})
		default:
			findings = append(findings, Finding{
				Level:  "info",
				PID:    p.PID,
				Title:  fmt.Sprintf("PID %d 单核 %.1f%%（内核态 %d%%）", p.PID, p.CPUPercent, p.KernelPercent),
				Detail: "用户态为主，一般是正常的前台计算或渲染。",
			})
		}
	}

	if rep.MaxSensor != nil && rep.MaxSensor.Temperature >= sensorWarnC {
		findings = append(findings, Finding{
			Level:  "warn",
			Title:  fmt.Sprintf("传感器最高 %.1f℃（%s）", rep.MaxSensor.Temperature, rep.MaxSensor.Type),
			Detail: fmt.Sprintf("持续高于 %.0f℃ 会触发降频，核对上面的 Top 进程。", sensorWarnC),
		})
	}

	b := rep.Battery
	if b.Charging && b.Temperature >= chargingInfoC {
		findings = append(findings, Finding{
			Level:  "info",
			Title:  fmt.Sprintf("正在充电，电池 %.1f℃", b.Temperature),
			Detail: "充电升温属正常，不判为异常。",
		})
	} else if !b.Charging && b.Temperature >= batteryWarnC {
		findings = append(findings, Finding{
			Level:  "warn",
			Title:  fmt.Sprintf("未充电但电池 %.1f℃", b.Temperature),
			Detail: fmt.Sprintf("高于 %.0f℃，确认是否刚结束高负载。", batteryWarnC),
		})
	}

	if len(findings) == 0 {
		findings = append(findings, Finding{
			Level:  "ok",
			Title:  "未发现单核独占或内核态异常进程",
			Detail: "当前温度与 CPU 分布都在正常范围。",
		})
	}
	return findings
}

// renderLog 生成可直接粘贴的纯文本报告，格式与 tools/thermal-check.sh 对齐。
func renderLog(rep Report) string {
	var b strings.Builder
	p := func(format string, a ...any) { fmt.Fprintf(&b, format+"\n", a...) }

	p("MagicPower 发热自检   %s   采样 %ds", rep.GeneratedAt, rep.WindowSec)
	if rep.Service.Root {
		p("服务 PID %d · root 已就绪", rep.Service.PID)
	} else {
		p("服务 PID %d · 非 root，读不到其他进程，结果会漏", rep.Service.PID)
	}
	p("================================================================")
	p("")
	p("【电池】")
	p("  温度      %.1f ℃", rep.Battery.Temperature)
	p("  状态      %s   电量 %d%%", rep.Battery.Status, rep.Battery.Capacity)
	p("  电流      %d %s", rep.Battery.Current, rep.Battery.Unit)
	p("")
	if len(rep.Sensors) > 0 {
		p("【温度传感器 · 最高 %d 个】", len(rep.Sensors))
		for _, s := range rep.Sensors {
			p("  %5.1f ℃   %s", s.Temperature, s.Type)
		}
	} else {
		p("【温度传感器】")
		p("  （读不到）")
	}
	p("")
	p("【全局 CPU】")
	p("  总占用    %.1f%%   (%.2f 核 / 共 %.0f 核)", rep.CPU.UsedPercent, rep.CPU.UsedCores, rep.CPU.TotalCores)
	p("  内核态    %.0f%%   ← 已用 CPU 里内核态占比", rep.CPU.KernelPercent)
	p("")
	p("【CPU 占用 Top 5】")
	p("  PID       CPU%%     内核态   进程")
	for _, t := range rep.Top {
		p("  %-9d %6.1f%%  %5d%%   %s", t.PID, t.CPUPercent, t.KernelPercent, t.Name)
	}
	p("")
	p("【判定】")
	for _, f := range rep.Findings {
		switch f.Level {
		case "danger":
			p("  [!] %s", f.Title)
		case "warn":
			p("  [!] %s", f.Title)
		case "info":
			p("  [·] %s", f.Title)
		default:
			p("  [ok] %s", f.Title)
		}
		if f.Detail != "" {
			p("      %s", f.Detail)
		}
		if f.Action != "" {
			p("      %s", f.Action)
		}
	}
	p("")
	p("  （只做诊断，不会自动结束任何进程）")
	return b.String()
}

// ---------- HTTP ----------

func handleCheck(w http.ResponseWriter, r *http.Request) {
	window := 5
	if v := r.URL.Query().Get("window"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n >= 1 && n <= 30 {
			window = n
		}
	}
	rep := analyze(window)
	rep.StopToken = stopToken

	// 存下这份结果：停止接口只认它里面被判 danger/warn 的 PID。
	reportMu.Lock()
	lastReport = &rep
	lastAt = time.Now()
	reportMu.Unlock()

	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	if err := json.NewEncoder(w).Encode(rep); err != nil {
		log.Printf("encode report: %v", err)
	}
}

// ---------- 停止异常进程 ----------

// readUid 读 /proc/PID/status 里的 Uid（取第一个，即真实 uid）。
func readUid(pid int) (int, bool) {
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return 0, false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if !strings.HasPrefix(line, "Uid:") {
			continue
		}
		f := strings.Fields(line)
		if len(f) < 2 {
			return 0, false
		}
		uid, err := strconv.Atoi(f[1])
		if err != nil {
			return 0, false
		}
		return uid, true
	}
	return 0, false
}

// hasUserPayload 判断进程的可执行文件或任一命令行参数是否指向用户空间。
// 用户自己用 root 跑的脚本 exe 是 /system/bin/sh，但命令行会带 /data/... 路径。
func hasUserPayload(pid int) bool {
	isUserSpace := func(p string) bool {
		return strings.HasPrefix(p, "/data/") ||
			strings.HasPrefix(p, "/storage/") ||
			strings.HasPrefix(p, "/sdcard/") ||
			strings.HasPrefix(p, "/mnt/")
	}
	if exe, err := os.Readlink(fmt.Sprintf("/proc/%d/exe", pid)); err == nil && isUserSpace(exe) {
		return true
	}
	b, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid))
	if err != nil {
		return false
	}
	for _, arg := range strings.Split(string(b), "\x00") {
		if isUserSpace(arg) {
			return true
		}
	}
	return false
}

// protectReason 返回不允许停止的原因；空字符串表示允许。
//
// 判定分两层：
//  1. uid >= 10000 是普通用户应用，直接放行。Android 应用进程的 exe 也是
//     /system/bin/app_process，只看路径会把微信这类普通应用误判成系统进程。
//  2. uid < 10000 是系统服务或 root 进程。只有负载指向 /data 等用户空间才放行
//     （用户自己用 root 跑的脚本），否则一律拒绝——这样 system_server、
//     surfaceflinger 这类真正的系统服务会被挡住。
func protectReason(pid int) string {
	if pid <= 1 {
		return "非法 PID"
	}
	if pid == os.Getpid() {
		return "是本服务自身"
	}
	uid, ok := readUid(pid)
	if !ok {
		return "进程已不存在"
	}
	if uid >= 10000 {
		return ""
	}
	if hasUserPayload(pid) {
		return ""
	}
	return fmt.Sprintf("是系统进程（uid=%d，可执行文件和命令行都在系统分区）", uid)
}

// criticalProcs 是任何情况下都不允许停止的系统关键进程（按 comm / 包名匹配）。
// 即使调用方显式要求 force，这些也会被拒绝。
var criticalProcs = map[string]bool{
	"init": true, "system_server": true, "zygote": true, "zygote64": true,
	"surfaceflinger": true, "servicemanager": true, "vold": true, "netd": true,
	"installd": true, "keystore2": true, "audioserver": true, "media": true,
	"thermald": true, "hostapd": true,
}

func isCritical(pid int) bool {
	if pid <= 1 || pid == os.Getpid() {
		return true
	}
	if criticalProcs[readTrim(fmt.Sprintf("/proc/%d/comm", pid))] {
		return true
	}
	if b, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", pid)); err == nil {
		cmd := string(b)
		for _, name := range []string{"com.android.systemui", "com.hihonor.systemmanager"} {
			if strings.Contains(cmd, name) {
				return true
			}
		}
	}
	return false
}

// allowedToStop 要求 PID 出现在最近一次检查里、且判定为 danger 或 warn。
func allowedToStop(pid int) (string, bool) {
	reportMu.Lock()
	defer reportMu.Unlock()
	if lastReport == nil {
		return "还没有检查结果，请先运行检查", false
	}
	if time.Since(lastAt) > reportTTL {
		return "检查结果已过期，请重新检查后再停止", false
	}
	for _, f := range lastReport.Findings {
		if f.PID != pid {
			continue
		}
		if f.Level == "danger" || f.Level == "warn" {
			return "", true
		}
		return "该进程当前判定为「" + f.Level + "」，不提供停止", false
	}
	return "该 PID 不在最近一次检查的可疑名单里", false
}

// stopProcess 先 SIGTERM 让进程自己收拾，2 秒后仍在则 SIGKILL。
func stopProcess(pid int, mode string) (string, bool) {
	if mode == "pause" {
		if err := syscall.Kill(pid, syscall.SIGSTOP); err != nil {
			return "暂停失败：" + err.Error(), false
		}
		return "已暂停（SIGSTOP，可用 SIGCONT 恢复）", true
	}

	_ = syscall.Kill(pid, syscall.SIGTERM)
	for i := 0; i < 20; i++ {
		time.Sleep(100 * time.Millisecond)
		if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); err != nil {
			return "已结束（SIGTERM）", true
		}
	}
	if err := syscall.Kill(pid, syscall.SIGKILL); err != nil {
		return "SIGKILL 失败：" + err.Error(), false
	}
	time.Sleep(200 * time.Millisecond)
	if _, err := os.Stat(fmt.Sprintf("/proc/%d", pid)); err == nil {
		return "已发 SIGKILL，但进程仍存在", false
	}
	return "SIGTERM 无效，已用 SIGKILL 结束", true
}

func writeStop(w http.ResponseWriter, code int, ok bool, msg string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(map[string]any{"ok": ok, "message": msg})
}

func handleStop(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", "POST")
		writeStop(w, http.StatusMethodNotAllowed, false, "只接受 POST")
		return
	}
	var req struct {
		PID   int    `json:"pid"`
		Mode  string `json:"mode"`
		Token string `json:"token"`
		Force bool   `json:"force"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeStop(w, http.StatusBadRequest, false, "请求格式错误")
		return
	}
	if req.Token != stopToken {
		writeStop(w, http.StatusForbidden, false, "校验令牌无效，请重新检查后再操作")
		return
	}
	if req.PID <= 0 {
		writeStop(w, http.StatusBadRequest, false, "PID 非法")
		return
	}
	if reason, ok := allowedToStop(req.PID); !ok {
		writeStop(w, http.StatusForbidden, false, reason)
		return
	}
	if reason := protectReason(req.PID); reason != "" {
		if !req.Force {
			writeStop(w, http.StatusForbidden, false, "拒绝停止："+reason+"（可确认后选择强制停止）")
			return
		}
		if isCritical(req.PID) {
			writeStop(w, http.StatusForbidden, false, "关键系统进程，禁止停止："+reason)
			return
		}
	}

	msg, ok := stopProcess(req.PID, req.Mode)
	code := http.StatusOK
	if !ok {
		code = http.StatusConflict
	}
	writeStop(w, code, ok, msg)
	log.Printf("stop pid=%d mode=%q ok=%v: %s", req.PID, req.Mode, ok, msg)
}

// noCache 让 WebView / 浏览器每次都取最新的 HTML/CSS/JS。
// 没有它时升级 APK 后会继续渲染旧页面（缓存里那份）。
func noCache(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-cache, no-store, must-revalidate")
		w.Header().Set("Pragma", "no-cache")
		w.Header().Set("Expires", "0")
		next.ServeHTTP(w, r)
	})
}

func main() {
	addr := flag.String("addr", "127.0.0.1:8787", "监听地址")
	flag.Parse()

	sub, err := fs.Sub(staticFS, "static")
	if err != nil {
		log.Fatalf("static: %v", err)
	}
	mux := http.NewServeMux()
	mux.Handle("/", noCache(http.FileServer(http.FS(sub))))
	mux.HandleFunc("/api/check", handleCheck)
	mux.HandleFunc("/api/stop", handleStop)

	log.Printf("MagicPower 发热自检已启动: http://%s", *addr)
	if err := http.ListenAndServe(*addr, mux); err != nil {
		log.Fatal(err)
	}
}
