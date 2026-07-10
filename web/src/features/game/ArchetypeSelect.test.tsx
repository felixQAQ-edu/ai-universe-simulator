import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import { ArchetypeCard, FusionCard } from './ArchetypeSelect';
import { INITIAL_FUSION_STAGES, nextFusionStages, type FusionStages } from './fusion';

// 选择屏卡片(ADR-008 决策 4):可玩卡渲染钩子/标签 + 点击触发 onChoose(→ startGame);
// 未开放卡灰显「敬请期待」、不可点。store 接线(loadArchetypes/startGame/lastArchetype)
// 已由 gameStore.test 覆盖,这里只钉纯展示 + 点击语义。

const APOCALYPSE: ArchetypeSummary = {
  archetype: 'apocalypse',
  displayName: '末日生存',
  tagline: '废土求生,饥饿是另一个敌人。',
  vibeTag: '荒凉 · 绝境',
  active: true,
};
const CTHULHU: ArchetypeSummary = {
  archetype: 'cthulhu',
  displayName: '克苏鲁',
  tagline: '凝视深渊,深渊回以低语。知道得越多,离疯狂越近。',
  vibeTag: '深渊 · 疯狂',
  active: true,
};
const LOCKED: ArchetypeSummary = {
  archetype: 'life_sim',
  displayName: '人生模拟',
  tagline: null,
  vibeTag: null,
  active: false,
};

