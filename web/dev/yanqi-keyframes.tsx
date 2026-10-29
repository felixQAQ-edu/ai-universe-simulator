import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '../src/index.css';
import { YanqiKeyframes } from './YanqiKeyframes';
import { YanqiMotion } from './YanqiMotion';

// 《验讫》融合 · 五张静态关键帧(刀 2a,dev-only)。
//
// **不进生产 build**:本文件在 `web/dev/`,既不在 Vite 的构建入口(只有根 index.html),
// 也不在 tsconfig.app 的 include(只有 src/)。只能经 `npm run dev` 打开:
//   http://localhost:5173/dev/yanqi-keyframes.html?frame=1..5[&t=2000]   (静态关键帧)
//   http://localhost:5173/dev/yanqi-keyframes.html?play=1              (短运动稿,见 YanqiMotion.tsx)
//
// **卡片全是真的**:直接渲染生产导出的 ArchetypeCard / FusionCard + game.module.css,
// 选择屏骨架(.screen / .selectHeader / .cardList)照抄 ArchetypeSelect 的 JSX。
// 不走 ArchetypeSelect 本身,是因为它挂载即拉目录(store → api),静态稿不该依赖后端。
// 新增的只有 ③④ 的融合面与焦印(keyframes.module.css + Seal.tsx)。
//
// 卡上的 CSS 动画(云气、蠕动、标题轮换、渗出入场)在 t 毫秒处**统一暂停**,
// 让每次打开、每次截图都是同一帧。缺省 t=2000:渗出入场(0.9s)已走完,
// 融合卡标题轮换(5.2s 一周)处在「验讫」驻留段(16%–55%)。

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {new URLSearchParams(location.search).get('play') === '1' ? <YanqiMotion /> : <YanqiKeyframes />}
  </StrictMode>,
);
