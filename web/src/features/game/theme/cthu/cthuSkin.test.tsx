import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AvailableAction } from '../../../../types/schema';
import type { AxisView, StatsProps } from '../contract';
import { SkinRuntime } from '../lifecycle';
import { SKINS } from '../skins';
import { CthuActions } from './CthuActions';
import { CthuStats } from './CthuStats';
import { applyAnomaly } from './useAnomaly';
import {
  MAX_STEADY_MS,
  MIN_STEADY_MS,
  advancePhase,
  deferMs,
  meanForSeverity,
  nextDelayMs,
  resumeMs,
} from './anomalySchedule';
import { segmentProse } from './anomalySegment';
import { createPickState, pickAnomaly } from './anomalyPick';

// 刀 4 · 克苏鲁皮肤。测试双层(ADR-018 §5 Q8):**守行为,不守像素** ——
// 不断言 DOM 层数 / 分段数量 / class 排列 / 颜色值。

const skin = SKINS.cthulhu!;

const axis = (over: Partial<AxisView> = {}): AxisView => ({
  key: 'san',
  displayName: '理智',
  value: 55,
  percent: 55,
  bandLabel: '神志清明',
  severity: 'neutral',
  a11yText: '理智 · 55 · 神志清明 · 正常',
  signature: false,
  ...over,
});

const statsProps = (axes: AxisView[]): StatsProps => ({
  axes,
  runtime: new SkinRuntime(),
  rootRef: { current: null },
});

describe('克苏鲁皮肤登记', () => {
  it('四件形态齐全,且**多一个正文槽**(本刀新开的可选槽)', () => {
    expect(skin.Ambient).toBeTruthy();
    expect(skin.Stats).toBeTruthy();
    expect(skin.Actions).toBeTruthy();
    expect(skin.Prose).toBeTruthy();
  });

  it('不占开场:文字异常是侵入,不该有起点', () => {
    expect(skin.hasIntro).toBe(false);
  });

  it('签名轴登记在注册表内(主题层不得自己认 key)', () => {
    expect(skin.signatureAxisKey).toBe('san');
  });

  it('只有克苏鲁配了正文槽 —— 另三个世界仍渲染通用 Prose(DOM 不变是构造保证)', () => {
    expect(SKINS.rules_creepy!.Prose).toBeUndefined();
    expect(SKINS.cultivation!.Prose).toBeUndefined();
    expect(SKINS.apocalypse!.Prose).toBeUndefined();
  });
});

// ★ Felix 点名要的断言:防止以后有人把克苏鲁的时长改回常数而无人察觉。
describe('四世界时间感可区分(§4.3 成对 + §4.12 关系不能丢)', () => {
  const all = [SKINS.rules_creepy!, SKINS.cultivation!, SKINS.apocalypse!, SKINS.cthulhu!];

  it('四条 ease 互不相同', () => {
    const eases = all.map((s) => s.valueRoll.ease);
    expect(new Set(eases).size).toBe(eases.length);
  });

  it('克苏鲁**每次不同**,其余三世界每次相同 —— 这就是「节奏不可信」那条时间感本身', () => {
    const sample = (v: number | (() => number)) => (typeof v === 'function' ? v() : v);
    for (const s of [SKINS.rules_creepy!, SKINS.cultivation!, SKINS.apocalypse!]) {
      const runs = Array.from({ length: 8 }, () => sample(s.valueRoll.durationMs));
      expect(new Set(runs).size).toBe(1); // 常数:八次一样
    }
    const cthu = Array.from({ length: 40 }, () => sample(skin.valueRoll.durationMs));
    expect(new Set(cthu).size).toBeGreaterThan(1); // 函数:重新采样
  });

  it('克苏鲁的迟到也是每次求值(25% 概率额外迟到)', () => {
    const delays = Array.from({ length: 60 }, () => {
      const d = skin.valueRoll.startDelayMs!;
      return typeof d === 'function' ? d() : d;
    });
    expect(delays.some((d) => d === 0)).toBe(true); // 多数不迟到
    expect(delays.some((d) => d > 0)).toBe(true); // 偶尔迟到
  });
});

