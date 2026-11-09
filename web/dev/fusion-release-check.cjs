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
//   ④ 标志路径另判「收进卡位」段(刀 2c-2 返工):760–1300ms(从融合面挂上、即时间线 0 点起算)逐帧(CDP screencast,真实帧时间戳)取
//      宿主卡位**之外**、被拖卡曾经占过的那条带子(卡位上 / 下沿外,左右各让 6px),与 1300ms 那一帧(被拖卡早已退净)
//      逐行比像素均差;任何一帧任何一行超过 TUCK_DIFF 即判失败 —— 没字的深色块、灰条、第二道边框都会让这条带子变色。
//      判据只看像素,不读实现(不认遮罩 / 变量),所以修法换了也照样能判。
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
/** 收进卡位段:卡位外带子与参照帧的逐行像素均差上限(0–255)。暗卡残块对浅色底约差上百。 */
const TUCK_WINDOW = [760, 1300];
const TUCK_DIFF = 6;

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
    window.__emblemAt = null; // 融合面挂上那一刻(= 揉合时间线的 0 点;页面时钟)
    new MutationObserver(() => {
      if (!window.__emblem && document.querySelector('[data-fusion-emblem]')) {
        window.__emblem = true;
        window.__emblemAt = performance.now();
      }
    }).observe(document.body, { childList: true, subtree: true });
    const loop = () => {
      const r = el.getBoundingClientRect();
      window.__frames.push({ t: performance.now(), top: r.top, bottom: r.bottom, left: r.left, op: +getComputedStyle(el).opacity });
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
  const shots = [];
  if (emblem) {
    cdp.on('Page.screencastFrame', (f) => {
      shots.push({ wall: f.metadata.timestamp * 1000, data: f.data });
      cdp.send('Page.screencastFrameAck', { sessionId: f.sessionId }).catch(() => {});
    });
    await cdp.send('Page.startScreencast', { format: 'png', everyNthFrame: 1 });
  }
  const tUp = await page.evaluate(() => performance.now());
  const wallUp = Date.now();
  await page.mouse.up();
  let burnLayouts = null;
  if (emblem) {
    await page.waitForTimeout(1080);
    const l0 = await layouts();
    await page.waitForTimeout(1020);
    burnLayouts = (await layouts()) - l0;
    await cdp.send('Page.stopScreencast');
  } else {
    await page.waitForTimeout(450);
  }
  const { frames, merged, sawEmblem, slot, t0, origin } = await page.evaluate((hostName) => {
    const h = [...document.querySelectorAll('button')].find((b) => b.querySelector('h2')?.textContent === hostName);
    const r = h.getBoundingClientRect();
    return {
      frames: window.__frames,
      merged: !!document.querySelector('[data-fusion-key]'),
      sawEmblem: window.__emblem,
      t0: window.__emblemAt,
      origin: performance.timeOrigin,
      slot: { top: r.top, bottom: r.bottom, left: r.left, right: r.right },
    };
  }, NAMES[c.to]);
  // 时间窗按揉合时间线的 0 点(融合面挂上那一刻)对齐,不按松手:松手到时间线起步之间隔着 React 提交与一次 tick,
  // 实测约 40–50ms;按松手对齐会把停顿段末尾当成收进卡位段。
  const tuck = emblem ? await tuckResidue(browser, shots, frames, slot, t0 ?? tUp, origin + (t0 ?? tUp)) : null;
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
    (emblem ? sawEmblem && maxStep <= MAX_STEP && burnLayouts <= MAX_LAYOUTS && tuck.ok : !sawEmblem && drift <= 0.5);
  const fmt = (f) => `${(f.t - tUp).toFixed(0).padStart(5)}ms top=${f.top.toFixed(1)} left=${f.left.toFixed(1)} op=${f.op.toFixed(2)}`;
  console.log(
    `${ok ? 'PASS' : 'FAIL'}  ${c.vw} ${NAMES[c.from]}→${NAMES[c.to]}${c.reduced ? ' reduced' : ''} [${c.g}]` +
      `  [${emblem ? '标志' : '交叉淡化'}] merged=${merged} 融合面=${sawEmblem} 松手跳=${firstJump.toFixed(1)}px(松手前单帧最大 ${maxPreStep.toFixed(1)})` +
      (emblem
        ? ` 淡净前单帧最大=${maxStep.toFixed(1)}px 烧边段布局=${burnLayouts} 卡位外残块=${tuck.summary}`
        : ` 淡出期漂移=${drift.toFixed(2)}px`),
  );
  for (const f of [...pre.slice(-3), ...fading.slice(0, 3)]) console.log('      ' + fmt(f));
  return ok;
}

