#!/system/bin/sh
# MagicPower 异常发热自检
#
# 为什么需要它：电池温度和"耗电排行"回答不了"为什么烫"。真正让手机发烫的
# 往往是一个进程单核满载空转（常见于父进程退出的孤儿进程、syscall 自旋），
# 这类进程在耗电排行里看不出来，只有读 /proc 的 CPU 增量能抓到。
#
# 需要 root：Android 的 /proc 挂载为 hidepid=invisible，非 root 只能看到
# 同 uid 的进程（本机实测 uid1000 仅可见 47/936 个），会漏掉真正的元凶。
#
# 用法:
#   su -c 'sh thermal-check.sh'        # 默认采样 5 秒
#   su -c 'sh thermal-check.sh 10'     # 自定义采样秒数
#
# 判定依据（同一进程同时命中 1+2 即提示 syscall 自旋，再加 3 为高度可疑）:
#   1. 单核满载：该进程 CPU 占用 >= 80%（单核为 100%，8 核满载为 800%）
#   2. 内核态主导：stime / (utime + stime) >= 50%
#   3. 孤儿进程：PPid == 1，原父进程已退出、被 init 收养
# 只看温度不看 CPU 会把充电升温误判成异常，所以两条都要看。
#
# 退出码: 0 未发现异常 / 1 发现可疑项 / 2 用法或权限错误

set -u

WINDOW=${1:-5}
HZ=100                      # sysconf(_SC_CLK_TCK)，本机实测 100，即 1 jiffy = 10ms
BAT=/sys/class/power_supply/battery
THERMAL=/sys/class/thermal

if [ "$(id -u)" != "0" ]; then
    echo "需要 root: su -c 'sh $0 $*'" >&2
    exit 2
fi

case "$WINDOW" in
    ''|*[!0-9]*) echo "采样秒数必须是正整数" >&2; exit 2 ;;
esac
[ "$WINDOW" -ge 1 ] || { echo "采样秒数至少 1 秒" >&2; exit 2; }

TMP=/data/local/tmp/mp-thermal.$$
mkdir -p "$TMP" 2>/dev/null
trap 'rm -rf "$TMP" 2>/dev/null' EXIT

say() { printf '%s\n' "$*"; }

# ---- 进程快照 -------------------------------------------------------------
# /proc/PID/stat 的 comm 字段可含空格和右括号，必须从最后一个 ") " 之后切分。
# 切分后字段位移: a[1]=state a[2]=ppid ... a[12]=utime a[13]=stime
# 输出: pid utime stime ppid
snapshot() {
    awk '
    {
        line = $0
        sub(/^.*\) /, "", line)
        n = split(line, a, " ")
        if (n < 13) next
        f = FILENAME
        sub(/^\/proc\//, "", f)
        sub(/\/stat$/, "", f)
        print f, a[12], a[13], a[2]
    }
    ' /proc/[0-9]*/stat 2>/dev/null </dev/null
}

say ""
say "MagicPower 发热自检   $(date '+%Y-%m-%d %H:%M:%S')   采样 ${WINDOW}s"
say "================================================================"

# ---- 电池 -----------------------------------------------------------------
b_temp=$(cat "$BAT/temp" 2>/dev/null)
b_cur=$(cat "$BAT/current_now" 2>/dev/null)
b_status=$(cat "$BAT/status" 2>/dev/null)
b_cap=$(cat "$BAT/capacity" 2>/dev/null)

# 本机 current_now 单位是 mA（荣耀 BRT-AN00 实测），不是标准 µA。
# 仅当绝对值大到不可能是 mA 时才按 µA 换算。
cur_unit="mA"
if [ -n "$b_cur" ]; then
    case "$b_cur" in -*) cur_abs=${b_cur#-} ;; *) cur_abs=$b_cur ;; esac
    if [ "$cur_abs" -gt 100000 ]; then
        b_cur=$((b_cur / 1000))
        cur_unit="mA(由µA换算)"
    fi
fi

charging=0
case "$b_status" in
    Charging|Full) charging=1 ;;
esac

say ""
say "【电池】"
[ -n "$b_temp" ] && say "  温度      $(awk -v t="$b_temp" 'BEGIN{printf "%.1f", t/10}') ℃"
say "  状态      ${b_status:-未知}   电量 ${b_cap:-?}%"
say "  电流      ${b_cur:-?} $cur_unit"

# ---- 温度传感器（取最高 3 个）---------------------------------------------
# 过滤 trip/lvl/bcl：这些是过温跳闸阈值和电量门限，不是温度读数
# （本机 cpu-hw-trip-0/1 恒为 105℃，直读会把它们当成"105℃ 过热"误报）。
hot=$(for z in "$THERMAL"/thermal_zone*; do
          t=$(cat "$z/temp" 2>/dev/null) || continue
          n=$(cat "$z/type" 2>/dev/null) || continue
          case "$n" in *trip*|*lvl*|*bcl*) continue ;; esac
          echo "$t $n"
      done | sort -rn | head -3)

