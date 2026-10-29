// 《验讫》短运动稿录制(刀 2b,dev-only)。
//
// 前提:`npm run dev` 已在 :5173 跑着;系统有 ffmpeg。用法:
//   NODE_PATH=$(npm root -g) node dev/record-yanqi-motion.cjs [输出目录]
//
// **逐帧定格录制**,不是实时屏幕录像:页面以 rec=1 打开,时间线与卡上 CSS 动画全部暂停,
// 每一帧调用 window.__kf.seek(ms) 定格后截图,再用 ffmpeg 按 30fps 编码。
// 这样「原速」是逐帧精确的原速(无掉帧、无录屏抖动);实时流畅度另由 perf-yanqi-motion.cjs 在 CPU 降速下测。
//
// 四段:原速光带开 / 原速光带关 / ¼ 速(光带开)/ reduced-motion(prefers-reduced-motion: reduce)。
// 每段:mp4 + gif + 一张逐帧图(每格 100ms 视频时间,只截修仙卡位一带)+ 标题可见性日志。
const { chromium } = require('playwright');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const BASE = process.env.KF_BASE ?? 'http://localhost:5173/dev/yanqi-keyframes.html';
const OUT = path.resolve(process.argv[2] ?? 'dev/yanqi-motion-shots');
const FPS = 30;
const VIEW = { width: 480, height: 1040 };
/** 录制区:整张卡列(其余卡的淡出 / 恢复也要看得见);不含页头与底部空白。CSS px。 */
const REC = { x: 0, y: 260, width: 480, height: 600 };

const SEGMENTS = [
  { id: 'full-streak-on', q: 'streak=1', speed: 1, reduced: false, label: '原速 · 光带开' },
  { id: 'full-streak-off', q: 'streak=0', speed: 1, reduced: false, label: '原速 · 光带关' },
  { id: 'quarter', q: 'streak=1&speed=0.25', speed: 0.25, reduced: false, label: '¼ 速 · 光带开' },
  { id: 'reduced', q: 'streak=1', speed: 1, reduced: true, label: 'reduced-motion' },
];

