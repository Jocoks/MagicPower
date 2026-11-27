'use strict';

const $ = (id) => document.getElementById(id);

const LEVEL_TAG = {
  danger: '高度可疑',
  warn: '可疑',
  info: '提示',
  ok: '正常'
};
const LEVEL_RANK = { danger: 3, warn: 2, info: 1, ok: 0 };

const state = {
  report: null,
  running: false,
  stopping: false,
  autoTimer: null
};

// 当前视图。runCheck 需要它来判断"是否还在检查页里"。
let currentView = null;

function esc(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  }[c]));
}

function setStatus(text, kind) {
  const el = $('status');
  el.textContent = text;
  el.className = 'status' + (kind ? ' ' + kind : '');
}

/* ---------- 渲染 ---------- */

function renderFindings(findings) {
  const box = $('findings');
  const card = box.closest('.card');

  let worst = 'ok';
  for (const f of findings) {
    if ((LEVEL_RANK[f.level] ?? 0) > (LEVEL_RANK[worst] ?? 0)) {
      worst = f.level;
    }
  }
  card.dataset.level = worst;

  box.innerHTML = findings.map((f) => {
    const stoppable = f.pid && (f.level === 'danger' || f.level === 'warn');
    const action = f.action
      ? `<code class="finding-action">${esc(f.action)}</code>`
      : '';
    const button = stoppable
      ? `<button class="stop-btn ${esc(f.level)}" data-pid="${f.pid}">停止进程</button>`
      : '';
    const actions = (button || action)
      ? `<div class="finding-actions">${button}${action}</div>`
      : '';
    return `
      <div class="finding">
        <div class="finding-title">
          <span class="tag ${esc(f.level)}">${esc(LEVEL_TAG[f.level] || f.level)}</span>
          <span>${esc(f.title)}</span>
        </div>
        ${f.detail ? `<p class="finding-detail">${esc(f.detail)}</p>` : ''}
        ${actions}
      </div>`;
  }).join('');

  box.querySelectorAll('.stop-btn').forEach((btn) => {
    btn.addEventListener('click', () => onStop(btn));
  });
}

function renderBattery(b) {
  $('battery').innerHTML = `
    <dt>温度</dt><dd>${b.temperature.toFixed(1)} ℃</dd>
    <dt>状态</dt><dd>${esc(b.status || '未知')}</dd>
    <dt>电量</dt><dd>${b.capacity}%</dd>
    <dt>电流</dt><dd>${b.current} ${esc(b.unit)}</dd>`;
}

function renderCPU(c) {
  $('cpu').innerHTML = `
    <dt>总占用</dt><dd>${c.usedPercent.toFixed(1)}%</dd>
    <dt>占用核心</dt><dd>${c.usedCores.toFixed(2)} / ${c.totalCores.toFixed(0)}</dd>
    <dt>内核态</dt><dd>${c.kernelPercent.toFixed(0)}%</dd>`;
}

function renderSensors(sensors) {
  const box = $('sensors');
  if (!sensors || sensors.length === 0) {
    box.innerHTML = '<li><span>（读不到温度传感器）</span></li>';
    return;
  }
  box.innerHTML = sensors.map((s) => `
    <li>
      <span>${esc(s.type)}</span>
      <span>${s.temperature.toFixed(1)} ℃</span>
    </li>`).join('');
}

function renderTop(top) {
  const body = $('top');
  if (!top || top.length === 0) {
    body.innerHTML = '<tr><td colspan="4">没有采样到进程活动</td></tr>';
    return;
  }
  body.innerHTML = top.map((p) => {
    const hot = p.cpuPercent >= 80 ? ' class="hot"' : '';
    const name = p.ppid === 1 ? `${p.name}（PPid=1）` : p.name;
    return `
      <tr>
        <td>${p.pid}</td>
        <td class="num"${hot}>${p.cpuPercent.toFixed(1)}%</td>
        <td class="num">${p.kernelPercent}%</td>
        <td class="mono">${esc(name)}</td>
      </tr>`;
  }).join('');
}

