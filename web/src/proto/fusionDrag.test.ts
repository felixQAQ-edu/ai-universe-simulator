import { describe, expect, it } from 'vitest';
import {
  DEFAULT_TUNABLES,
  PROTO_WORLDS,
  autoScrollVelocity,
  baseOf,
  classifyMove,
  comboAllowed,
  commitLine,
  findTarget,
  isArmed,
  overlapRatio,
  protoWorlds,
  type Rect,
} from './fusionDrag';

// 手势判据的钉子。原型的体感由真机回答,但「代码到底在判什么」不该靠真机推断。

const T = DEFAULT_TUNABLES;

describe('classifyMove · 滚动与拖拽的分岔(本刀成败所系)', () => {
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

  it('阈值可调:容差调大后,同一次抖动不再被判为滚动', () => {
    expect(classifyMove(0, 14, T)).toBe('scroll');
    expect(classifyMove(0, 14, { ...T, moveTolerancePx: 20 })).toBe('none');
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
  it('边缘带宽为 0 → 关掉自动滚动(可在真机上单独排除这条变量)', () => {
    expect(autoScrollVelocity(0, H, { ...T, edgePx: 0 })).toBe(0);
  });
});

describe('组合合法性(原型口径:任意两个已激活世界都可揉,方向决定 host)', () => {
  it('两个已激活世界 = 合法,且方向两边都合法(host 不同 = 不同的世界)', () => {
    expect(comboAllowed('cultivation', 'rules_creepy')).toBe(true);
    expect(comboAllowed('rules_creepy', 'cultivation')).toBe(true);
  });
  it('自己揉自己 = 非法', () => {
    expect(comboAllowed('cthulhu', 'cthulhu')).toBe(false);
  });
  it('未激活世界作目标 = 非法(真机上用它试「排斥 + 回弹」这条路径)', () => {
    expect(comboAllowed('cthulhu', 'life_sim')).toBe(false);
    expect(comboAllowed('cyberpunk', 'cthulhu')).toBe(false);
  });
  it('四个已激活世界共 6 对组合(与设计前提「四套碎解材质覆盖六对」一致)', () => {
    const active = PROTO_WORLDS.filter((w) => w.active).map((w) => w.id);
    const pairs = active.flatMap((a) => active.filter((b) => b !== a).map((b) => [a, b]));
    expect(pairs.filter(([a, b]) => comboAllowed(a, b))).toHaveLength(12); // 12 个有序 = 6 对无序
    expect(new Set(pairs.map(([a, b]) => [a, b].sort().join('×'))).size).toBe(6);
  });
});

describe('加长列表(副本卡)', () => {
  it('6 张 / 12 张两档,副本 id 带后缀但基名不变', () => {
    expect(protoWorlds(false)).toHaveLength(6);
    const long = protoWorlds(true);
    expect(long).toHaveLength(12);
    expect(long.map((w) => baseOf(w.id)).slice(6)).toEqual(PROTO_WORLDS.map((w) => w.id));
  });

  it('副本参与组合判定时看基名:与本体互揉 = 非法,与别的世界 = 合法', () => {
    expect(comboAllowed('cthulhu~2', 'cthulhu')).toBe(false);
    expect(comboAllowed('cthulhu~2', 'cultivation')).toBe(true);
    expect(comboAllowed('cthulhu~2', 'life_sim~2')).toBe(false);
  });

  it('提交文案落回基名(副本是列表长度的道具,不是新世界)', () => {
    expect(commitLine('cthulhu~2', 'cultivation~2')).toBe(
      '将「克苏鲁」揉入「修仙」 · host=cultivation foreign=cthulhu',
    );
  });
});

describe('commitLine · 提交只打印一行(不生成世界)', () => {
  it('被拖者揉入承接者,方向写死在文案里', () => {
    expect(commitLine('rules_creepy', 'cultivation')).toBe(
      '将「规则怪谈」揉入「修仙」 · host=cultivation foreign=rules_creepy',
    );
  });
});
