import { StrictMode, useCallback, useRef, useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand, ClientAction } from '../../../../api';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime, useSkinRuntime } from '../lifecycle';
import { resolveWorldTheme } from '../registry';
import { SKINS } from '../skins';
import { rodFor, rodsFor } from './abacus';
import { LANTERN_FADE_MS, LANTERN_HOLD_MS, QiriAmbient } from './QiriAmbient';
import { QiriActions } from './QiriActions';
import styles from './qiri.module.css';
import { QIRI_KAI_CHARS } from './subset';

// 《七日开张》局内皮肤(ADR-038 Slice G-2)的测试。口径同 ADR-018 §5 Q8 / paidanSkin.test.tsx:
//   ① 共享语义;② 形态特征冒烟;③ 记忆点触发与收尾。守行为,不守像素。

const skin = SKINS.time_travel!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

// 档表照 ArchetypeRegistry(下发形态:显式区间 + severity)。
const CAPITAL: AxisBand[] = [
  { min: 0, max: 20, label: '靠赊账', severity: 'danger' },
  { min: 21, max: 50, label: '紧巴巴', severity: 'caution' },
  { min: 51, max: 100, label: '够周转', severity: 'neutral' },
];
const REGULARS: AxisBand[] = [
  { min: 0, max: 29, label: '生面孔', severity: 'neutral' },
  { min: 30, max: 59, label: '回头客', severity: 'neutral' },
  { min: 60, max: 100, label: '老主顾', severity: 'neutral' },
];
const COVER: AxisBand[] = [
  { min: 0, max: 20, label: '快露馅', severity: 'danger' },
  { min: 21, max: 50, label: '有点怪', severity: 'caution' },
  { min: 51, max: 100, label: '藏得住', severity: 'neutral' },
];
const AXES: AttributeAxisMeta[] = [
  { key: 'capital', displayName: '摊本', bands: CAPITAL },
  { key: 'regulars', displayName: '熟客', bands: REGULARS },
  { key: 'cover', displayName: '底细', bands: COVER },
];

describe('登记', () => {
  it('time_travel 登记了皮肤:签名轴 regulars、无入场、顶图不挂动效', () => {
    expect(resolveWorldTheme('time_travel').skin).toBe('time_travel');
    expect(skin.id).toBe('time_travel');
    expect(skin.signatureAxisKey).toBe('regulars');
    expect(skin.hasIntro).toBe(false);
    expect(skin.bannerImgClass).toBe('');
    expect(skin.valueRoll).toEqual({ durationMs: 600, ease: 'power3.out', ceremonyDelayMs: 600 });
    expect(skin.backPressDurMs).toBe(150);
  });
});

describe('珠子映射(abacus.ts):靠梁的算数,上珠当五,下珠当一', () => {
  const counts = (v: number) => rodsFor(v).map((r) => [r.upper ? 1 : 0, r.lower]);

  it.each([
    [0, [[0, 0], [0, 0], [0, 0]]],
    [5, [[0, 0], [0, 0], [1, 0]]],
    [9, [[0, 0], [0, 0], [1, 4]]],
    [41, [[0, 0], [0, 4], [0, 1]]],
    [100, [[0, 1], [0, 0], [0, 0]]],
  ])('%i → 百 / 十 / 个各档 [上珠, 下珠] 的算上数', (v, expected) => {
    expect(counts(v)).toEqual(expected);
  });

  it('一位数 0–9 逐个:上珠 = ≥5,下珠 = 除以 5 的余数', () => {
    for (let d = 0; d <= 9; d++) expect(rodFor(d)).toEqual({ upper: d >= 5, lower: d % 5 });
  });

  it('越界夹到 0–100、小数先取整、非数当 0', () => {
    expect(counts(-7)).toEqual(counts(0));
    expect(counts(250)).toEqual(counts(100));
    expect(counts(40.6)).toEqual(counts(41));
    expect(counts(Number.NaN)).toEqual(counts(0));
  });
});

