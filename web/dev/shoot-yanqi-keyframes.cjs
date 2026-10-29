// 《验讫》静态关键帧截图(刀 2a,dev-only)。
//
// 前提:`npm run dev` 已在 :5173 跑着。用法(Playwright 不进 web 依赖,用环境里全局装的那份):
//   NODE_PATH=$(npm root -g) node dev/shoot-yanqi-keyframes.cjs [输出目录]
//
// 深色。三组图:
//   zoom/    放大图:480 宽、dpr 2,①–⑤ 各一张卡片区裁切(裁切框与第一轮同一算法,同位置同尺寸);
//   actual/  实际尺寸:480 宽、dpr 1,①–⑤ 各一张 1:1 裁切 + 一张 ①→⑤ 横排连看(同样 1:1,不缩放);
//   narrow/  窄屏:375 宽、dpr 1,只截 ③。
// 裁切框每组只量一次(取自 ① 的修仙卡位),并逐帧断言卡位没动(动了就报错,不出图)。
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.KF_BASE ?? 'http://localhost:5173/dev/yanqi-keyframes.html';
const OUT = path.resolve(process.argv[2] ?? 'dev/yanqi-keyframes-shots');
const HEIGHT = 1040;
const FRAMES = [
  { id: '1', label: '① 交接' },
  { id: '2', label: '② 停顿·靠拢' },
  { id: '3', label: '③ 标志' },
  { id: '4', label: '④ 揭晓中的过渡' },
  { id: '5', label: '⑤ 揭晓' },
];
const PAD = 12;

/** 修仙卡位的布局位置(offset*,不受 transform 影响)。 */
async function hostSlot(page) {
  return page.evaluate(() => {
    let el = document.querySelector('[data-kf-slot="host"]');
    const w = el.offsetWidth;
    const h = el.offsetHeight;
    let x = 0;
    let y = 0;
    for (; el; el = el.offsetParent) {
      x += el.offsetLeft;
      y += el.offsetTop;
    }
    return { x, y, w, h };
  });
}

/** 上沿 = 修仙卡位顶上留一截;下沿 = 修仙卡位底 + ① 压出去的那截(0.56H + 旋转余量)。 */
function clipFor(host, width) {
  const top = host.y - 24 - PAD;
  const bottom = host.y + host.h + 0.56 * host.h + 24 + PAD;
  return { x: 0, y: Math.round(top), width, height: Math.round(bottom - top) };
}

async function shootSet(browser, { name, width, dpr, frames }) {
  const dir = path.join(OUT, name);
  fs.mkdirSync(dir, { recursive: true });
  const ctx = await browser.newContext({ viewport: { width, height: HEIGHT }, deviceScaleFactor: dpr, colorScheme: 'dark' });
  const page = await ctx.newPage();
  let clip = null;
  let ref = null;
  const readings = [];
  for (const f of frames) {
    await page.goto(`${BASE}?frame=${f.id}`);
    await page.waitForSelector('html[data-kf-ready="1"]');
    await page.evaluate(() => document.fonts.ready);
    const host = await hostSlot(page);
    if (!ref) {
      ref = host;
      clip = clipFor(host, width);
    } else if (Math.abs(host.y - ref.y) > 0.5 || Math.abs(host.h - ref.h) > 0.5) {
      throw new Error(`${name} frame ${f.id}: 修仙卡位移动了 ${JSON.stringify(host)} vs ${JSON.stringify(ref)}`);
    }
    readings.push({ frame: f.id, host });
    await page.screenshot({ path: path.join(dir, `frame-${f.id}.png`), clip });
  }
  await ctx.close();
  return { name, width, dpr, clip, readings };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const sets = [
    await shootSet(browser, { name: 'zoom', width: 480, dpr: 2, frames: FRAMES }),
    await shootSet(browser, { name: 'actual', width: 480, dpr: 1, frames: FRAMES }),
    await shootSet(browser, { name: 'narrow', width: 375, dpr: 1, frames: FRAMES.filter((f) => f.id === '3') }),
  ];

  // ①→⑤ 横排连看:实际尺寸那组,dpr 1、图按原像素排,不缩放;帧名在图下,不压画面。
  const actual = sets[1];
  const { width: cw, height: ch } = actual.clip;
  const imgs = FRAMES.map((f) => ({
    ...f,
    src: 'data:image/png;base64,' + fs.readFileSync(path.join(OUT, 'actual', `frame-${f.id}.png`)).toString('base64'),
  }));
  const ctx = await browser.newContext({ deviceScaleFactor: 1, colorScheme: 'dark' });
  const strip = await ctx.newPage();
  await strip.setViewportSize({ width: (cw + 12) * imgs.length + 12, height: ch + 48 });
  await strip.setContent(`<!doctype html><body style="margin:0;background:#0b0a0e;font:13px system-ui,'PingFang SC',sans-serif;color:#cfc9d8">
    <div style="display:flex;gap:12px;padding:12px">${imgs
      .map(
        (i) => `<figure style="margin:0"><img src="${i.src}" style="display:block;width:${cw}px;height:${ch}px">
        <figcaption style="padding-top:6px;text-align:center">${i.label}</figcaption></figure>`,
      )
      .join('')}</div></body>`);
  await strip.screenshot({ path: path.join(OUT, 'actual', 'strip-1-to-5.png'), fullPage: true });

  fs.writeFileSync(path.join(OUT, 'readings.json'), JSON.stringify(sets, null, 2));
  for (const s of sets) console.log(s.name, JSON.stringify(s.clip), JSON.stringify(s.readings[0].host));
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
