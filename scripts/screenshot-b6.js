#!/usr/bin/env node
/*
 * 第六批（前台接待补全：收款流水 / 交接班对账 / 发票 / 换房 / 延迟退房 / 团队入住 / 房价方案 / 应收台账）截图。
 *
 * 与 scripts/screenshot.js、screenshot-b4.js、screenshot-b5.js 同一套「Node 原生 WebSocket 直驱 CDP」方案：
 * 不依赖 puppeteer，只用一个常驻无头 Chrome。用法与前置条件一致：
 *   1) 先起无头 Chrome：chrome --headless=new --remote-debugging-port=9222 --no-proxy-server
 *   2) 应用跑在 http://127.0.0.1:8081
 *   3) node scripts/screenshot-b6.js
 *
 * 建议在跑完 scripts/opt6-test.sh（数据尚未清理）之后再截图，这样页面是「有真实数据」的状态。
 *
 * 环境变量：
 *   HMS_BASE    应用地址，默认 http://127.0.0.1:8081
 *   OUT_DIR     输出目录，默认 docs/screenshots/batch6
 *   CDP_URL     CDP 端点，默认 http://127.0.0.1:9222
 */
const fs = require('fs');
const path = require('path');

const BASE = process.env.HMS_BASE || 'http://127.0.0.1:8081';
const OUT = process.env.OUT_DIR || 'docs/screenshots/batch6';
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222';

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

  // 用浏览器自身的登录态抓页面 HTML，用来从列表里挑一条真实数据的 id
  async function html(p) {
    return await cdp.eval(`fetch('${p}', { credentials: 'same-origin' }).then(r => r.text())`);
  }

  let shotNo = 0;
  async function shot(name, { w = 1440, h = 1000, scale = 2, full = true, wait = 0, before = null, print = false } = {}) {
    await cdp.send('Emulation.setDeviceMetricsOverride',
      { width: w, height: h, deviceScaleFactor: scale, mobile: false });
    await cdp.send('Emulation.setEmulatedMedia', print ? { media: 'print' } : { media: '' });
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

  const firstMatch = (s, re) => { const m = s && s.match(re); return m ? m[1] : null; };

  console.log('开始截图 → ' + OUT);
  await login('admin', 'admin123');

  // 1) 收款流水：押金 / 预订金 / 退款全量台账 + 汇总卡
  await go(BASE + '/payments');
  await shot('b6-01-收款流水台账.png');

  // 2) 收款流水：按支付方式筛选（微信）
  await go(BASE + '/payments?method=WECHAT');
  await shot('b6-02-收款流水-按支付方式筛选.png', { full: false, w: 1440, h: 820 });

  // 3) 交接班对账：当班净收款 + 按支付方式净额 + 交班表单
  await go(BASE + '/shift');
  await shot('b6-03-交接班对账.png');

  // 4) 交班单打印（用打印媒体渲染）
  const shiftId = firstMatch(await html('/shift'), /\/shift\/(\d+)\/print/);
  if (shiftId) {
    await go(BASE + '/shift/' + shiftId + '/print');
    await shot('b6-04-交班单打印.png', { print: true, w: 900, h: 1100, scale: 2, full: false, wait: 300 });
  } else {
    console.log('  （未找到交班单，跳过 b6-04）');
  }

  // 5) 发票管理：勾上「含已作废」——一张图同时展示有效票与作废票（作废是软删除）
  await go(BASE + '/invoices?includeCancelled=true');
  await shot('b6-05-发票管理.png');

  // 6) 发票打印
  const invId = firstMatch(await html('/invoices'), /\/invoices\/(\d+)\/print/);
  if (invId) {
    await go(BASE + '/invoices/' + invId + '/print');
    await shot('b6-06-发票打印.png', { print: true, w: 900, h: 1100, scale: 2, full: false, wait: 300 });
  } else {
    console.log('  （未找到发票，跳过 b6-06）');
  }

  // 7) 应收账款台账：未结 / 超期高亮 + 挂账单位
  await go(BASE + '/receivables');
  await shot('b6-07-应收账款台账.png');

  // 8) 房价方案：协议价 / 会员价
  await go(BASE + '/rate-plans');
  await shot('b6-08-房价方案-协议价与会员价.png');

  // 9) 团队入住工作台
  await go(BASE + '/groups/checkin');
  await shot('b6-09-团队入住工作台.png');

  // 10) 订单详情：押金 / 预订金收款卡片 + 换房 / 延迟退房入口
  const resId = firstMatch(await html('/reservations'), /\/reservations\/(\d+)(?!\/)/);
  if (resId) {
    await go(BASE + '/reservations/' + resId);
    await shot('b6-10-订单详情-押金与收款流水.png');
  } else {
    console.log('  （未找到订单，跳过 b6-10）');
  }

  // 11) 账单详情：挂账登记 + 发票开具卡片
  const billId = firstMatch(await html('/receivables?creditedOnly=true'), /\/bills\/(\d+)(?!\/)/)
             || firstMatch(await html('/bills'), /\/bills\/(\d+)(?!\/)/);
  if (billId) {
    await go(BASE + '/bills/' + billId);
    await shot('b6-11-账单详情-挂账登记与发票开具.png');
  } else {
    console.log('  （未找到账单，跳过 b6-11）');
  }

  // 12) 权限边界：客房部进不了收款流水（403 页）
  await login('housekeeping', '123456');
  await go(BASE + '/payments');
  await shot('b6-12-权限边界-客房部不可进前台模块.png', { w: 1280, h: 760, full: false, wait: 300 });

  console.log('完成，共 ' + shotNo + ' 张。');
  ws.close();
}

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
