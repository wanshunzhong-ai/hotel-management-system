#!/usr/bin/env node
/*
 * 第七批（客房服务补全：客房主管 / 自行派工 / 清洁检查单 / 排班与工作量 /
 *           耗材出入库 / 移动端扫码上报 / 站内待办中心 / 布草外送结算）截图。
 *
 * 与 screenshot.js / screenshot-b4.js / screenshot-b5.js / screenshot-b6.js 同一套
 * 「Node 原生 WebSocket 直驱 CDP」方案：不依赖 puppeteer，只用一个常驻无头 Chrome。
 *
 *   1) 先起无头 Chrome：chrome --headless=new --remote-debugging-port=9222 --no-proxy-server
 *   2) 应用跑在 http://127.0.0.1:8081
 *   3) node scripts/screenshot-b7.js
 *
 * 建议在跑完 scripts/opt7-test.sh（数据尚未清理）之后再截图，这样页面是「有真实数据」的状态。
 *
 * 环境变量：
 *   HMS_BASE    应用地址，默认 http://127.0.0.1:8081
 *   OUT_DIR     输出目录，默认 docs/screenshots/batch7
 *   CDP_URL     CDP 端点，默认 http://127.0.0.1:9222
 */
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');

const BASE = process.env.HMS_BASE || 'http://127.0.0.1:8081';
const OUT = process.env.OUT_DIR || 'docs/screenshots/batch7';
const CDP = process.env.CDP_URL || 'http://127.0.0.1:9222';
const MYSQL = process.env.MYSQL_BIN || 'D:/SoftWare/MySQL/MySQL Server 9.0/bin/mysql.exe';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** 直连数据库取真实 id（比从 HTML 里正则抠更稳） */
function sql(query) {
  try {
    return execFileSync(MYSQL, [
      '--default-character-set=utf8mb4', '-uroot', '-p123456',
      '-D', 'hotel_management', '-N', '-B', '--batch', '-e', query,
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
  } catch (e) {
    return '';
  }
}

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

  async function go(url, opts = {}) {
    const loaded = cdp.once('Page.loadEventFired', 25000);
    await cdp.send('Page.navigate', { url });
    try { await loaded; } catch (e) { /* 超时也继续拍 */ }
    await sleep(400);
    // 页面体检：模板渲染到一半抛异常时，错误页会以 HTTP 200 + 半截正文的形式返回，
    // 截出来的图看着像正常页面实则整页坏掉（b7-11 就踩过）。这里按正文特征拦一道。
    if (!opts.allowError) {
      const txt = await cdp.eval('document.body ? document.body.innerText : ""');
      const hit = ERR_MARKS.find((m) => typeof txt === 'string' && txt.includes(m));
      if (hit) problems.push(url + '  ← 正文命中错误页标记「' + hit + '」');
    }
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
  const problems = [];
  const ERR_MARKS = ['操作未能完成', '系统开小差了', 'No converter for'];
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

  // 从库里取几条真实数据的 id
  const taskId = sql("select id from housekeeping_task where remark='第七批测试派工-快照' order by id desc limit 1;");
  const supplyId = sql("select id from supply_item where item_name='第七批测试耗材';");
  const batchId = sql("select id from outsource_batch where vendor='第七批测试洗涤公司' order by id desc limit 1;");
  const roomId = sql("select id from room where id in (select room_id from reservation where status='CHECKED_IN') order by id limit 1;");
  const taskRoomId = sql("select room_id from housekeeping_task where remark like '%测试%' order by id desc limit 1;");

  console.log('开始截图 → ' + OUT);
  await login('supervisor', '123456');

  // 1) 待办中心（第七批新增）：实时派生待办 + 分档高亮
  await go(BASE + '/todos');
  await shot('b7-01-待办中心.png');

  // 2) 派工与验收：新增「检查单」进度列 + 主管可自行派工
  await go(BASE + '/housekeeping/tasks');
  await shot('b7-02-派工与验收-检查单进度.png');

  // 3) 派工单详情：清洁检查单逐项打勾 + 全流程时间线（主管派工 → 服务员完成 → 主管验收）
  if (taskId) {
    await go(BASE + '/housekeeping/tasks/' + taskId);
    await shot('b7-03-派工单详情-清洁检查单打勾.png');
  } else {
    console.log('  （未找到测试派工单，跳过 b7-03）');
  }

  // 4) 清洁标准检查单模板（按分类分组维护）
  await go(BASE + '/checklists');
  await shot('b7-04-清洁标准检查单模板.png');

  // 5) 排班与工作量：三班次排班 + 按人工作量统计
  await go(BASE + '/housekeeping/schedule');
  await shot('b7-05-排班与工作量.png');

  // 6) 耗材出入库台账：库存 / 安全库存 / 预警 + 出入库快捷录入
  await go(BASE + '/supplies');
  await shot('b7-06-耗材出入库台账.png');

  // 7) 耗材流水明细：每一笔变动都留痕（含 stock_after 快照）
  if (supplyId) {
    await go(BASE + '/supplies/' + supplyId);
    await shot('b7-07-耗材出入库流水明细.png');
  } else {
    console.log('  （未找到测试耗材，跳过 b7-07）');
  }

  // 8) 移动端现场上报入口：手输房号 + 房间宫格 + 我的待办工单
  await go(BASE + '/hk');
  await shot('b7-08-移动端-现场上报入口.png');

  // 9) 房间操作台：报修 / 失物 / 挂账 / 改房态 / 一键完成工单（窄屏，模拟手机）
  const consoleRoom = taskRoomId || roomId;
  if (consoleRoom) {
    await go(BASE + '/hk/room/' + consoleRoom);
    await shot('b7-09-移动端-房间操作台.png', { w: 430, h: 1100, scale: 2, full: true });
  } else {
    console.log('  （未找到房间，跳过 b7-09）');
  }

  // 10) 布草外送批次列表
  await go(BASE + '/laundry/outsourcing');
  await shot('b7-10-布草外送批次列表.png');

  // 11) 布草外送交接单（打印媒体渲染，交给洗涤公司签字）
  if (batchId) {
    await go(BASE + '/laundry/outsourcing/' + batchId);
    await shot('b7-11-布草外送交接单打印.png', { print: true, w: 900, h: 1100, scale: 2, full: false, wait: 300 });
  } else {
    console.log('  （未找到外送批次，跳过 b7-11）');
  }

  // 12) 扫码消费：住客码 + 员工码并存（顺带验证该页此前那处表达式缺陷已修复）
  await login('admin', 'admin123');
  await go(BASE + '/consumables');
  await shot('b7-12-房间二维码-住客码与员工码并存.png');

  // 13) 权限边界：客房主管也进不了前台资金模块（403，角色继承是单向的）
  await login('supervisor', '123456');
  await go(BASE + '/payments', { allowError: true });  // 这一张拍的就是 403 拒绝页，属预期
  await shot('b7-13-权限边界-主管不可进前台资金模块.png', { w: 1280, h: 760, full: false, wait: 300 });

  if (problems.length) {
    console.error('⚠️  以下页面疑似渲染失败，截图不可用：');
    problems.forEach((p) => console.error('   · ' + p));
  } else {
    console.log('页面体检：全部正常，未发现错误页。');
  }
  console.log('完成，共 ' + shotNo + ' 张。');
  ws.close();
  if (problems.length) process.exit(1);
}

main().catch((e) => { console.error('失败：', e.message); process.exit(1); });