describe('调度节奏(纯逻辑,随机源注入)', () => {
  it('基线期 55–75s 零异常 —— 先让玩家确信「这里是正常的」', () => {
    const input = { phase: 'baseline' as const, severity: null, sinceScrollMs: 0 };
    expect(nextDelayMs(input, () => 0)).toBe(55_000);
    expect(nextDelayMs(input, () => 0.999)).toBeLessThanOrEqual(75_000);
  });

  it('首次异常之后长沉默 45–90s', () => {
    const input = { phase: 'aftermath' as const, severity: null, sinceScrollMs: 0 };
    expect(nextDelayMs(input, () => 0)).toBe(45_000);
    expect(nextDelayMs(input, () => 0.999)).toBeLessThanOrEqual(90_000);
  });

  it('稳态均值按 severity 分档,且**只认 severity**(不认 key、不认数值)', () => {
    expect(meanForSeverity('danger')).toBeLessThan(meanForSeverity('caution'));
    expect(meanForSeverity('caution')).toBeLessThan(meanForSeverity('neutral'));
  });

  it('四种缺省(null)按**最低频**处理 —— 不确定时不吓玩家', () => {
    expect(meanForSeverity(null)).toBe(meanForSeverity('neutral'));
  });

  it('稳态恒落在 [12s, 90s] —— 再频不密于 12s,再稀不长于 90s', () => {
    for (const sev of ['danger', 'caution', 'neutral', null] as const) {
      for (const r of [0.0001, 0.3, 0.9, 0.999999]) {
        const d = nextDelayMs({ phase: 'steady', severity: sev, sinceScrollMs: 0 }, () => r);
        expect(d).toBeGreaterThanOrEqual(MIN_STEADY_MS);
        expect(d).toBeLessThanOrEqual(MAX_STEADY_MS);
      }
    }
  });

  it('**任何状态都不形成周期**:同一 severity 下取样值高度分散(否则会被学成节拍)', () => {
    const vals = Array.from({ length: 200 }, () =>
      nextDelayMs({ phase: 'steady', severity: 'caution', sinceScrollMs: 0 }),
    );
    // 截断区间内部的取值几乎两两不同 —— 这才是「无记忆」该有的样子。
    const inside = vals.filter((v) => v > MIN_STEADY_MS && v < MAX_STEADY_MS);
    expect(inside.length).toBeGreaterThan(100);
    expect(new Set(inside).size).toBe(inside.length);
    // 跨度足够大:最短与最长相差数十秒,不是围着一个中心小幅摆动。
    expect(Math.max(...vals) - Math.min(...vals)).toBeGreaterThan(20_000);
  });

  // 如实记:指数分布 + 下界截断 → **相当一部分取值恰好落在 12s 下界**
  // (caution 均值 30s 时约三分之一,danger 均值 22s 时约四成)。这是样板间冻结参数的
  // 固有性质,本刀保持不改口径;「这算不算一种可被察觉的节拍」属体感问题,
  // 已列入真机冒烟观察项(本地测得出概率,测不出体感)。
  it('下界处会有一处概率堆积 —— 已知性质,钉住免得日后被当成 bug 改掉', () => {
    const vals = Array.from({ length: 400 }, () =>
      nextDelayMs({ phase: 'steady', severity: 'danger', sinceScrollMs: 0 }),
    );
    const atFloor = vals.filter((v) => v === MIN_STEADY_MS).length;
    expect(atFloor / vals.length).toBeGreaterThan(0.2);
    expect(atFloor / vals.length).toBeLessThan(0.6);
  });

  it('高位(neutral)极低频但**非绝对零** —— 只在低位启动会退化为状态播报(§5 Q4)', () => {
    const d = nextDelayMs({ phase: 'steady', severity: 'neutral', sinceScrollMs: 0 }, () => 0.5);
    expect(Number.isFinite(d)).toBe(true);
    expect(d).toBeLessThanOrEqual(MAX_STEADY_MS);
  });

  it('静读(久未滚动)略微加频,但不越出截断', () => {
    const still = nextDelayMs({ phase: 'steady', severity: 'caution', sinceScrollMs: 60_000 }, () => 0.5);
    const moving = nextDelayMs({ phase: 'steady', severity: 'caution', sinceScrollMs: 0 }, () => 0.5);
    expect(still).toBeLessThan(moving);
    expect(still).toBeGreaterThanOrEqual(MIN_STEADY_MS);
  });

  it('相位只走一次基线,之后稳态', () => {
    expect(advancePhase('baseline')).toBe('aftermath');
    expect(advancePhase('aftermath')).toBe('steady');
    expect(advancePhase('steady')).toBe('steady');
  });

  it('顺延与回前台的步长都为正、且回前台**不为零**(绝不切回来就炸一串)', () => {
    expect(deferMs(() => 0)).toBeGreaterThan(0);
    expect(resumeMs(() => 0)).toBeGreaterThanOrEqual(8_000);
  });
});

