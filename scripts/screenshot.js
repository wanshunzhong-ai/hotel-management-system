#!/usr/bin/env node
/*
 * 用 Chrome DevTools Protocol 给页面拍高清截图（第三批优化的可视化验收）。
 *
 * 为什么不用 puppeteer/agent-browser：本机 agent-browser 自带的 Chromium 冷启动会挂死，
 * 而 Node 22 自带全局 WebSocket，直接手驱 CDP 最稳。详见 docs/验收记录。
 *
 * 用法：
 *   1) 先起一个常驻无头 Chrome（必须 --no-proxy-server，否则 sandbox 代理会拦 localhost）：
 *      "/c/Program Files/Google/Chrome/Application/chrome.exe" \
 *        --headless=new --disable-gpu --no-first-run --no-proxy-server \
 *        --remote-debugging-port=9222 --user-data-dir=/tmp/hms-chrome about:blank
 *   2) HMS_BASE / OUT_DIR / B3_* 环境变量就绪后：
 *      node scripts/screenshot.js
 *
 * 环境变量：
 *   HMS_BASE      应用地址，默认 http://127.0.0.1:8081
 *   OUT_DIR       输出目录，默认 docs/screenshots/batch3
 *   CDP_URL       CDP 端点，默认 http://127.0.0.1:9222
 *   B3_BILL       要拍的账单 id（已结账，最好带折扣/反结账备注）
 *   B3_ORDER_EARLY 带「提前离店减免」的订单 id
 *   B3_ORDER_IN    仍在住（CHECKED_IN）的订单 id，用于拍退房弹窗
 */
const fs = require('fs');
const path = require('path');

const BASE = process.env.HMS_BASE || 'http://127.0.0.1:8081';
const OUT = process.env.OUT_DIR || 'docs/screenshots/batch3';
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222';
const BILL = process.env.B3_BILL || '';
const ORDER_EARLY = process.env.B3_ORDER_EARLY || '';
const ORDER_IN = process.env.B3_ORDER_IN || '';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function pickTarget() {
  for (let i = 0; i < 40; i++) {
    try {
      const list = await (await fetch(CDP + '/json/list')).json();
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page.webSocketDebuggerUrl;
    } catch (e) { /* Chrome 还没起来 */ }
    await sleep(500);
  }
  throw new Error('连接不上 CDP：' + CDP + '（无头 Chrome 起了吗？）');
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.seq = 0;
    this.pending = new Map();
    this.handlers = new Map();
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
      } else if (msg.method && this.handlers.has(msg.method)) {
        for (const h of [...this.handlers.get(msg.method)]) h(msg.params);
      }
    };
  }
  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.seq;
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  once(method, timeout = 20000) {
    return new Promise((resolve, reject) => {
      if (!this.handlers.has(method)) this.handlers.set(method, new Set());
      const set = this.handlers.get(method);
      const h = (p) => { clearTimeout(timer); set.delete(h); resolve(p); };
      const timer = setTimeout(() => { set.delete(h); reject(new Error('等待 ' + method + ' 超时')); }, timeout);
      set.add(h);
    });
  }
  eval(expression, awaitPromise = true) {
    return this.send('Runtime.evaluate', { expression, awaitPromise, returnByValue: true })
      .then((r) => (r.exceptionDetails ? 'ERR:' + JSON.stringify(r.exceptionDetails.text) : r.result.value));
  }
}

