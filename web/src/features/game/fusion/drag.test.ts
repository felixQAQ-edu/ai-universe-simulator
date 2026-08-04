import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TUNABLES,
  autoScrollVelocity,
  classifyMove,
  findTarget,
  fusionKey,
  isArmed,
  isFusionAllowed,
  overlapRatio,
  type Rect,
} from './drag';
import type { FusionCombo } from '../../../api';

// 手势判据的钉子(线 B1 原型的用例随判据一并搬入生产)。体感由真机回答,
// 但「代码到底在判什么」不该靠真机推断。

const T = DEFAULT_TUNABLES;

describe('classifyMove · 滚动与拖拽的分岔(这套手势成败所系)', () => {
  it('纵向甩动 = 滚动意图,取消长按', () => {
    expect(classifyMove(0, 30, T)).toBe('scroll');
    expect(classifyMove(3, -24, T)).toBe('scroll');
  });

  it('横向位移过阈 = 立刻抓起(纵向滚动被 pan-y 留给浏览器,横向留给我们)', () => {
    expect(classifyMove(14, 2, T)).toBe('drag');
    expect(classifyMove(-20, 6, T)).toBe('drag');
  });

  it('横向虽过阈但纵向更大 → 仍判滚动(斜着甩主要还是想滚)', () => {
    expect(classifyMove(14, 40, T)).toBe('scroll');
  });

  it('容差内的手指抖动:不作判断,继续等长按计时', () => {
    expect(classifyMove(2, 3, T)).toBe('none');
    expect(classifyMove(0, 0, T)).toBe('none');
  });

  it('B1 真机验过的阈值原样沿用(改动须回真机重验,桌面测不准)', () => {
    expect(T).toEqual({
      longPressMs: 180,
      moveTolerancePx: 10,
      dirLockPx: 12,
      overlapRatio: 0.62,
      dwellMs: 300,
      edgePx: 90,
      maxScrollPxPerSec: 900,
    });
  });
});

const card = (top: number): Rect => ({ left: 16, top, width: 343, height: 92 });

describe('overlapRatio / findTarget', () => {
  it('完全重合 = 1,完全分离 = 0', () => {
    expect(overlapRatio(card(0), card(0))).toBe(1);
    expect(overlapRatio(card(0), card(400))).toBe(0);
  });

  it('半高错开 ≈ 0.5', () => {
    expect(overlapRatio(card(0), card(46))).toBeCloseTo(0.5, 2);
  });

  it('命中以拖动卡中心为准:中心未落在目标上即不命中(哪怕边缘擦到)', () => {
    const cands = [{ id: 'b', rect: card(100) }];
    expect(findTarget(card(60), cands)).toBe('b'); // 中心 y=106,落在 100–192 内
    expect(findTarget(card(0), cands)).toBeNull(); // 有交叠但中心在外
  });

  it('无候选 / 中心悬空 → null(不硬凑一个目标)', () => {
    expect(findTarget(card(0), [])).toBeNull();
    expect(findTarget(card(600), [{ id: 'b', rect: card(100) }])).toBeNull();
  });
});

describe('isArmed · 重叠或停留,二者取一', () => {
  it('重叠够深即可提交(不必等停留)', () => {
    expect(isArmed(0.7, 0, T)).toBe(true);
  });
  it('重叠不够但停够久也可提交', () => {
    expect(isArmed(0.2, 400, T)).toBe(true);
  });
  it('两者都不够 → 不可提交', () => {
    expect(isArmed(0.2, 100, T)).toBe(false);
  });
});

describe('autoScrollVelocity · 边缘自动滚动', () => {
  const H = 800;
  it('屏幕中部不滚', () => {
    expect(autoScrollVelocity(400, H, T)).toBe(0);
  });
  it('贴上边缘向上滚,越贴越快', () => {
    const near = autoScrollVelocity(10, H, T);
    const far = autoScrollVelocity(80, H, T);
    expect(near).toBeLessThan(0);
    expect(far).toBeLessThan(0);
    expect(Math.abs(near)).toBeGreaterThan(Math.abs(far));
  });
  it('贴下边缘向下滚', () => {
    expect(autoScrollVelocity(H - 5, H, T)).toBeGreaterThan(0);
  });
  it('超出屏幕(手指拖到边框外)不超过最大速度', () => {
    expect(autoScrollVelocity(-50, H, T)).toBe(-T.maxScrollPxPerSec);
    expect(autoScrollVelocity(H + 50, H, T)).toBe(T.maxScrollPxPerSec);
  });
});

// ── 合法性(生产口径:真相源在后端 fusions 投影)────────────────────────────
// 与原型口径「任意两个已激活世界都可揉」**刻意不同** —— 原型要同时试到吸附与排斥两条路径,
// 生产要的是「拖出来的组合后端一定收」(ADR-019)。

const COMBOS: FusionCombo[] = [
  { host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' },
  { host: 'rules_creepy', foreign: 'apocalypse', key: 'rules_creepy×apocalypse' },
];

describe('isFusionAllowed · 合法组合只认后端投影', () => {
  it('已登记组合 = 合法', () => {
    expect(isFusionAllowed(COMBOS, 'cultivation', 'rules_creepy')).toBe(true);
    expect(isFusionAllowed(COMBOS, 'rules_creepy', 'apocalypse')).toBe(true);
  });

  it('**方向敏感**:反向未登记即非法(后端 FUSION_COMBOS 就是方向敏感的)', () => {
    expect(isFusionAllowed(COMBOS, 'rules_creepy', 'cultivation')).toBe(false);
    expect(isFusionAllowed(COMBOS, 'apocalypse', 'rules_creepy')).toBe(false);
  });

  it('两个已激活但未登记的世界 = 非法(否则拖出来会吃后端 400)', () => {
    expect(isFusionAllowed(COMBOS, 'cthulhu', 'cultivation')).toBe(false);
  });

  it('自己揉自己 = 非法', () => {
    expect(isFusionAllowed(COMBOS, 'cthulhu', 'cthulhu')).toBe(false);
  });

  it('空组合表(老后端 / 加载失败)→ 一律非法,选择屏照常可用(安全降级)', () => {
    expect(isFusionAllowed([], 'cultivation', 'rules_creepy')).toBe(false);
  });
});

describe('fusionKey', () => {
  it('与后端 registry / 封面 / 卡文案同键(host×foreign,方向敏感)', () => {
    expect(fusionKey('cultivation', 'rules_creepy')).toBe('cultivation×rules_creepy');
    expect(fusionKey('rules_creepy', 'cultivation')).not.toBe(fusionKey('cultivation', 'rules_creepy'));
  });
});