describe('QiriStats(九档算盘)· 共享语义与面板结构', () => {
  const VALUES = { capital: 41, regulars: 36, cover: 18 };

  it('数值 / 中文名 / 档名 / 状态词全部可读,读屏一句读全', () => {
    withSkin(<StatsPanel axes={AXES} values={VALUES} />);
    for (const t of ['摊本', '熟客', '底细', '41', '36', '18', '紧巴巴', '回头客', '快露馅']) {
      expect(screen.getByText(t)).toBeInTheDocument();
    }
    expect(screen.getByLabelText('摊本 · 41 · 紧巴巴 · 注意')).toBeInTheDocument();
    expect(screen.getByLabelText('熟客 · 36 · 回头客 · 正常')).toBeInTheDocument();
    expect(screen.getByLabelText('底细 · 18 · 快露馅 · 危险')).toBeInTheDocument();
  });

  it('三组 × 三档 × 五颗珠;珠子摆成面板上的数', () => {
    const { container } = withSkin(<StatsPanel axes={AXES} values={VALUES} />);
    const groups = container.querySelectorAll('[data-group]');
    expect(groups).toHaveLength(3);
    groups.forEach((g) => {
      expect(g.querySelectorAll('[data-rod]')).toHaveLength(3);
      expect(g.querySelectorAll('[data-bead]')).toHaveLength(15);
    });
    // 摊本 41:十位下珠 4、个位下珠 1,共 5 颗算上;没有上珠算上。
    expect(groups[0].querySelectorAll('[data-on="true"]')).toHaveLength(5);
    expect(groups[0].querySelectorAll('[data-bead="upper"][data-on="true"]')).toHaveLength(0);
    // 底细 18:十位下珠 1;个位上珠 1 + 下珠 3。
    const rods = groups[2].querySelectorAll('[data-rod]');
    expect(rods[1].querySelectorAll('[data-on="true"]')).toHaveLength(1);
    expect(rods[2].querySelectorAll('[data-bead="upper"][data-on="true"]')).toHaveLength(1);
    expect(rods[2].querySelectorAll('[data-bead="lower"][data-on="true"]')).toHaveLength(3);
  });

  it('组按 axes 的顺序排(主题层不认轴:换个顺序,组跟着换)', () => {
    const { container } = withSkin(
      <StatsPanel axes={[AXES[2], AXES[0]]} values={{ capital: 100, cover: 0 }} />,
    );
    const groups = container.querySelectorAll('[data-group]');
    expect(groups).toHaveLength(2);
    expect(groups[0].querySelectorAll('[data-on="true"]')).toHaveLength(0); // 底细 0
    expect(groups[1].querySelectorAll('[data-on="true"]')).toHaveLength(1); // 摊本 100
  });

  it('面板头:灯笼常在(装饰,读屏不念);带 timeLabel 显示时辰牌,原样', () => {
    const { container } = withSkin(
      <StatsPanel axes={AXES} values={VALUES} timeLabel="第四日 · 午市" />,
    );
    expect(screen.getByText('第四日 · 午市')).toHaveClass(styles.hourTag);
    const lantern = container.querySelector('[data-lantern]');
    expect(lantern).toHaveAttribute('aria-hidden', 'true');
    expect(lantern?.querySelector(`.${styles.lanternBody}`)).not.toBeNull();
  });

  it('不带 timeLabel(缺省 / null):时辰牌节点不渲染,灯笼仍在', () => {
    for (const label of [undefined, null]) {
      const { container, unmount } = withSkin(
        <StatsPanel axes={AXES} values={VALUES} timeLabel={label} />,
      );
      expect(container.querySelector(`.${styles.hourTag}`)).toBeNull();
      expect(container.querySelector('[data-lantern]')).not.toBeNull();
      unmount();
    }
  });
});

