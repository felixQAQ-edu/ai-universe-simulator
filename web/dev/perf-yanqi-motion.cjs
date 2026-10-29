// 《验讫》短运动稿性能探针(刀 2b,dev-only)。
//
// 前提:`npm run dev` 已在 :5173 跑着。用法:
//   NODE_PATH=$(npm root -g) node dev/perf-yanqi-motion.cjs
//
// 实时播放(不是逐帧定格),480 宽、dpr 2,Chrome CPU 降速 1× / 4× 各跑 3 遍:
// - 帧间隔:页面注入 rAF 计时,统计整段动画内 >25ms(掉 ≥1 帧)与 >50ms 的帧、最长一帧、估算掉帧数;
// - 布局:Chrome Performance 指标 LayoutCount / RecalcStyleCount 在动画开始与结束时各取一次,差值 = 动画期间的布局次数。
// 局限:headless Chromium 的合成与真机 GPU 不同,读数只作相对参考;真机由 Felix 看。
const { chromium } = require('playwright');

const URL = (process.env.KF_BASE ?? 'http://localhost:5173/dev/yanqi-keyframes.html') + '?play=1&streak=1';

async function run(browser, rate) {
  const ctx = await browser.newContext({ viewport: { width: 480, height: 1040 }, deviceScaleFactor: 2, colorScheme: 'dark' });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
  await cdp.send('Performance.enable');
  await cdp.send('Emulation.setCPUThrottlingRate', { rate });
  await page.addInitScript(() => {
    window.__frames = [];
    const tick = (t) => {
      window.__frames.push(t);
      requestAnimationFrame(tick);
    };
    requestAnimationFrame(tick);
  });
  await page.goto(URL);
  await page.waitForSelector('html[data-kf-ready="1"]');
  const metric = async () => {
    const { metrics } = await cdp.send('Performance.getMetrics');
    const m = Object.fromEntries(metrics.map((x) => [x.name, x.value]));
    return { layout: m.LayoutCount, style: m.RecalcStyleCount };
  };
  const t0 = await page.evaluate(() => performance.now());
  const before = await metric();
  await page.waitForTimeout(5900); // 原速整段 5.7s + 余量
  const after = await metric();
  const frames = await page.evaluate((start) => window.__frames.filter((t) => t >= start), t0);
  await ctx.close();

  const gaps = frames.slice(1).map((t, i) => t - frames[i]);
  const over25 = gaps.filter((g) => g > 25).length;
  const over50 = gaps.filter((g) => g > 50).length;
  const dropped = gaps.reduce((n, g) => n + Math.max(0, Math.round(g / 16.67) - 1), 0);
  const sorted = [...gaps].sort((a, b) => a - b);
  return {
    rate,
    frames: frames.length,
    fps: +((frames.length - 1) / ((frames.at(-1) - frames[0]) / 1000)).toFixed(1),
    p50: +sorted[Math.floor(sorted.length * 0.5)].toFixed(1),
    p95: +sorted[Math.floor(sorted.length * 0.95)].toFixed(1),
    max: +sorted.at(-1).toFixed(1),
    over25,
    over50,
    droppedEst: dropped,
    layoutDuring: after.layout - before.layout,
    styleRecalcDuring: after.style - before.style,
  };
}

(async () => {
  const browser = await chromium.launch();
  const out = [];
  for (const rate of [1, 4]) {
    for (let i = 0; i < 3; i++) out.push(await run(browser, rate));
  }
  await browser.close();
  console.table(out);
  console.log(JSON.stringify(out));
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