describe('异常施加与撤销(正文是禁区)', () => {
  const chunks = segmentProse('港口的雾比昨夜更沉。渔市收摊后石板路上留下发亮的黏液。');
  const el = () => document.createElement('span');

  const pickOf = (mode: string) => {
    const i = chunks.findIndex((c) => c.mode === mode);
    return { index: i, mode: chunks[i].mode, chunk: chunks[i] };
  };

  it('五型都能施加,且撤销后 DOM 与原文逐字相同、不残留任何字符或行内样式', () => {
    for (const mode of ['swap', 'swap2', 'tail', 'squeeze', 'shift']) {
      const chosen = pickOf(mode);
      if (chosen.index < 0) continue;
      const node = el();
      node.textContent = chosen.chunk.text;
      const restore = applyAnomaly(node, chosen, () => 100)!;
      expect(restore).toBeTruthy();
      restore();
      expect(node.textContent).toBe(chosen.chunk.text);
      expect(node.getAttribute('style')).toBeFalsy();
    }
  });

  it('swap 用的是人工表登记的替身(不是任意字)', () => {
    const chosen = pickOf('swap');
    const node = el();
    node.textContent = chosen.chunk.text;
    applyAnomaly(node, chosen, () => 100);
    expect(node.textContent).toBe(chosen.chunk.alt);
  });

  it('★ 换行守卫:改动引起高度变化 → 当场撤销并返回 null(宁可不发生,不接受跳行)', () => {
    for (const mode of ['tail', 'shift', 'squeeze']) {
      const chosen = pickOf(mode);
      if (chosen.index < 0) continue;
      const node = el();
      node.textContent = chosen.chunk.text;
      let h = 100;
      const restore = applyAnomaly(node, chosen, () => (h += 20)); // 每次测量都变 = 必然触发守卫
      expect(restore).toBeNull();
      expect(node.textContent).toBe(chosen.chunk.text); // 已撤销
      expect(node.getAttribute('style')).toBeFalsy();
    }
  });

  it('守卫按**结果**判(高度变没变),不按类型猜 —— 高度不变时五型都放行', () => {
    const chosen = pickOf('shift');
    const node = el();
    node.textContent = chosen.chunk.text;
    expect(applyAnomaly(node, chosen, () => 100)).toBeTruthy();
  });
});

