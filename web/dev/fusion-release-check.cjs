// 融合入口「松手那一刻被拖卡不许跳」的真实时序检查(ADR-019 刀 2c-1 返工)。
//
// 为什么要它:jsdom 没有布局、没有 CSS 过渡;旧录屏用的是页面假时钟。两者都没走到真实路径 ——
// 刀 2c-1 第一版在 React StrictMode(dev 构建)下松手 15ms 内被拖卡跳回自己的卡位,
// 生产构建里快速松手时又会**往前**跳到内联目标值(卡片自带 transition: transform .12s,屏上位置落后)。
// 这里用真时钟 + 真鼠标事件(page.mouse),每个 rAF 读一次被拖卡的 getBoundingClientRect。
//
// 判定(每个用例):
//   ① 揉合真的开始了(成品卡挂进卡列);
//   ② 松手后第一帧相对松手前最后一帧的位移,不超过松手前相邻两帧位移的最大值 + 1px(没有额外的跳);
//   ③ 交叉淡化路径:松手后到被拖卡淡到 opacity < 0.05 为止,屏上位置一动不动(±0.5px);
//      标志路径(刀 2c-2,有登记且非 reduced):被拖卡**会**从松手姿态摆正、靠拢再收进卡位,改判
//      「融合面挂上了」+「到淡净为止相邻两帧位移都不超过 MAX_STEP」(连续,没有跳)
//      +「烧边段(1080–2100ms)里布局次数 ≤ MAX_LAYOUTS」(Chrome Performance.getMetrics 的 LayoutCount,
//      守「动画期间不逐帧触发布局」;dev 稿 SVG 遮罩那一版这里是每帧一次)。
//
// 另有一组「揉合期间单击」用例(Felix 2026-11-05 裁定):揉合进行中用真鼠标单击另一张卡、对它按回车,
// 都不许进入世界(不许发出 /api/game/init);卡列标 aria-busy;演完之后同样的单击照常进入世界(对照)。
//
// 用法(需要 playwright-core 与本机 Chrome;不进 package.json,同 dev 稿录屏脚本的口径):
//   npm run dev                       # 或 npm run build && npx vite preview
//   NODE_PATH=$(npm root -g) node dev/fusion-release-check.cjs http://localhost:5173
//   (playwright-core 装在别处时把 NODE_PATH 指到那个 node_modules)
//   (CHROME_CHANNEL=chromium 改用 Playwright 自带浏览器;/api/archetypes 由同目录的 json 喂,不需要后端)
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');

const BASE = (process.argv[2] ?? 'http://localhost:5173').replace(/\/$/, '');
const PAYLOAD = fs.readFileSync(path.join(__dirname, 'fusion-release-check.archetypes.json'));
const NAMES = { rules_creepy: '规则怪谈', apocalypse: '末日生存', cthulhu: '克苏鲁', cultivation: '修仙' };

/** 有标志登记的组合(与 theme/registry 的 FUSIONS 同步;脚本只用它来选判定口径)。 */
const EMBLEM = new Set(['cultivation×cthulhu']);
const MAX_STEP = 30; // 标志路径交接段的单帧位移上限(px):靠拢 ≤ 约 60px / 420ms power2.out,首帧最大约 10px
// 烧边段 1020ms ≈ 61 帧(60Hz):逐帧布局 ≈ 61 次。判据是「不逐帧」,上限取帧数的一成(6)。
// 实测(生产构建,480 宽):这一窗口里 3 次,全是一次性的 —— 1186 / 1204ms 收进卡位那一刻
// (被拖卡清掉 transform / 层级、宿主隐去)与 2058ms 一次;1204–2058 之间零次。
const MAX_LAYOUTS = 6;

const CASES = [];
for (const g of ['dwell', 'fast']) {
  CASES.push({ vw: 375, from: 'cthulhu', to: 'cultivation', reduced: false, g });
  CASES.push({ vw: 480, from: 'cthulhu', to: 'cultivation', reduced: true, g });
  CASES.push({ vw: 480, from: 'cthulhu', to: 'cultivation', reduced: false, g });
  CASES.push({ vw: 480, from: 'rules_creepy', to: 'cultivation', reduced: false, g });
  CASES.push({ vw: 480, from: 'apocalypse', to: 'rules_creepy', reduced: false, g });
  CASES.push({ vw: 375, from: 'apocalypse', to: 'rules_creepy', reduced: false, g });
  CASES.push({ vw: 375, from: 'apocalypse', to: 'rules_creepy', reduced: true, g });
}

