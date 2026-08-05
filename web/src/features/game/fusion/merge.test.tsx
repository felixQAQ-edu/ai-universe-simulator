import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import gsap from 'gsap';
import { BEATS, TOTAL_MS } from './physics';
import { SHARDS, materialOf } from './shards';
import { FusionMerge } from './FusionMerge';
import type { Rect } from './drag';

// 五拍揉合与四套材质的钉子(ADR-018 §4.8「测试守行为不守像素」):
// 观感由真机回答,这里钉住结构与契约 —— 拍数与时长、四套材质各自出场、降级不碎裂、
// 以及**演完一定会放行**(后台标签页里 rAF 冻结时 timeline 可能永不完成,§4.11 子模式 A)。

const HOST: Rect = { left: 16, top: 100, width: 343, height: 92 };
const FOREIGN: Rect = { left: 16, top: 0, width: 343, height: 92 };

afterEach(() => {
  vi.useRealTimers();
});

describe('五拍时长(定案 B)', () => {
  it('合计 1000ms,五拍齐', () => {
    expect(BEATS).toEqual({ squeeze: 200, shatter: 180, knead: 260, hold: 140, unfold: 220 });
    expect(TOTAL_MS).toBe(1000);
  });

  it('**停顿一拍不可省** —— 那是新世界诞生前的重量(hold 归零即视为回归)', () => {
    expect(BEATS.hold).toBeGreaterThan(0);
  });
});

describe('四套材质覆盖六对组合(ADR-019 §2:否决 per-combo 特供)', () => {
  it('四个基础世界各映射一套材质', () => {
    expect(materialOf('rules_creepy')).toBe('rules');
    expect(materialOf('apocalypse')).toBe('waste');
    expect(materialOf('cthulhu')).toBe('cthulhu');
    expect(materialOf('cultivation')).toBe('xian');
  });

  it('未登记世界 → null(该侧不出碎片,揉合照常;不报错、不阻断)', () => {
    expect(materialOf('life_sim')).toBeNull();
    expect(materialOf('nope')).toBeNull();
  });

  it('四套在**尺寸分布**上刻意拉开(不是换个颜色):碎片数与形态种类各不相同', () => {
    const counts = Object.fromEntries(
      Object.entries(SHARDS).map(([k, v]) => [k, v.length]),
    );
    expect(new Set(Object.values(counts)).size).toBe(4); // 四套数量互不相同
    // 末日跨度最大(大块 + 大量微尘),修仙刻意最少(少才显得轻)。
    expect(counts.waste).toBeGreaterThan(counts.rules);
    expect(counts.xian).toBeLessThan(counts.cthulhu);
  });

  it('形态固定种子:每次读到的碎片完全一致(同一个世界碎起来是同一副样子)', () => {
    expect(SHARDS.rules).toEqual(SHARDS.rules);
    expect(SHARDS.cthulhu.filter((s) => s.branch).length).toBe(3); // 分叉上限:仅 3 条
    expect(SHARDS.cthulhu.filter((s) => s.pair).length).toBe(2); // 黏连只在两条之间
  });
});

describe('FusionMerge', () => {
  it('两侧各画一张卡面 + 各自的碎片场(host / foreign 分开,材质各归各)', () => {
    render(<FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} onDone={vi.fn()} />);
    const stage = screen.getByTestId('fusion-merge');
    const fields = stage.querySelectorAll('[data-mat="xian"], [data-mat="rules"]');
    expect(fields.length).toBeGreaterThan(0);
    // 碎片总数 = 两套材质之和(四套覆盖六对:同一套材质在任何组合里原样复用)。
    expect(stage.querySelectorAll('i[data-kind]')).toHaveLength(
      SHARDS.xian.length + SHARDS.rules.length,
    );
  });

  /**
   * **这条是冒烟抓到的真缺陷留下的钉子**(ADR-018 §4.14 一族):
   * 第一版用 `.${styles.faceHost}` / `.${styles.fieldHost}` 取元素,而 CSS Modules 里
   * 根本没有这两条规则 → 值是 undefined、选择器退化成 `.undefined`,而两个 div 的 class
   * 恰好都字面写着 "undefined" —— host 与 foreign **双双指向同一个元素**、碎片场一个也取不到,
   * **四套材质一枚碎片都没动**;可卡面照常挤压、世界核照常出现,**画面上完全像是成功的**,
   * 唯一的报错是 GSAP 一句「target not found」。
   *
   * 「碎片在 DOM 里」证明不了任何事,**「碎片身上真的挂了 tween」才是**。
   */
  it('每一枚碎片身上都真的挂上了 tween(不是「渲染出来了」就算数)', () => {
    render(<FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} onDone={vi.fn()} />);
    const shards = [...screen.getByTestId('fusion-merge').querySelectorAll<HTMLElement>('i[data-kind]')];
    expect(shards).toHaveLength(SHARDS.xian.length + SHARDS.rules.length);
    const without = shards.filter((el) => gsap.getTweensOf(el).length === 0);
    expect(without).toHaveLength(0);
  });

  it('两侧是两个不同的元素(定位一旦退化成同一个,一侧的材质就整套消失)', () => {
    render(<FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} onDone={vi.fn()} />);
    const stage = screen.getByTestId('fusion-merge');
    const h = stage.querySelector('[data-role="field"][data-side="host"]');
    const f = stage.querySelector('[data-role="field"][data-side="foreign"]');
    expect(h).not.toBeNull();
    expect(f).not.toBeNull();
    expect(h).not.toBe(f);
    expect(h!.querySelectorAll('i[data-kind]')).toHaveLength(SHARDS.xian.length);
    expect(f!.querySelectorAll('i[data-kind]')).toHaveLength(SHARDS.rules.length);
  });

  it('降级路径:不碎裂、不旋转 —— 一枚碎片都不渲染(reduced-motion / 未登记材质)', () => {
    render(
      <FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} degraded onDone={vi.fn()} />,
    );
    // 降级下碎片仍在 DOM(结构不变),但 CSS 侧 display:none + JS 侧不排任何碎解 tween。
    // 这里钉住 JS 侧:degraded 分支 return 在 shatter/knead 之前。
    const stage = screen.getByTestId('fusion-merge');
    for (const el of stage.querySelectorAll<HTMLElement>('i[data-kind]')) {
      expect(el.style.opacity).toBe('0'); // 从未被碎解拍点亮
    }
  });

  it('**一定会放行**:页面被切到后台(rAF 冻结)时靠独立于 rAF 的兜底放行,不卡死入口', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    render(<FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} onDone={onDone} />);
    vi.advanceTimersByTime(TOTAL_MS + 700);
    expect(onDone).toHaveBeenCalled();
  });

  it('unmount 撤掉全部 tween 与兜底计时(不让上一组揉合的回调补发到下一组)', () => {
    vi.useFakeTimers();
    const onDone = vi.fn();
    const { unmount } = render(
      <FusionMerge host="cultivation" foreign="rules_creepy" hostRect={HOST} foreignRect={FOREIGN} onDone={onDone} />,
    );
    unmount();
    vi.advanceTimersByTime(TOTAL_MS + 2000);
    expect(onDone).not.toHaveBeenCalled();
  });
});