describe('ArchetypeCard', () => {
  it('可玩卡:渲染名称/钩子/标签,点击触发 onChoose', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={APOCALYPSE} onChoose={onChoose} />);
    expect(screen.getByText('末日生存')).toBeInTheDocument();
    expect(screen.getByText('废土求生,饥饿是另一个敌人。')).toBeInTheDocument();
    expect(screen.getByText('荒凉 · 绝境')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button'));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('克苏鲁可玩卡:加世界自动进目录,渲染钩子/标签 + 点击触发 onChoose', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={CTHULHU} onChoose={onChoose} />);
    expect(screen.getByText('克苏鲁')).toBeInTheDocument();
    expect(screen.getByText('深渊 · 疯狂')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button'));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('未开放卡:灰显「敬请期待」,无可点按钮', () => {
    const onChoose = vi.fn();
    render(<ArchetypeCard summary={LOCKED} onChoose={onChoose} />);
    expect(screen.getByText('人生模拟')).toBeInTheDocument();
    expect(screen.getByText('敬请期待')).toBeInTheDocument();
    // 占位不是 button(不可点)。
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});

// ── 融合入口 = 渗漏卡 + 误入手势(ADR-013 决策 4)──

const CULTIVATION: ArchetypeSummary = {
  archetype: 'cultivation',
  displayName: '修仙',
  tagline: '逆天改命,踏上仙途。',
  vibeTag: '缥缈 · 仙途',
  active: true,
};

describe('误入手势(长按)', () => {
  it('长按(≥600ms)触发 onLongPress,且随后的 click 不触发 onChoose(不误入局)', () => {
    vi.useFakeTimers();
    const onChoose = vi.fn();
    const onLongPress = vi.fn();
    render(<ArchetypeCard summary={CULTIVATION} onChoose={onChoose} onLongPress={onLongPress} />);
    const card = screen.getByRole('button');

    fireEvent.pointerDown(card);
    vi.advanceTimersByTime(650);
    fireEvent.pointerUp(card);
    fireEvent.click(card); // 长按释放后浏览器仍会派发 click —— 必须被吞掉

    expect(onLongPress).toHaveBeenCalledTimes(1);
    expect(onChoose).not.toHaveBeenCalled();
    vi.useRealTimers();
  });

  it('短按(<600ms)不触发 onLongPress,click 照常进入世界(单击零回归)', () => {
    vi.useFakeTimers();
    const onChoose = vi.fn();
    const onLongPress = vi.fn();
    render(<ArchetypeCard summary={CULTIVATION} onChoose={onChoose} onLongPress={onLongPress} />);
    const card = screen.getByRole('button');

    fireEvent.pointerDown(card);
    vi.advanceTimersByTime(200);
    fireEvent.pointerUp(card);
    fireEvent.click(card);

    expect(onLongPress).not.toHaveBeenCalled();
    expect(onChoose).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  // 组合表状态机(ADR-014):per-combo 独立,一次长按推进全部机。
  const SHIHAI = 'cultivation×rules_creepy';
  const RENFANG = 'rules_creepy×apocalypse';
  const press = (stages: FusionStages, ...archetypes: string[]) =>
    archetypes.reduce((s, a) => nextFusionStages(s, a), stages);

  it('状态机(识海):依次长按 修仙 → 规则怪谈 才渗出;顺序不对不推进;渗出后保持', () => {
    // 正序:修仙 → 规则怪谈 → 识海 revealed。
    let s = press(INITIAL_FUSION_STAGES, 'cultivation', 'rules_creepy');
    expect(s[SHIHAI]).toBe('revealed');
    // 顺序不对:先规则怪谈、再修仙 → 识海不渗出(修仙重 armed)。
    s = press(INITIAL_FUSION_STAGES, 'rules_creepy', 'cultivation');
    expect(s[SHIHAI]).toBe('armed');
    // armed 后按无关卡不推进。
    s = press(INITIAL_FUSION_STAGES, 'cultivation', 'cthulhu');
    expect(s[SHIHAI]).toBe('armed');
    // 渗出后保持(不因再长按回退)。
    s = press(INITIAL_FUSION_STAGES, 'cultivation', 'rules_creepy', 'cultivation', 'apocalypse');
    expect(s[SHIHAI]).toBe('revealed');
  });

  it('状态机(守则即补给):依次长按 规则怪谈 → 末日 才渗出', () => {
    let s = press(INITIAL_FUSION_STAGES, 'rules_creepy', 'apocalypse');
    expect(s[RENFANG]).toBe('revealed');
    // 顺序不对:先末日不推进。
    s = press(INITIAL_FUSION_STAGES, 'apocalypse', 'cthulhu');
    expect(s[RENFANG]).toBe('idle');
  });

  it('交叉序列:「长按规则怪谈」在两台机语义不同,各自匹配、互不误触发', () => {
    // 修仙 → 末日:两台机都不渗出(识海差第二步、本对差第一步)。
    let s = press(INITIAL_FUSION_STAGES, 'cultivation', 'apocalypse');
    expect(s[SHIHAI]).toBe('armed');
    expect(s[RENFANG]).toBe('idle');
    // 规则怪谈 → 末日:只渗出「守则即补给」,识海不受影响(规则怪谈对识海是第二步、需先修仙)。
    s = press(INITIAL_FUSION_STAGES, 'rules_creepy', 'apocalypse');
    expect(s[RENFANG]).toBe('revealed');
    expect(s[SHIHAI]).toBe('idle');
    // 修仙 → 规则怪谈:渗出识海;同一按规则怪谈同时是本对第一步(armed)——独立推进、互不干扰。
    s = press(INITIAL_FUSION_STAGES, 'cultivation', 'rules_creepy');
    expect(s[SHIHAI]).toBe('revealed');
    expect(s[RENFANG]).toBe('armed');
  });
});

describe('FusionCard(渗漏卡,ADR-014 参数化)', () => {
  it('识海卡:渲染三层撕裂标题(修仙/规则怪谈/识海遗蜕)+ 渗漏标签,点击触发 onChoose(→ 双值 init)', () => {
    const onChoose = vi.fn();
    render(<FusionCard combo="cultivation×rules_creepy" onChoose={onChoose} />);
    // 三层标题都在(CSS 轮换浮现;可及名是「识海遗蜕(融合世界)」)。
    expect(screen.getByText('修仙')).toBeInTheDocument();
    expect(screen.getByText('规则怪谈')).toBeInTheDocument();
    expect(screen.getByText('识海遗蜕')).toBeInTheDocument();
    expect(screen.getByText('渗漏 · 勿入')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '识海遗蜕(融合世界)' }));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('守则即补给卡:渲染三层撕裂标题(规则怪谈/末日生存/缺页的人防工程),点击触发 onChoose', () => {
    const onChoose = vi.fn();
    render(<FusionCard combo="rules_creepy×apocalypse" onChoose={onChoose} />);
    expect(screen.getByText('规则怪谈')).toBeInTheDocument();
    expect(screen.getByText('末日生存')).toBeInTheDocument();
    expect(screen.getByText('缺页的人防工程')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: '缺页的人防工程(融合世界)' }));
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('未配文案的组合不渲染(登记齐再上)', () => {
    const { container } = render(<FusionCard combo="cthulhu×life_sim" onChoose={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });
});