/**
 * 收进卡位段的卡位外残块(判据 ④)。带子 = 被拖卡在 TUCK_WINDOW 里伸出卡位上 / 下沿的范围(按 rAF 量的屏上矩形取并集),
 * 只算它还看得见(opacity > 0.02)的帧;内缩 2px 避开卡位自己的边线;参照 = 时间 ≥ TUCK_WINDOW[1] 的第一帧。在一个空白页的 canvas 里解码比对(不依赖外部工具)。
 */
async function tuckResidue(browser, shots, frames, slot, tUp, wallUp) {
  // tUp / wallUp:时间线 0 点(页面时钟 / 墙钟 ms);screencast 时间戳是墙钟秒。
  const [w0, w1] = TUCK_WINDOW;
  const inWin = frames.filter((f) => f.t - tUp >= w0 && f.t - tUp <= w1 && f.op > 0.02); // 淡净后它回到自己卡位(看不见),不算
  const below = Math.max(slot.bottom, ...inWin.map((f) => f.bottom));
  const above = Math.min(slot.top, ...inWin.map((f) => f.top));
  const bands = [];
  if (below - slot.bottom > 6) bands.push([Math.ceil(slot.bottom) + 2, Math.floor(below) - 2]);
  if (slot.top - above > 6) bands.push([Math.ceil(above) + 2, Math.floor(slot.top) - 2]);
  const rel = shots.map((s) => ({ t: s.wall - wallUp, data: s.data }));
  const ref = rel.find((s) => s.t >= w1);
  const test = rel.filter((s) => s.t >= w0 && s.t < w1);
  if (!bands.length) return { ok: true, summary: '被拖卡未伸出卡位' };
  if (!ref || !test.length) return { ok: false, summary: `帧不足(参照=${!!ref} 窗内=${test.length})` };
  const page = await browser.newPage();
  const worst = await page.evaluate(
    async ({ ref, test, bands, x0, x1 }) => {
      const load = (d) => new Promise((ok) => { const i = new Image(); i.onload = () => ok(i); i.src = 'data:image/png;base64,' + d; });
      const pixels = async (d) => {
        const img = await load(d);
        const cv = document.createElement('canvas');
        cv.width = img.width; cv.height = img.height;
        const g = cv.getContext('2d');
        g.drawImage(img, 0, 0);
        return g.getImageData(0, 0, img.width, img.height);
      };
      const R = await pixels(ref.data);
      let out = { diff: 0, t: null, y: null, series: {} };
      for (const s of test) {
        const P = await pixels(s.data);
        for (const [y0, y1] of bands) {
          for (let y = y0; y <= y1; y++) {
            let sum = 0, n = 0;
            for (let x = x0; x <= x1; x++) {
              const k = (y * P.width + x) * 4;
              sum += Math.abs(P.data[k] - R.data[k]) + Math.abs(P.data[k + 1] - R.data[k + 1]) + Math.abs(P.data[k + 2] - R.data[k + 2]);
              n += 3;
            }
            const d = sum / n;
            if (d > out.diff) out = { ...out, diff: d, t: s.t, y };
            if (d > (out.series[s.t]?.d ?? -1)) out.series[s.t] = { d: +d.toFixed(1), y };
          }
        }
      }
      return out;
    },
    { ref, test, bands, x0: Math.ceil(slot.left) + 6, x1: Math.floor(slot.right) - 6 },
  );
  await page.close();
  if (process.env.DEBUG_TUCK) console.log('      逐帧最大行均差:', Object.entries(worst.series).map(([t, v]) => `${Math.round(t)}:${v.d}@${v.y}`).join(' '));
  const ok = worst.diff <= TUCK_DIFF;
  return {
    ok,
    summary:
      `${worst.diff.toFixed(1)}/${TUCK_DIFF}(` +
      (worst.t != null ? `${Math.round(worst.t)}ms, y=${worst.y}, ` : '') +
      `带子 ${bands.map((b) => b.join('–')).join(' + ')}, ${test.length} 帧)`,
  };
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