max_sensor=0
say ""
say "【温度传感器 · 最高 3 个】"
if [ -n "$hot" ]; then
    max_sensor=$(echo "$hot" | head -1 | awk '{print $1}')
    echo "$hot" | while read -r t n; do
        say "  $(awk -v t="$t" 'BEGIN{printf "%5.1f", t/1000}') ℃   $n"
    done
else
    say "  （读不到）"
fi

# ---- 单一采样窗口：进程快照与 /proc/stat 同窗口 ---------------------------
g1=$(awk '/^cpu /{print $2,$3,$4,$5,$6,$7,$8,$9}' /proc/stat)
snapshot > "$TMP/s1"
sleep "$WINDOW"
g2=$(awk '/^cpu /{print $2,$3,$4,$5,$6,$7,$8,$9}' /proc/stat)
snapshot > "$TMP/s2"

# ---- 全局 CPU -------------------------------------------------------------
say ""
say "【全局 CPU】"
awk -v a="$g1" -v b="$g2" -v w="$WINDOW" -v hz="$HZ" '
BEGIN {
    split(a, x, " "); split(b, y, " ")
    tot = 0; ker = 0; idle = 0
    for (i = 1; i <= 8; i++) {
        d = y[i] - x[i]
        tot += d
        if (i == 4 || i == 5) idle += d              # idle + iowait
        if (i == 3 || i == 7 || i == 8) ker += d     # system + irq + softirq
    }
    used = tot - idle
    if (tot <= 0) { print "  （窗口内无增量）"; exit }
    printf "  总占用    %.1f%%   (%.2f 核 / 共 %.0f 核)\n", used / tot * 100, used / (hz * w), tot / (hz * w)
    if (used > 0) printf "  内核态    %.0f%%   ← 已用 CPU 里内核态占比，超过 50%% 且温度高要查\n", ker / used * 100
}
'

# ---- 进程 CPU Top 5 -------------------------------------------------------
awk -v w="$WINDOW" -v hz="$HZ" '
FNR == NR { u1[$1] = $2; s1[$1] = $3; next }
{
    if (!($1 in u1)) next
    du = $2 - u1[$1]
    ds = $3 - s1[$1]
    tot = du + ds
    if (tot <= 0) next
    printf "%.1f %s %d %d\n", tot / (w * hz) * 100, $1, ds * 100 / tot, $4
}
' "$TMP/s1" "$TMP/s2" | sort -rn | head -5 > "$TMP/top5"

say ""
say "【CPU 占用 Top 5】"
say "  PID       CPU%     内核态   进程"
while read -r cpu pid kern ppid; do
    [ -z "${pid:-}" ] && continue
    name=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | sed 's/[[:space:]]*$//')
    [ -z "$name" ] && name=$(cat "/proc/$pid/comm" 2>/dev/null)
    printf "  %-9s %6.1f%%  %5d%%   %.72s\n" "$pid" "$cpu" "$kern" "$name"
done < "$TMP/top5"

# ---- 判定 -----------------------------------------------------------------
say ""
say "【判定】"
flagged=0
while read -r cpu pid kern ppid; do
    [ -z "${pid:-}" ] && continue
    [ "$(awk -v c="$cpu" 'BEGIN{print (c >= 80) ? 1 : 0}')" = "1" ] || continue

    kerny=$(awk -v k="$kern" 'BEGIN{print (k >= 50) ? 1 : 0}')
    if [ "$kerny" = "1" ] && [ "$ppid" = "1" ]; then
        say "  [!] PID $pid  单核 ${cpu}% + 内核态 ${kern}% + PPid=1（孤儿）"
        say "      → 原父进程已退出，疑似 syscall 自旋空转；核实后 kill -9 $pid"
        flagged=1
    elif [ "$kerny" = "1" ]; then
        say "  [!] PID $pid  单核 ${cpu}% + 内核态 ${kern}%（父进程 $ppid）"
        say "      → 疑似 syscall 自旋，先确认它是否该常驻"
        flagged=1
    else
        say "  [·] PID $pid  单核 ${cpu}%（内核态 ${kern}%）—— 用户态为主，一般是正常计算"
    fi
done < "$TMP/top5"

if [ "$max_sensor" -ge 75000 ]; then
    say "  [!] 传感器最高 $(awk -v t="$max_sensor" 'BEGIN{printf "%.1f", t/1000}') ℃，持续高于 75℃ 会触发降频，核对上面 Top 进程"
    flagged=1
fi

if [ -n "$b_temp" ]; then
    if [ "$charging" = "1" ] && [ "$b_temp" -ge 400 ]; then
        say "  [·] 正在充电，电池 $((b_temp / 10))℃ 属充电正常升温，不判为异常"
    elif [ "$b_temp" -ge 450 ]; then
        say "  [!] 未充电但电池 $((b_temp / 10))℃，偏高"
        flagged=1
    fi
fi

if [ "$flagged" = "0" ]; then
    say "  [ok] 未发现单核独占或内核态异常进程"
fi

say ""
say "  （只做诊断，不会自动结束任何进程）"
say ""

exit "$flagged"
