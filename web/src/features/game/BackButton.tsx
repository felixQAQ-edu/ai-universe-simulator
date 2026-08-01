import type { PointerEvent } from 'react';
import { useSkin } from './theme/contract';
import styles from './game.module.css';

// 返回键(线 C 导航层)。**通用组件 + 皮肤 class 令牌**,不是第四个组件槽 ——
// 四个世界的差异 100% 在 CSS,DOM 都是同一个按钮(理由见 `WorldSkin.backClass` 头注释)。
//
// 行为(Felix 2026-08-01 三条产品裁定):
//   · 退出不弃局 —— 只调 `reset()` 回选择屏,存档指针原封不动;
//   · 返回后选择屏自动出「继续上局」—— ADR-015 Slice 2 的既有机制,零新机制;
//   · 不做二次确认 —— 既然无损,给无害动作加确认框是制造摩擦。
//
// **生成中照样可点**(不禁用、不中断):一回合 15s+,那恰恰是玩家最想退出的时刻,
// 禁用等于导航消失。前端关流,服务端那回合跑完但因客户端断开不写盘 —— 盘上停在上一个
// 完整回合,续局照常可用,即 ADR-015 已立字的「崩溃回滚一回合是特性非 bug」在新路径上的延伸。
//
// 导航不可缺席:皮肤没配 `backClass` 也照常渲染(只是长得普通)。

export function BackButton({ onBack }: { onBack: () => void }) {
  const skin = useSkin()?.skin;

  // 按压时长每次求值 —— 只为「时间不可信」的世界开的口子(缺省不写,其余世界行为逐字不变)。
  const onPointerDown = (e: PointerEvent<HTMLButtonElement>) => {
    const d = skin?.backPressDurMs;
    if (d === undefined) return;
    const ms = typeof d === 'function' ? d() : d;
    e.currentTarget.style.setProperty('--press-dur', `${(ms / 1000).toFixed(3)}s`);
  };

  return (
    <button
      type="button"
      // 空段一律滤掉:class 属性里不留多余空格(§4.5.1 对拍口径)。
      className={[styles.back, skin?.backClass].filter(Boolean).join(' ')}
      onPointerDown={onPointerDown}
      onClick={onBack}
      // 图标 + 「返回」二字都在按钮里,读屏念得出;aria-label 说明去哪儿,免得只念「返回」。
      aria-label="返回世界选择"
    >
      <span className={styles.backGlyph} aria-hidden="true">
        ←
      </span>
      <span className={styles.backText}>返回</span>
    </button>
  );
}
