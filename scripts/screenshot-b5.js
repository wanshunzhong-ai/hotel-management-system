#!/usr/bin/env node
/*
 * 第五批（洗衣布草 / 扫码消费 / 查房评分 / 房态大屏）截图。
 *
 * 与 scripts/screenshot.js、screenshot-b4.js 同一套「Node 原生 WebSocket 直驱 CDP」方案：
 * 不依赖 puppeteer，只用一个常驻无头 Chrome。用法与前置条件一致：
 *   1) 先起无头 Chrome：chrome --headless=new --remote-debugging-port=9222 --no-proxy-server
 *   2) 应用跑在 http://127.0.0.1:8081
 *   3) node scripts/screenshot-b5.js
 *
 * 环境变量：
 *   HMS_BASE    应用地址，默认 http://127.0.0.1:8081
 *   OUT_DIR     输出目录，默认 docs/screenshots/batch5
 *   CDP_URL     CDP 端点，默认 http://127.0.0.1:9222
 *   SCAN_TOKEN  在住房间的扫码令牌（截图用；不传则跳过扫码相关两张）
 */
const fs = require('fs');
const path = require('path');

const BASE = process.env.HMS_BASE || 'http://127.0.0.1:8081';
const OUT = process.env.OUT_DIR || 'docs/screenshots/batch5';
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222';
const SCAN_TOKEN = process.env.SCAN_TOKEN || '';

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
    await sleep(400);
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
  async function shot(name, { w = 1440, h = 1000, scale = 2, full = true, wait = 0, before = null } = {}) {
    await cdp.send('Emulation.setDeviceMetricsOverride',
      { width: w, height: h, deviceScaleFactor: scale, mobile: false });
    await sleep(250);
    if (before) { await cdp.eval('(function(){' + before + '})()'); }
    if (wait) await sleep(wait);
    const params = { format: 'png' };
    if (full) {
      const m = await cdp.send('Page.getLayoutMetrics');
      const cs = m.cssContentSize || m.contentSize;
      const height = Math.min(Math.ceil(cs.height), 5200);
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

  // 1) 洗衣与布草：收件表单 + 洗衣单 + 布草库存（整页）
  await go(BASE + '/laundry');
  await shot('b5-01-洗衣与布草.png');

  // 2) 布草库存尾部（预警 + 三态数量），滚到底只看库存区
  await shot('b5-02-布草库存与安全库存预警.png', {
    full: false, w: 1440, h: 1000, wait: 300,
    before: "window.scrollTo(0, document.body.scrollHeight);"
  });

  // 3) 扫码消费：消费目录 + 房间二维码网格
  await go(BASE + '/consumables');
  await shot('b5-03-扫码消费-目录与房间二维码.png');

  // 4) 迷你吧挂账：来源列显示「住客扫码」
  await go(BASE + '/housekeeping/minibar');
  await shot('b5-04-迷你吧挂账-来源可审计.png');

  // 5) 查房评分：四项打分 + 结论 + 整改单号
  await go(BASE + '/inspections');
  await shot('b5-05-查房评分.png');

  // 6) 房态大屏：全高清（墙挂机常见分辨率）
  await go(BASE + '/board');
  await shot('b5-06-房态大屏-全高清.png', { w: 1920, h: 1080, scale: 1, full: false, wait: 1200 });

  // 7) 房态大屏：小屏自适应（值班台笔记本）
  await shot('b5-07-房态大屏-小屏自适应.png', { w: 1024, h: 768, scale: 1, full: false, wait: 800 });

  // 8) 住客扫码页（免登录、手机宽度）
  if (SCAN_TOKEN) {
    await cdp.send('Network.clearBrowserCookies');
    await go(BASE + '/scan/' + SCAN_TOKEN);
    await shot('b5-08-住客扫码下单页.png', { w: 390, h: 844, scale: 2, full: false, wait: 400 });

    // 9) 提交一单，拍成功页
    await cdp.eval(`(function(){
      const f = document.querySelector('form.item-row');
      if (f) { f.querySelector('input[name="quantity"]').value = 1; f.submit(); }
      return !!f;
    })()`);
    await sleep(1500);
    await shot('b5-09-扫码下单成功.png', { w: 390, h: 844, scale: 2, full: false });
  } else {
    console.log('  （未提供 SCAN_TOKEN，跳过扫码页两张）');
  }

  // 10) 不合格查房自动生成的整改派工单，在派工页可见
  await login('admin', 'admin123');
  await go(BASE + '/housekeeping/tasks');
  await shot('b5-10-查房不合格自动整改派工.png');

  console.log('完成，共 ' + shotNo + ' 张。');
  ws.close();
}

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
