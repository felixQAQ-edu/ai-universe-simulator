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
//   ③ 松手后到被拖卡淡到 opacity < 0.05 为止,屏上位置一动不动(±0.5px)。
//
// 用法(需要 playwright-core 与本机 Chrome;不进 package.json,同 dev 稿录屏脚本的口径):
//   npm run dev                       # 或 npm run build && npx vite preview
//   NODE_PATH=$(npm root -g) node dev/fusion-release-check.cjs http://localhost:5173
//   (CHROME_CHANNEL=chromium 改用 Playwright 自带浏览器;/api/archetypes 由同目录的 json 喂,不需要后端)
const { chromium } = require('playwright-core');
const fs = require('node:fs');
const path = require('node:path');

const BASE = (process.argv[2] ?? 'http://localhost:5173').replace(/\/$/, '');
const PAYLOAD = fs.readFileSync(path.join(__dirname, 'fusion-release-check.archetypes.json'));
const NAMES = { rules_creepy: '规则怪谈', apocalypse: '末日生存', cthulhu: '克苏鲁', cultivation: '修仙' };

const CASES = [];
for (const g of ['dwell', 'fast']) {
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
  await page.goto(`${BASE}/`);
  const card = (id) => page.getByRole('button', { name: new RegExp(`^${NAMES[id]}`) });
  await card(c.from).waitFor();
  await page.waitForTimeout(900); // 入场动画走完
  await page.evaluate((name) => {
    const el = [...document.querySelectorAll('button')].find((b) => b.querySelector('h2')?.textContent === name);
    window.__frames = [];
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
  await page.waitForTimeout(450);
  const { frames, merged } = await page.evaluate(() => ({
    frames: window.__frames,
    merged: !!document.querySelector('[data-fusion-key]'),
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
  const ok = merged && fading.length > 0 && firstJump <= maxPreStep + 1 && drift <= 0.5;
  const fmt = (f) => `${(f.t - tUp).toFixed(0).padStart(5)}ms top=${f.top.toFixed(1)} left=${f.left.toFixed(1)} op=${f.op.toFixed(2)}`;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${c.vw} ${NAMES[c.from]}→${NAMES[c.to]}${c.reduced ? ' reduced' : ''} [${c.g}]` +
      `  merged=${merged} 松手跳=${firstJump.toFixed(1)}px(松手前单帧最大 ${maxPreStep.toFixed(1)}) 淡出期漂移=${drift.toFixed(2)}px`,
  );
  for (const f of [...pre.slice(-3), ...fading.slice(0, 3)]) console.log('      ' + fmt(f));
  return ok;
}

(async () => {
  const browser = await chromium.launch({ channel: process.env.CHROME_CHANNEL ?? 'chrome' });
  let fail = 0;
  try {
    for (const c of CASES) if (!(await run(browser, c))) fail++;
  } finally {
    await browser.close();
  }
  console.log(fail ? `\n${fail} 个用例失败` : '\n全部通过');
  process.exit(fail ? 1 : 0);
})();