function renderService(svc) {
  const el = $('service');
  if (!svc) {
    el.hidden = true;
    return;
  }
  el.hidden = false;
  el.className = 'service ' + (svc.root ? 'ok' : 'warn');
  el.textContent = svc.root
    ? `检查服务运行中（PID ${svc.pid}）· root 已就绪`
    : `检查服务运行中（PID ${svc.pid}）· 非 root，读不到其他进程，结果会漏`;
}

function render(report) {
  renderService(report.service);
  renderFindings(report.findings || []);
  renderBattery(report.battery);
  renderCPU(report.cpu);
  renderSensors(report.sensors);
  renderTop(report.top);
  $('log').textContent = report.log || '';
}

/* ---------- 检查 ---------- */

async function runCheck() {
  if (state.running) return;
  // 用户已经离开检查页时不要继续采样，否则首页/后台也会占用 CPU。
  if (currentView !== 'thermal') return;
  state.running = true;

  const windowSec = $('window').value;
  $('run').disabled = true;
  $('run').textContent = '采样中…';
  setStatus(`正在采样 ${windowSec} 秒，请稍候…`, 'busy');

  try {
    const res = await fetch(`/api/check?window=${encodeURIComponent(windowSec)}`, {
      cache: 'no-store'
    });
    if (!res.ok) {
      throw new Error(`HTTP ${res.status}`);
    }
    const report = await res.json();
    state.report = report;
    render(report);
    $('result').hidden = false;
    setStatus(`检查完成 · ${report.generatedAt}`);
  } catch (err) {
    setStatus(`检查失败：${err.message}。请确认后端服务正在运行。`, 'error');
  } finally {
    state.running = false;
    $('run').disabled = false;
    $('run').textContent = '开始检查';
  }
}

/* ---------- 停止异常进程 ---------- */

// 页面内确认框。WebView 默认没有实现 window.confirm，调用会静默返回
// false，导致按钮点了没反应，所以必须自己实现。
function askConfirm(title, message) {
  return new Promise((resolve) => {
    const modal = $('modal');
    $('modal-title').textContent = title;
    $('modal-text').textContent = message;
    modal.hidden = false;
    requestAnimationFrame(() => modal.classList.add('show'));

    const finish = (result) => {
      modal.classList.remove('show');
      modal.hidden = true;
      $('modal-ok').removeEventListener('click', onOk);
      $('modal-cancel').removeEventListener('click', onCancel);
      modal.removeEventListener('click', onBackdrop);
      resolve(result);
    };
    const onOk = () => finish(true);
    const onCancel = () => finish(false);
    const onBackdrop = (e) => { if (e.target === modal) finish(false); };

    $('modal-ok').addEventListener('click', onOk);
    $('modal-cancel').addEventListener('click', onCancel);
    modal.addEventListener('click', onBackdrop);
  });
}

async function onStop(btn) {
  if (state.stopping || !state.report) return;

  const pid = Number(btn.dataset.pid);
  const finding = (state.report.findings || []).find((f) => f.pid === pid);
  const title = finding ? finding.title : `PID ${pid}`;

  const confirmed = await askConfirm(
    '确认停止这个进程？',
    `${title}\n\n会先发送 SIGTERM，2 秒后仍在则发送 SIGKILL。\n关键系统进程会被服务端拒绝。`
  );
  if (!confirmed) return;

  state.stopping = true;
  btn.disabled = true;
  btn.textContent = '停止中…';
  await doStop(pid, false);
  state.stopping = false;
  btn.disabled = false;
  btn.textContent = '停止进程';
}