async function main() {
  const wsUrl = await pickTarget();
  const ws = new WebSocket(wsUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  const cdp = new Cdp(ws);

  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Network.enable');
  // 关键：清干净 cookie。若 9222 上残留着上个实例的登录态，
  // 「未登录访问后台」这类验证会得出完全错误的结论。
  await cdp.send('Network.clearBrowserCookies');

  async function go(url) {
    const loaded = cdp.once('Page.loadEventFired', 25000);
    await cdp.send('Page.navigate', { url });
    try { await loaded; } catch (e) { /* 超时也继续拍 */ }
    await sleep(350);
  }

  async function viewport(w, h, scale) {
    await cdp.send('Emulation.setDeviceMetricsOverride',
      { width: w, height: h, deviceScaleFactor: scale, mobile: false });
  }

  let shotNo = 0;
  async function shot(name, { w = 1440, h = 1000, scale = 2, full = true, before = null } = {}) {
    await viewport(w, h, scale);
    await sleep(200);
    if (before) { await cdp.eval('(function(){' + before + '})()'); await sleep(500); }
    let params = { format: 'png' };
    if (full) {
      const m = await cdp.send('Page.getLayoutMetrics');
      const cs = m.cssContentSize || m.contentSize;
      // Chrome 单张位图有上限，超高页面截断（房态图 40 间房也就 ~2500px）
      const height = Math.min(Math.ceil(cs.height), 4200);
      params.clip = { x: 0, y: 0, width: Math.ceil(cs.width), height, scale: 1 };
      params.captureBeyondViewport = true;
    } else {
      params.clip = { x: 0, y: 0, width: w, height: h, scale: 1 };
    }
    const res = await cdp.send('Page.captureScreenshot', params);
    const file = path.join(OUT, name);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, Buffer.from(res.data, 'base64'));
    shotNo++;
    console.log('  [' + String(shotNo).padStart(2, '0') + '] ' + name);
  }

  console.log('登录 admin …');
  await go(BASE + '/login');
  const loginRes = await cdp.eval(`(async () => {
    const t = document.querySelector('input[name="_csrf"]').value;
    const fd = new URLSearchParams({ username: 'admin', password: 'admin123', _csrf: t });
    const r = await fetch('/login', { method: 'POST', body: fd, credentials: 'same-origin' });
    return r.status;
  })()`);
  console.log('  login status =', loginRes);
  await go(BASE + '/dashboard');
  const who = await cdp.eval("document.querySelector('.stat-label') ? document.querySelector('.stat-label').textContent : document.body.className");
  console.log('  看板首卡 =', who);

  const TODAY = new Date();
  const D3 = new Date(TODAY.getTime() + 3 * 86400000).toISOString().slice(0, 10);

  console.log('开始截图 → ' + OUT);

  // 1) 房态图：今天（物理状态 + 营业状态图例 + 可售/已售汇总）
  await go(BASE + '/rooms/board');
  await shot('b3-01-房态图-今天.png');

  // 2) 房态图：3 天后（日期选择器生效，同一间房换一天就变成「已预订」）
  await go(BASE + '/rooms/board?date=' + D3);
  await shot('b3-02-房态图-未来日期.png');

  // 3) 数据看板：可售 / 已售 / 出租率改用「按日期实时算」的口径
  await go(BASE + '/dashboard');
  await shot('b3-03-数据看板-口径.png');

  // 4) 账单列表（钉住首列 + 导出按钮）
  await go(BASE + '/bills');
  await shot('b3-04-账单列表.png');

  // 5) 账单详情：折扣 / 减免 + 备注留痕
  if (BILL) {
    await go(BASE + '/bills/' + BILL);
    await shot('b3-05-账单详情-折扣与留痕.png');
    await go(BASE + '/bills/' + BILL + '/print');
    await shot('b3-06-账单小票打印页.png', { w: 860, h: 1100 });
  }

  // 6) 订单：提前离店减免
  if (ORDER_EARLY) {
    await go(BASE + '/reservations/' + ORDER_EARLY);
    await shot('b3-07-订单详情-提前离店减免.png');
  }

  // 7) 退房弹窗（实际离店日期 + 实时重算预览）
  if (ORDER_IN) {
    await go(BASE + '/reservations/' + ORDER_IN);
    const min = await cdp.eval("document.getElementById('actualCheckOutDate')?.getAttribute('min') || ''");
    await shot('b3-08-退房弹窗-实际离店日期.png', {
      before: `var el=document.getElementById('checkoutModal');if(!el)return 'no-modal';
               var i=document.getElementById('actualCheckOutDate');
               if(i){i.value='${min}';i.dispatchEvent(new Event('input',{bubbles:true}));i.dispatchEvent(new Event('change',{bubbles:true}));}
               bootstrap.Modal.getOrCreateInstance(el).show();return 'ok';`,
      full: false, w: 1440, h: 900,
    });
  }

  // 8) 操作日志（仅管理员可见）
  await go(BASE + '/logs');
  await shot('b3-09-操作日志.png');
  await go(BASE + '/logs?action=' + encodeURIComponent('反结账') + '&size=100');
  await shot('b3-10-操作日志-按动作筛选.png');

  // 9) 交接班名单打印页
  await go(BASE + '/dashboard/print/today');
  await shot('b3-11-交接班名单打印页.png', { w: 900, h: 1200 });

  // 10) 平板适配：834px 宽（首列钉住 + 卡片流）
  await go(BASE + '/bills');
  await shot('b3-12-平板-账单列表.png', { w: 834, h: 1080, full: false });
  await go(BASE + '/rooms/board?date=' + D3);
  await shot('b3-13-平板-房态图卡片流.png', { w: 834, h: 1080, full: false });

  console.log('完成，共 ' + shotNo + ' 张。');
  ws.close();
}

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