describe('QiriStats · 状态只跟 severity 走(不读 key、不读数值高低)', () => {
  function one(severity: AxisBand['severity'] | undefined, key = 'whatever') {
    const band: AxisBand = { min: 0, max: 100, label: '档', ...(severity ? { severity } : {}) };
    const { container } = withSkin(
      <StatsPanel axes={[{ key, displayName: '某轴', bands: [band] }]} values={{ [key]: 77 }} />,
    );
    return {
      cell: container.querySelector('[data-severity]')!,
      group: container.querySelector('[data-group]')!,
      risk: container.querySelector(`.${styles.cellRisk}`)!,
    };
  }

  it('neutral → 状态词「正常」,无注意 / 危险态', () => {
    const { cell, group, risk } = one('neutral');
    expect(risk).toHaveTextContent('正常');
    expect(cell).not.toHaveClass(styles.caution);
    expect(cell).not.toHaveClass(styles.danger);
    expect(group).not.toHaveClass(styles.danger);
  });

  it('caution → 只有读数格进注意态;珠子那一组不变色', () => {
    const { cell, group, risk } = one('caution');
    expect(risk).toHaveTextContent('注意');
    expect(cell).toHaveClass(styles.caution);
    expect(group).not.toHaveClass(styles.danger);
  });

  it('danger → 读数格与珠子那一组都进危险态', () => {
    const { cell, group, risk } = one('danger');
    expect(risk).toHaveTextContent('危险');
    expect(cell).toHaveClass(styles.danger);
    expect(group).toHaveClass(styles.danger);
  });

  it('severity 缺省(null)→ 不渲染状态词,绝不默认危险', () => {
    const { cell, group, risk } = one(undefined);
    expect(risk).toBeEmptyDOMElement();
    expect(cell).not.toHaveClass(styles.danger);
    expect(group).not.toHaveClass(styles.danger);
  });

  it('同一个 severity,轴的 key 叫什么都一样(不按 key 分派)', () => {
    const a = one('danger', 'capital');
    const b = one('danger', 'regulars');
    expect(a.cell.className).toBe(b.cell.className);
    expect(a.group.className).toBe(b.group.className);
  });
});