async function run(browser, c) {
  const ctx = await browser.newContext({
    viewport: { width: c.vw, height: 1040 },
    reducedMotion: c.reduced ? 'reduce' : 'no-preference',
  });
  await ctx.route(`${BASE}/api/**`, (r) =>
    new URL(r.request().url()).pathname === '/api/archetypes'
      ? r.fulfill({ contentType: 'application/json', body: PAYLOAD })
      : r.fulfill({ status: 404, body: '' }),
  );
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Performance.enable');
  const layouts = async () => (await cdp.send('Performance.getMetrics')).metrics.find((m) => m.name === 'LayoutCount').value;
  const emblem = EMBLEM.has(`${c.to}×${c.from}`) && !c.reduced;
  await page.goto(`${BASE}/`);
  const card = (id) => page.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });
  await card(c.from).waitFor();
  await page.waitForTimeout(900); // 入场动画走完
  await page.evaluate((name) => {
    const el = [...document.querySelectorAll('button')].find((b) => b.querySelector('h2')?.textContent === name);
    window.__frames = [];
    window.__emblem = false;
    new MutationObserver(() => {
      if (document.querySelector('[data-fusion-emblem]')) window.__emblem = true;
    }).observe(document.body, { childList: true, subtree: true });
    const loop = () => {
      const r = el.getBoundingClientRect();
      window.__frames.push({ t: performance.now(), top: r.top, left: r.left, op: +getComputedStyle(el).opacity });
      requestAnimationFrame(loop);
    };
    requestAnimationFrame(loop);
  }, NAMES[c.from]);
  const center = async (id) => {
    const b = await card(id).boundingBox();
    return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
  };
  const a = await center(c.from);
  const b = await center(c.to);
  await page.mouse.move(a.x, a.y);
  await page.mouse.down();
  await page.waitForTimeout(250); // 长按 180ms
  if (c.g === 'fast') {
    // 一次 move 内插 10 个 pointermove,到了立刻松手:屏上位置还落后内联值(.12s 过渡)
    await page.mouse.move(b.x, b.y, { steps: 10 });
    await page.waitForTimeout(40);
  } else {
    for (let i = 1; i <= 12; i++) {
      await page.mouse.move(a.x + 4, a.y + ((b.y - a.y) * i) / 12);
      await page.waitForTimeout(16);
    }
    for (let i = 0; i < 25; i++) {
      await page.mouse.move(a.x + 2 + (i % 2), b.y);
      await page.waitForTimeout(16);
    }
  }
  const tUp = await page.evaluate(() => performance.now());
  await page.mouse.up();
  let burnLayouts = null;
  if (emblem) {
    await page.waitForTimeout(1080);
    const l0 = await layouts();
    await page.waitForTimeout(1020);
    burnLayouts = (await layouts()) - l0;
  } else {
    await page.waitForTimeout(450);
  }
  const { frames, merged, sawEmblem } = await page.evaluate(() => ({
    frames: window.__frames,
    merged: !!document.querySelector('[data-fusion-key]'),
    sawEmblem: window.__emblem,
  }));
  await ctx.close();

  const i0 = frames.findIndex((f) => f.t > tUp);
  const pre = frames.slice(Math.max(0, i0 - 4), i0);
  const fading = [];
  for (let i = i0; i < frames.length && frames[i].op >= 0.05; i++) fading.push(frames[i]);
  const step = (p, q) => Math.hypot(q.top - p.top, q.left - p.left);
  const maxPreStep = Math.max(0, ...pre.slice(1).map((f, k) => step(pre[k], f)));
  const firstJump = fading.length ? step(pre[pre.length - 1], fading[0]) : Infinity;
  const drift = fading.length ? Math.max(...fading.map((f) => step(fading[0], f))) : Infinity;
  const maxStep = fading.length ? Math.max(0, ...fading.slice(1).map((f, k) => step(fading[k], f))) : Infinity;
  const ok =
    merged &&
    fading.length > 0 &&
    firstJump <= maxPreStep + 1 &&
    (emblem ? sawEmblem && maxStep <= MAX_STEP && burnLayouts <= MAX_LAYOUTS : !sawEmblem && drift <= 0.5);
  const fmt = (f) => `${(f.t - tUp).toFixed(0).padStart(5)}ms top=${f.top.toFixed(1)} left=${f.left.toFixed(1)} op=${f.op.toFixed(2)}`;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${c.vw} ${NAMES[c.from]}→${NAMES[c.to]}${c.reduced ? ' reduced' : ''} [${c.g}]` +
      `  [${emblem ? '标志' : '交叉淡化'}] merged=${merged} 融合面=${sawEmblem} 松手跳=${firstJump.toFixed(1)}px(松手前单帧最大 ${maxPreStep.toFixed(1)})` +
      (emblem
        ? ` 淡净前单帧最大=${maxStep.toFixed(1)}px 烧边段布局=${burnLayouts}`
        : ` 淡出期漂移=${drift.toFixed(2)}px`),
  );
  for (const f of [...pre.slice(-3), ...fading.slice(0, 3)]) console.log('      ' + fmt(f));
  return ok;
}

/** 揉合期间单击 / 回车不许进入世界;演完照常(对照)。 */
const CLICK_CASES = [
  { vw: 480, from: 'cthulhu', to: 'cultivation', other: 'rules_creepy', reduced: false }, // 标志路径(约 4.5s)
  { vw: 480, from: 'rules_creepy', to: 'cultivation', other: 'apocalypse', reduced: false }, // 交叉淡化(约 0.74s)
  { vw: 375, from: 'cthulhu', to: 'cultivation', other: 'apocalypse', reduced: true }, // reduced(约 0.62s)
];

async function runClick(browser, c) {
  const ctx = await browser.newContext({
    viewport: { width: c.vw, height: 1040 },
    reducedMotion: c.reduced ? 'reduce' : 'no-preference',
  });
  const inits = [];
  await ctx.route(`${BASE}/api/**`, (r) => {
    const p = new URL(r.request().url()).pathname;
    if (p === '/api/archetypes') return r.fulfill({ contentType: 'application/json', body: PAYLOAD });
    if (p.startsWith('/api/game/init')) inits.push(performance.now());
    return r.fulfill({ status: 404, body: '' });
  });
  const page = await ctx.newPage();
  await page.goto(`${BASE}/`);
  const card = (id) => page.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });
  await card(c.from).waitFor();
  await page.waitForTimeout(900);
  const center = async (id) => {
    const b = await card(id).boundingBox();
    return { x: b.x + b.width / 2, y: b.y + b.height / 2 };
  };
  const a = await center(c.from);
  const b = await center(c.to);
  await page.mouse.move(a.x, a.y);
  await page.mouse.down();
  await page.waitForTimeout(250);
  for (let i = 1; i <= 12; i++) {
    await page.mouse.move(a.x + 4, a.y + ((b.y - a.y) * i) / 12);
    await page.waitForTimeout(16);
  }
  for (let i = 0; i < 25; i++) {
    await page.mouse.move(a.x + 2 + (i % 2), b.y);
    await page.waitForTimeout(16);
  }
  await page.mouse.up();
  const list = page.locator('[aria-busy]');
  await page.waitForTimeout(120);
  const busyDuring = await list.count();
  // 揉合中:真鼠标单击另一张卡、单击刚松手的两张卡,再对另一张卡按回车。
  const o = await center(c.other);
  await page.mouse.click(o.x, o.y);
  await page.mouse.click(b.x, b.y);
  await card(c.other).focus();
  await page.keyboard.press('Enter');
  await page.waitForTimeout(200);
  const initsDuring = inits.length;
  const stillHere = await card(c.other).isVisible();
  // 等演完(aria-busy 撤掉),再点一次:照常进入世界(发出 init)。
  await page.waitForFunction(() => !document.querySelector('[aria-busy]'), null, { timeout: 8000 });
  const o2 = await center(c.other);
  await page.mouse.click(o2.x, o2.y);
  await page.waitForTimeout(300);
  const initsAfter = inits.length;
  await ctx.close();
  const ok = busyDuring === 1 && initsDuring === 0 && stillHere && initsAfter > 0;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  揉合期间单击 ${c.vw} ${NAMES[c.from]}→${NAMES[c.to]}${c.reduced ? ' reduced' : ''},点 ${NAMES[c.other]}` +
      `  aria-busy=${busyDuring === 1} 揉合中 init 请求=${initsDuring} 仍在选择屏=${stillHere} 演完后单击 init 请求=${initsAfter}`,
  );
  return ok;
}

(async () => {
  const browser = await chromium.launch({ channel: process.env.CHROME_CHANNEL ?? 'chrome' });
  let fail = 0;
  try {
    for (const c of CASES) if (!(await run(browser, c))) fail++;
    for (const c of CLICK_CASES) if (!(await runClick(browser, c))) fail++;
  } finally {
    await browser.close();
  }
  console.log(fail ? `\n${fail} 个用例失败` : '\n全部通过');
  process.exit(fail ? 1 : 0);
})();