describe('潮汐刻度(数值形态)', () => {
  it('渲染每根轴的名称、数值与档名', () => {
    render(<CthuStats {...statsProps([axis(), axis({ key: 'hp', displayName: '体力', value: 80, bandLabel: '尚可' })])} />);
    expect(screen.getByText('理智')).toBeInTheDocument();
    expect(screen.getByText('55')).toBeInTheDocument();
    expect(screen.getByText('神志清明')).toBeInTheDocument();
    expect(screen.getByText('体力')).toBeInTheDocument();
  });

  it('风险字样只在 caution / danger 出现,常态不加字', () => {
    const { container, rerender } = render(<CthuStats {...statsProps([axis({ severity: 'neutral' })])} />);
    expect(container.textContent).not.toContain('危险');
    expect(container.textContent).not.toContain('注意');
    rerender(<CthuStats {...statsProps([axis({ severity: 'caution' })])} />);
    expect(screen.getByText('注意')).toBeInTheDocument();
    rerender(<CthuStats {...statsProps([axis({ severity: 'danger' })])} />);
    expect(screen.getByText('危险')).toBeInTheDocument();
  });

  it('★ 风险态**只认 severity**:高位累积轴(禁忌知识 88 / danger)照样告警 —— 样板间那条固定红区会判反', () => {
    const { container } = render(
      <CthuStats
        {...statsProps([
          axis({ key: 'knowledge', displayName: '禁忌知识', value: 88, percent: 88, bandLabel: '深陷', severity: 'danger' }),
        ])}
      />,
    );
    expect(container.textContent).toContain('危险');
  });

  it('★ 低位但 severity=neutral 时**不进危险态**(位置不是语义)', () => {
    const { container } = render(
      <CthuStats {...statsProps([axis({ key: 'knowledge', displayName: '禁忌知识', value: 12, percent: 12, severity: 'neutral' })])} />,
    );
    expect(container.textContent).not.toContain('危险');
    expect(container.textContent).not.toContain('注意');
  });

  it('severity 缺省(null)安全降级:数字与档名照常,不进危险态', () => {
    const { container } = render(<CthuStats {...statsProps([axis({ severity: null })])} />);
    expect(container.textContent).toContain('55');
    expect(container.textContent).toContain('神志清明');
    expect(container.textContent).not.toContain('危险');
  });

  it('无档表:只显数字,不崩', () => {
    const { container } = render(<CthuStats {...statsProps([axis({ bandLabel: null, severity: null })])} />);
    expect(container.textContent).toContain('55');
  });

  it('可访问文本挂在每根轴上(读屏拿到完整语义)', () => {
    render(<CthuStats {...statsProps([axis()])} />);
    expect(screen.getByLabelText('理智 · 55 · 神志清明 · 正常')).toBeInTheDocument();
  });
});

describe('调查记录纸条(决策圈形态)', () => {
  const actions: AvailableAction[] = [
    { id: 'A', text: '沿黏液痕迹接近灯塔', hint: '雾正在退向海面' },
    { id: 'B', text: '回旅店翻查航海日志', hint: '' },
  ];

  it('渲染每个选项;hint 缺失时不渲染、不占位(ADR-011)', () => {
    const { container } = render(<CthuActions actions={actions} disabled={false} onChoose={() => {}} />);
    expect(screen.getByText('沿黏液痕迹接近灯塔')).toBeInTheDocument();
    expect(screen.getByText('雾正在退向海面')).toBeInTheDocument();
    expect(container.querySelectorAll('button')).toHaveLength(2);
  });

  it('点击只回传 id(语义与通用 DecisionCircle 一致)', () => {
    const picked: string[] = [];
    render(<CthuActions actions={actions} disabled={false} onChoose={(id) => picked.push(id)} />);
    screen.getByText('沿黏液痕迹接近灯塔').closest('button')!.click();
    expect(picked).toEqual(['A']);
  });

  it('忙态下选项禁用(重复输入不堆叠)', () => {
    render(<CthuActions actions={actions} disabled onChoose={() => {}} />);
    for (const b of screen.getAllByRole('button')) expect(b).toBeDisabled();
  });
});

describe('选点约束在真实文本上仍成立', () => {
  it('连续取十次:同一目标不连续、同一类型不连续', () => {
    const chunks = segmentProse(
      '港口的雾比昨夜更沉。渔市收摊后,石板路上留下一滩滩发亮的黏液,顺着坡道延伸到旧灯塔。你的怀表停在三点零七分,表针仍在轻轻颤动。',
    );
    const state = createPickState();
    let prev: { index: number; mode: string } | null = null;
    for (let i = 0; i < 10; i++) {
      const p = pickAnomaly(chunks, state);
      if (!p) continue;
      if (prev) {
        expect(p.index).not.toBe(prev.index);
        expect(p.mode).not.toBe(prev.mode);
      }
      state.lastIndex = p.index;
      state.lastMode = p.mode;
      prev = { index: p.index, mode: p.mode };
    }
    expect(prev).not.toBeNull();
  });
});