describe('QiriActions(细框条目)', () => {
  const actions: ClientAction[] = [
    { id: 'A', text: '削取号竹牌', hint: '街坊会觉得新鲜', tag: '怪点子' },
    { id: 'B', text: '照常卖', hint: '' },
  ];

  it('带 tag 的选项显示小签(ADR-039 决策 5 的验收项);不带的没有这个节点', () => {
    const { container } = render(<QiriActions actions={actions} disabled={false} onChoose={() => {}} />);
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(2);
    expect(screen.getByText('怪点子')).toHaveClass(styles.optTag);
    expect(buttons[0].querySelector(`.${styles.optTag}`)).not.toBeNull();
    expect(buttons[1].querySelector(`.${styles.optTag}`)).toBeNull();
    expect(container.querySelectorAll(`.${styles.optTag}`)).toHaveLength(1);
  });

  it('所有 tag 同一种样式(不按内容区分)', () => {
    render(
      <QiriActions
        actions={[
          { id: 'A', text: '甲', hint: '', tag: '怪点子' },
          { id: 'B', text: '乙', hint: '', tag: '打圆场' },
        ]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    expect(screen.getByText('怪点子').className).toBe(screen.getByText('打圆场').className);
  });

  it('只回传 id;hint 缺失不渲染;生成中禁用', () => {
    const onChoose = vi.fn();
    const { rerender } = render(<QiriActions actions={actions} disabled={false} onChoose={onChoose} />);
    expect(screen.getByText('街坊会觉得新鲜')).toBeInTheDocument();
    fireEvent.click(screen.getByText('照常卖'));
    expect(onChoose).toHaveBeenCalledWith('B');
    rerender(<QiriActions actions={actions} disabled onChoose={onChoose} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });
});

describe('QiriAmbient · 一级记忆点「灯笼」(熟客向上跨档)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // 根 class 的作用域照 GameScreen.PlayingScreen:连同「皮肤 + 回合」一起存,换回合即作废。
  function Harness({ tick, turn = 1 }: { tick: number; turn?: number }) {
    const runtime = useSkinRuntime('time_travel', turn);
    const root = useRef<HTMLElement | null>(null);
    const scope = `time_travel|${turn}`;
    const [state, setState] = useState({ scope, cls: '' });
    const setRootClass = useCallback((cls: string) => setState({ scope, cls }), [scope]);
    const cls = state.scope === scope ? state.cls : '';
    return (
      <main ref={root} data-testid="root" className={cls}>
        <QiriAmbient
          runtime={runtime}
          rootRef={root}
          paused={false}
          generating
          turn={turn}
          setRootClass={setRootClass}
          onIntroDone={() => {}}
          signatureTick={tick}
        />
      </main>
    );
  }

  const lit = () => screen.getByTestId('root').classList.contains(styles.lanternOn);

  it('首次装载(tick=0)不亮 —— init / 续局首帧不触发', () => {
    vi.useFakeTimers();
    render(<Harness tick={0} />);
    act(() => vi.advanceTimersByTime(5000));
    expect(lit()).toBe(false);
  });

  it('跨档 → 亮(生成中也亮)→ 停 2800ms → 熄', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    expect(lit()).toBe(true);
    act(() => vi.advanceTimersByTime(LANTERN_HOLD_MS - 1));
    expect(lit()).toBe(true);
    act(() => vi.advanceTimersByTime(1));
    expect(lit()).toBe(false);
  });

  it('换回合与跨档同一次渲染发生(生产的真实时序)→ 仍会亮', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} turn={7} />);
    rerender(<Harness tick={1} turn={8} />);
    expect(lit()).toBe(true);
    act(() => vi.advanceTimersByTime(LANTERN_HOLD_MS));
    expect(lit()).toBe(false);
  });

  it('亮着时再来一次直接忽略,不排队;熄灭过渡走完后才能再亮', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    act(() => vi.advanceTimersByTime(1000));
    rerender(<Harness tick={2} />); // 亮着 → 忽略
    act(() => vi.advanceTimersByTime(LANTERN_HOLD_MS - 1000));
    expect(lit()).toBe(false); // 按第一次的时刻熄,没有被续上
    rerender(<Harness tick={3} />); // 还在熄灭过渡里 → 仍忽略
    expect(lit()).toBe(false);
    act(() => vi.advanceTimersByTime(LANTERN_FADE_MS));
    rerender(<Harness tick={4} />);
    expect(lit()).toBe(true);
  });

  it('亮着时换了回合 → 立刻熄,不补播', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness tick={0} turn={1} />);
    rerender(<Harness tick={1} turn={1} />);
    expect(lit()).toBe(true);
    rerender(<Harness tick={1} turn={2} />);
    expect(lit()).toBe(false);
    act(() => vi.advanceTimersByTime(LANTERN_HOLD_MS + LANTERN_FADE_MS));
    expect(lit()).toBe(false);
  });

  it('StrictMode 双挂载后仍会亮', () => {
    vi.useFakeTimers();
    const { rerender } = render(
      <StrictMode>
        <Harness tick={0} />
      </StrictMode>,
    );
    rerender(
      <StrictMode>
        <Harness tick={1} />
      </StrictMode>,
    );
    expect(lit()).toBe(true);
  });

  it('减弱动效下仍然亮和熄(没有过渡由 CSS 负责)', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const { rerender } = render(<Harness tick={0} />);
    rerender(<Harness tick={1} />);
    expect(lit()).toBe(true);
    act(() => vi.advanceTimersByTime(LANTERN_HOLD_MS));
    expect(lit()).toBe(false);
  });

  it('卸载后定时器不残留;非 debug 时不渲染重播按钮', () => {
    vi.useFakeTimers();
    const { rerender, unmount } = render(<Harness tick={0} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    rerender(<Harness tick={1} />);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});

// 字符集写死(ADR-038 Slice G-2 决策 5):用楷体的四处可能出现的字,都必须在子集里。
// 时辰标签的文字在服务端 ClientProjection.java(QIRI_DAYS / QIRI_PARTS 与夜集两句),这里是手抄 ——
// 服务端改了文案,这条测试不会红;改文案的人要记得重裁并同步这里(fonts/README.md)。
describe('QiriKai 子集覆盖用楷体的四处', () => {
  const DAYS = '一二三四五六七';
  const PARTS = ['早市', '午市', '晚市'];
  const labels = [
    ...[...DAYS].flatMap((d) => PARTS.map((p) => `第${d}日 · ${p}`)),
    '夜集 · 开张',
    '夜集 · 收摊',
  ];
  const numbers = Array.from({ length: 101 }, (_, i) => String(i));
  const all = ['七日开张', '摊本', '熟客', '底细', ...labels, ...numbers];

  it('时辰标签共 23 个', () => {
    expect(labels).toHaveLength(23);
  });

  it('世界标题、轴名、0–100、全部时辰标签的每个字都在子集里', () => {
    const subset = new Set(QIRI_KAI_CHARS);
    const missing = [...new Set(all.join(''))].filter((c) => !subset.has(c));
    expect(missing).toEqual([]);
  });

  it('子集里没有用不到的字(36 个码位,一个不多)', () => {
    const used = new Set(all.join(''));
    expect([...QIRI_KAI_CHARS].filter((c) => !used.has(c))).toEqual([]);
    expect(new Set(QIRI_KAI_CHARS).size).toBe(36);
  });
});