async function doStop(pid, force) {
  setStatus(`正在停止 PID ${pid}…`, 'busy');
  try {
    const res = await fetch('/api/stop', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        pid,
        mode: 'kill',
        token: state.report.stopToken,
        force: !!force
      })
    });
    const data = await res.json();

    if (data.ok) {
      setStatus(`PID ${pid}：${data.message}，正在重新检查…`);
      setTimeout(runCheck, 700);
      return;
    }

    // 被"系统进程"规则拦下时，给一次明确说明风险后的强制选择。
    if (!force && data.message.indexOf('系统进程') >= 0) {
      const forced = await askConfirm(
        '该进程被判定为系统进程',
        `${data.message}\n\n它可能是系统关键服务，也可能是你自己用 root 运行的程序。` +
        `\n确认无害后再强制停止；init、system_server 这类关键进程仍会被拒绝。`
      );
      if (forced) {
        await doStop(pid, true);
        return;
      }
    }

    setStatus(`PID ${pid} 停止失败：${data.message}`, 'error');
  } catch (err) {
    setStatus(`停止请求失败：${err.message}`, 'error');
  }
}

/* ---------- 复制日志 ---------- */

async function copyText(text) {
  // 127.0.0.1 / localhost 属于安全上下文，clipboard API 可用；
  // 通过局域网 IP 打开时不是安全上下文，必须退回 execCommand。
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return true;
    } catch (err) {
      // 继续尝试降级方案
    }
  }
  const ta = document.createElement('textarea');
  ta.value = text;
  ta.setAttribute('readonly', '');
  ta.style.position = 'fixed';
  ta.style.top = '-1000px';
  ta.style.opacity = '0';
  document.body.appendChild(ta);
  ta.select();
  ta.setSelectionRange(0, ta.value.length);
  let ok = false;
  try {
    ok = document.execCommand('copy');
  } catch (err) {
    ok = false;
  }
  document.body.removeChild(ta);
  return ok;
}

async function onCopy() {
  if (!state.report || !state.report.log) {
    setStatus('还没有检查结果，先点「开始检查」。', 'error');
    return;
  }
  const btn = $('copy');
  const ok = await copyText(state.report.log);
  btn.textContent = ok ? '已复制' : '复制失败';
  if (!ok) {
    setStatus('浏览器拒绝了剪贴板访问，请手动选中下方日志复制。', 'error');
  }
  setTimeout(() => { btn.textContent = '复制日志'; }, 1600);
}

/* ---------- 自动刷新 ---------- */

function toggleAuto(enabled) {
  if (state.autoTimer) {
    clearInterval(state.autoTimer);
    state.autoTimer = null;
  }
  if (enabled) {
    state.autoTimer = setInterval(() => {
      if (!state.running) runCheck();
    }, 30000);
  }
}

/* ---------- 初始化 ---------- */

$('run').addEventListener('click', runCheck);
$('copy').addEventListener('click', onCopy);
$('auto').addEventListener('change', (e) => toggleAuto(e.target.checked));
$('back').addEventListener('click', () => { location.hash = '#/'; });

/* ---------- 路由 ----------
 * 用 hash 路由而不是一次性渲染，方便以后继续加功能页：
 * 新增功能 = 在 index.html 加一个 view + 这里加一条映射。
 */
const VIEWS = ['home', 'thermal'];

function route() {
  const hash = location.hash || '#/';
  const view = hash === '#/thermal' ? 'thermal' : 'home';

  if (view !== currentView) {
    currentView = view;
    for (const name of VIEWS) {
      const el = document.getElementById('view-' + name);
      if (el) el.hidden = (name !== view);
    }
    window.scrollTo(0, 0);
  }

  if (view !== 'thermal') {
    // 离开检查页就停掉自动刷新，避免在后台一直采样耗电
    if ($('auto').checked) {
      $('auto').checked = false;
    }
    toggleAuto(false);
    return;
  }

  // 只在真正进入检查页时才发起采样，首页不空转
  if (!state.report && !state.running) {
    runCheck();
  }
}

window.addEventListener('hashchange', route);
route();
