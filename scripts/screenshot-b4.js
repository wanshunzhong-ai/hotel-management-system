#!/usr/bin/env node
/*
 * 第四批（客房服务部模块）截图：派工验收 / 迷你吧挂账 / 维修工单 / 失物招领 / 权限隔离。
 *
 * 与 scripts/screenshot.js 同一套「Node 原生 WebSocket 直驱 CDP」方案（原因见该文件注释）。
 * 用法与前置条件也一致：先起常驻无头 Chrome（--no-proxy-server），再执行本脚本。
 *
 * 环境变量：
 *   HMS_BASE   应用地址，默认 http://127.0.0.1:8081
 *   OUT_DIR    输出目录，默认 docs/screenshots/batch4
 *   CDP_URL    CDP 端点，默认 http://127.0.0.1:9222
 *   B4_BILL    合并了迷你吧消费的账单 id
 */
const fs = require('fs');
const path = require('path');

const BASE = process.env.HMS_BASE || 'http://127.0.0.1:8081';
const OUT = process.env.OUT_DIR || 'docs/screenshots/batch4';
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222';
const BILL = process.env.B4_BILL || '';

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
  await cdp.send('Network.clearBrowserCookies');

  async function go(url) {
    const loaded = cdp.once('Page.loadEventFired', 25000);
    await cdp.send('Page.navigate', { url });
    try { await loaded; } catch (e) { /* 超时也继续拍 */ }
    await sleep(350);
  }

  async function login(user, pass) {
    await cdp.send('Network.clearBrowserCookies');
    await go(BASE + '/login');
    const st = await cdp.eval(`(async () => {
      const t = document.querySelector('input[name="_csrf"]').value;
      const fd = new URLSearchParams({ username: '${user}', password: '${pass}', _csrf: t });
      const r = await fetch('/login', { method: 'POST', body: fd, credentials: 'same-origin' });
      return r.status;
    })()`);
    console.log('  登录 ' + user + ' → ' + st);
  }

  let shotNo = 0;
  async function shot(name, { w = 1440, h = 1000, scale = 2, full = true, before = null } = {}) {
    await cdp.send('Emulation.setDeviceMetricsOverride',
      { width: w, height: h, deviceScaleFactor: scale, mobile: false });
    await sleep(200);
    if (before) { await cdp.eval('(function(){' + before + '})()'); await sleep(500); }
    const params = { format: 'png' };
    if (full) {
      const m = await cdp.send('Page.getLayoutMetrics');
      const cs = m.cssContentSize || m.contentSize;
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

  console.log('开始截图 → ' + OUT);
  await login('admin', 'admin123');

  // 1) 派工与验收（管理员视角：新建派工单 + 全部派工单）
  await go(BASE + '/housekeeping/tasks');
  await shot('b4-01-派工与验收.png');

  // 2) 待验收状态（行内「通过 / 打回」+ 打回原因栏）
  await go(BASE + '/housekeeping/tasks?status=DONE');
  await shot('b4-02-待验收-通过或打回.png');

  // 3) 迷你吧挂账：在住房间挂账 + 入账状态
  await go(BASE + '/housekeeping/minibar');
  await shot('b4-03-迷你吧挂账.png');

  // 4) 账单详情：迷你吧消费已自动并入「其他消费」
  if (BILL) {
    await go(BASE + '/bills/' + BILL);
    await shot('b4-04-账单详情-迷你吧并入.png');
  }

  // 5) 维修工单：报修 / 开工 / 完工，含房态联动说明
  await go(BASE + '/maintenance');
  await shot('b4-05-维修工单.png');

  // 6) 失物招领：拾遗登记 + 认领
  await go(BASE + '/lost-found');
  await shot('b4-06-失物招领.png');

  // 7) 客房部视角：侧边栏只剩客房相关，且看不到「新建派工单」
  await login('housekeeping', '123456');
  await go(BASE + '/housekeeping/tasks');
  const nav = await cdp.eval("Array.from(document.querySelectorAll('.nav-link-item span')).map(e=>e.textContent).join(' / ')");
  console.log('  客房部导航 =', nav);
  await shot('b4-07-客房部视角-导航隔离.png');

  // 8) 平板宽度下的派工页（表格首列钉住 + 横向滚动）
  await shot('b4-08-平板-派工与验收.png', { w: 834, h: 1080, full: false });

  console.log('完成，共 ' + shotNo + ' 张。');
  ws.close();
}

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
