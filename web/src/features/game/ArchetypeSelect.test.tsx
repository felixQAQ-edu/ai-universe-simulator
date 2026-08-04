import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ArchetypeSummary } from '../../api';
import { ArchetypeCard, FusionCard } from './ArchetypeSelect';

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

const CULTIVATION: ArchetypeSummary = {
  archetype: 'cultivation',
  displayName: '修仙',
  tagline: '逆天改命,踏上仙途。',
  vibeTag: '缥缈 · 仙途',
  active: true,
};

// ── 融合入口 = 把一张卡拖到另一张上(ADR-019;ADR-013 决策 4 的长按误入手势本轮退役)──
// 退役理由是技术性的:拖拽抓起 180ms 是长按 600ms 的**真子集**,并存只能靠让长按永不触发。
// 手势本身的钉子在 fusion/useFusionDrag.test.tsx,这里只钉卡片这一侧的接线。

describe('拖拽接线(卡片侧)', () => {
  it('可拖卡:pointerdown 交给手势层;抓起过的那一次 click 被吞掉(不误入世界)', () => {
    const onChoose = vi.fn();
    const onPointerDown = vi.fn();
    let swallow = true;
    render(
      <ArchetypeCard
        summary={CULTIVATION}
        onChoose={onChoose}
        drag={{
          ref: () => {},
          onPointerDown,
          shouldSwallowClick: () => swallow,
          state: null,
        }}
      />,
    );
    const card = screen.getByRole('button');
    fireEvent.pointerDown(card);
    expect(onPointerDown).toHaveBeenCalledTimes(1);

    fireEvent.click(card); // 抓起过 → 吞掉
    expect(onChoose).not.toHaveBeenCalled();

    swallow = false; // 普通单击 → 照常进世界(单击语义零回归)
    fireEvent.click(card);
    expect(onChoose).toHaveBeenCalledTimes(1);
  });

  it('拖拽角色只改 class 不改 DOM 结构(ADR-018 §4.17:差异只在样式 → 样式令牌)', () => {
    const bind = (state: 'dragging' | 'valid' | 'invalid' | null) => ({
      ref: () => {},
      onPointerDown: () => {},
      shouldSwallowClick: () => false,
      state,
    });
    const plain = render(<ArchetypeCard summary={CULTIVATION} onChoose={vi.fn()} drag={bind(null)} />);
    const plainHtml = plain.container.innerHTML;
    plain.unmount();

    for (const state of ['dragging', 'valid', 'invalid'] as const) {
      const r = render(<ArchetypeCard summary={CULTIVATION} onChoose={vi.fn()} drag={bind(state)} />);
      const el = r.container.querySelector('button')!;
      expect(el.className).not.toBe(plain.container.querySelector('button')?.className);
      // 结构一致:去掉 class 属性后与中性态逐字相等。
      const strip = (h: string) => h.replace(/ class="[^"]*"/g, '');
      expect(strip(r.container.innerHTML)).toBe(strip(plainHtml));
      r.unmount();
    }
  });
});

describe('FusionCard(融合卡 = 渗漏卡形态保留,ADR-014 参数化)', () => {
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
