// 《验讫》静态关键帧截图(刀 2a,dev-only)。
//
// 前提:`npm run dev` 已在 :5173 跑着。用法(Playwright 不进 web 依赖,用环境里全局装的那份):
//   NODE_PATH=$(npm root -g) node dev/shoot-yanqi-keyframes.cjs [输出目录]
//
// 480 宽手机视口、dpr 2、深色。每帧一张整屏 + 一张卡片区裁切;
// 裁切框只量一次(取自 ① 的卡位),全部帧共用同一位置同一尺寸,
// 并逐帧断言卡位没动(动了就报错,不出图)。最后拼一张横排对照。
const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.KF_BASE ?? 'http://localhost:5173/dev/yanqi-keyframes.html';
const OUT = path.resolve(process.argv[2] ?? 'dev/yanqi-keyframes-shots');
const VIEWPORT = { width: 480, height: 1040 };
const FRAMES = [
  { id: '1', q: 'frame=1', label: '① 交接' },
  { id: '2', q: 'frame=2', label: '② 停顿·靠拢' },
  { id: '3A', q: 'frame=3&v=A', label: '③ 标志 · A' },
  { id: '3B', q: 'frame=3&v=B', label: '③ 标志 · B' },
  { id: '4', q: 'frame=4', label: '④ 轮廓退去' },
  { id: '5', q: 'frame=5', label: '⑤ 揭晓' },
];
const PAD = 12;

async function slots(page) {
  return page.evaluate(() => {
    // 布局位置(offset*,不受 transform 影响):①② 的被拖者带 transform,量的是它的卡位。
    const r = (sel) => {
      let el = document.querySelector(sel);
      const w = el.offsetWidth;
      const h = el.offsetHeight;
      let x = 0;
      let y = 0;
      for (; el; el = el.offsetParent) {
        x += el.offsetLeft;
        y += el.offsetTop;
      }
      return { x, y, w, h };
    };
    return { foreign: r('[data-kf-slot="foreign"]'), host: r('[data-kf-slot="host"]') };
  });
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: VIEWPORT, deviceScaleFactor: 2, colorScheme: 'dark' });
  const page = await ctx.newPage();

  let clip = null;
  let ref = null;
  const readings = [];
  for (const f of FRAMES) {
    await page.goto(`${BASE}?${f.q}`);
    await page.waitForSelector('html[data-kf-ready="1"]');
    await page.evaluate(() => document.fonts.ready);
    const s = await slots(page);
    if (!ref) {
      ref = s;
      const host = s.host;
      const h = host.h;
      // 上沿 = 修仙卡位顶上留一截(克苏鲁卡位在 ①–⑤ 全程是空的,不必整个框进来);
      // 下沿 = 修仙卡位底 + ① 压出去的那截(0.56H + 旋转余量)。
      const top = host.y - 24 - PAD;
      const bottom = host.y + host.h + 0.56 * h + 24 + PAD;
      clip = { x: 0, y: Math.round(top), width: VIEWPORT.width, height: Math.round(bottom - top) };
    } else if (Math.abs(s.host.y - ref.host.y) > 0.5 || Math.abs(s.host.h - ref.host.h) > 0.5) {
      throw new Error(`frame ${f.id}: 修仙卡位移动了 ${JSON.stringify(s.host)} vs ${JSON.stringify(ref.host)}`);
    }
    readings.push({ frame: f.id, host: s.host });
    await page.screenshot({ path: path.join(OUT, `frame-${f.id}-full.png`) });
    await page.screenshot({ path: path.join(OUT, `frame-${f.id}-crop.png`), clip });
  }

  // 横排对照:六张裁切图 + 帧名(标签在图外,不压画面)。
  const imgs = FRAMES.map((f) => ({
    ...f,
    src: 'data:image/png;base64,' + fs.readFileSync(path.join(OUT, `frame-${f.id}-crop.png`)).toString('base64'),
  }));
  const strip = await ctx.newPage();
  await strip.setViewportSize({ width: (clip.width + 16) * imgs.length + 16, height: clip.height + 64 });
  await strip.setContent(`<!doctype html><body style="margin:0;background:#0b0a0e;font:14px system-ui,'PingFang SC',sans-serif;color:#cfc9d8">
    <div style="display:flex;gap:16px;padding:16px">${imgs
      .map(
        (i) => `<figure style="margin:0"><img src="${i.src}" style="display:block;width:${clip.width}px;height:${clip.height}px">
        <figcaption style="padding-top:8px;text-align:center">${i.label}</figcaption></figure>`,
      )
      .join('')}</div></body>`);
  await strip.screenshot({ path: path.join(OUT, 'strip.png'), fullPage: true });

  fs.writeFileSync(path.join(OUT, 'readings.json'), JSON.stringify({ viewport: VIEWPORT, dpr: 2, clip, readings }, null, 2));
  console.log(JSON.stringify({ clip, host: ref.host, foreignSlot: ref.foreign }));
  await browser.close();
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