async function recordSegment(browser, seg) {
  const dir = path.join(OUT, seg.id);
  const frames = path.join(dir, 'frames');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(frames, { recursive: true });
  const ctx = await browser.newContext({
    viewport: VIEW,
    deviceScaleFactor: 2,
    colorScheme: 'dark',
    reducedMotion: seg.reduced ? 'reduce' : 'no-preference',
  });
  const page = await ctx.newPage();
  await page.goto(`${BASE}?play=1&rec=1&${seg.q}`);
  await page.waitForSelector('html[data-kf-ready="1"]');
  await page.evaluate(() => document.fonts.ready);
  const animMs = await page.evaluate(() => window.__kf.duration);
  const videoMs = animMs / seg.speed;
  const n = Math.ceil((videoMs / 1000) * FPS) + 1;

  // 修仙卡位(逐帧图只截这一带):与静态关键帧同一算法的裁切框,换算到录制区坐标。
  const host = await page.evaluate(() => {
    const r = document.querySelector('[data-kf-slot="host"]').getBoundingClientRect();
    return { y: r.y, h: r.height };
  });
  const cell = { y: Math.round(host.y - 36 - REC.y), h: Math.round(host.h * 1.56 + 72) };

  const titleLog = [];
  for (let i = 0; i < n; i++) {
    const videoT = (i * 1000) / FPS;
    const t = Math.min(videoT * seg.speed, animMs);
    await page.evaluate((ms) => window.__kf.seek(ms), t);
    titleLog.push(
      await page.evaluate((ms) => {
        const reveal = document.querySelector('[data-kf="reveal"]');
        const fused = document.querySelector('[data-kf="fused"]');
        const text = Number(getComputedStyle(reveal).getPropertyValue('--kf-text') || 0);
        const fusedOp = Number(getComputedStyle(fused).opacity);
        const spans = [...reveal.querySelectorAll('h2 > span')].map((s) => Number(getComputedStyle(s).opacity));
        // 文字实际可见度 = 文字门控 × 融合层不透明度 × 该层标题自身(轮换)。
        const vis = spans.map((o) => +(o * text * fusedOp).toFixed(3));
        return { ms: Math.round(ms), text: +text.toFixed(3), A: vis[0], B: vis[1], C: vis[2] };
      }, t),
    );
    await page.screenshot({ path: path.join(frames, `f${String(i).padStart(4, '0')}.png`), clip: REC });
  }
  await ctx.close();

  // mp4(H.264 / yuv420p,30fps,输出 480 宽)与 gif(15fps,调色板两遍)。
  const mp4 = path.join(dir, `${seg.id}.mp4`);
  const gif = path.join(dir, `${seg.id}.gif`);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', path.join(frames, 'f%04d.png'),
    '-vf', `scale=${REC.width}:-2:flags=lanczos`, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '16', '-movflags', '+faststart', mp4]);
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-framerate', String(FPS), '-i', path.join(frames, 'f%04d.png'),
    '-vf', `fps=15,scale=${REC.width}:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=256:stats_mode=full[p];[b][p]paletteuse=dither=sierra2_4a`, gif]);

  // 逐帧图:每格 100ms 视频时间,只截修仙卡位一带,半尺寸;格下标视频时间与动画时间。
  const step = Math.round(FPS / 10);
  const picks = [];
  for (let i = 0; i < n; i += step) picks.push(i);
  const sheet = path.join(dir, `${seg.id}-contact-100ms.png`);
  const cols = 8;
  const tiles = picks.map((i) => {
    const vt = Math.round((i * 1000) / FPS);
    const at = Math.round(Math.min(vt * seg.speed, animMs));
    const label = seg.speed === 1 ? `${vt} ms` : `${vt} ms (anim ${at})`;
    return ['(', path.join(frames, `f${String(i).padStart(4, '0')}.png`), '-crop', `${REC.width * 2}x${cell.h * 2}+0+${cell.y * 2}`, '+repage',
      '-resize', '50%', '-gravity', 'south', '-background', '#0b0a0e', '-splice', '0x22', '-fill', '#cfc9d8',
      '-pointsize', '15', '-annotate', '+0+3', label, ')'];
  });
  // ImageMagick 一次拼不了几百张参数 → 先逐行拼,再纵向拼。
  const rows = [];
  for (let r = 0; r * cols < tiles.length; r++) {
    const rowFile = path.join(dir, `row${r}.png`);
    execFileSync('convert', [...tiles.slice(r * cols, (r + 1) * cols).flat(), '-background', '#0b0a0e', '-splice', '6x6', '+append', rowFile]);
    rows.push(rowFile);
  }
  execFileSync('convert', [...rows, '-background', '#0b0a0e', '-append', '-bordercolor', '#0b0a0e', '-border', '6', sheet]);
  rows.forEach((f) => fs.rmSync(f));

  fs.writeFileSync(path.join(dir, 'title-log.json'), JSON.stringify(titleLog, null, 1));
  fs.rmSync(frames, { recursive: true, force: true });
  return { id: seg.id, label: seg.label, animMs, videoMs: Math.round(videoMs), frames: n, titleLog };
}

/** 标题检查:文字可见度第一次 > 0.05 的那一帧起,A(修仙)/B(克苏鲁)出现之前,C(验讫)必须已经清楚(≥ 0.5)。 */
function titleVerdict(log) {
  const firstVisible = log.find((r) => Math.max(r.A, r.B, r.C) > 0.05);
  if (!firstVisible) return { ok: false, why: '整段都没有标题出现' };
  const firstClear = log.find((r) => Math.max(r.A, r.B, r.C) >= 0.5);
  const which = firstClear ? (['A', 'B', 'C'].find((k) => firstClear[k] >= 0.5)) : null;
  const leakAB = log.filter((r) => r.ms <= (firstClear?.ms ?? Infinity) && (r.A > 0.05 || r.B > 0.05));
  return {
    ok: which === 'C' && leakAB.length === 0,
    firstVisibleMs: firstVisible.ms,
    firstClearMs: firstClear?.ms,
    firstClearLayer: which === 'C' ? '验讫' : which === 'A' ? '修仙' : which === 'B' ? '克苏鲁' : null,
    abBeforeClear: leakAB.length,
  };
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const summary = [];
  for (const seg of SEGMENTS) {
    const r = await recordSegment(browser, seg);
    const verdict = titleVerdict(r.titleLog);
    summary.push({ id: r.id, label: r.label, animMs: r.animMs, videoMs: r.videoMs, frames: r.frames, title: verdict });
    console.log(r.id, JSON.stringify(verdict));
  }
  await browser.close();
  fs.writeFileSync(path.join(OUT, 'summary.json'), JSON.stringify(summary, null, 2));
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
