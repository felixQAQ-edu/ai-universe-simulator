import { StrictMode, useCallback, useRef, useState } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand, ClientAction } from '../../../../api';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime, useSkinRuntime } from '../lifecycle';
import { resolveWorldTheme } from '../registry';
import { SKINS } from '../skins';
import { AnimalActions } from './AnimalActions';
import { AnimalAmbient, INTRO_DARK_MS, INTRO_SPREAD_MS } from './AnimalAmbient';
import { JOINTS, beamPercent } from './beam';
import styles from './animal.module.css';

// 《动物人生》局内皮肤(ADR-021「局内皮肤 + 入场记忆点」一节)的测试。口径同 qiriSkin.test.tsx:
//   ① 共享语义;② 形态特征冒烟;③ 记忆点(入场)的播放与收尾。守行为,不守像素。

const skin = SKINS.animal_life!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

// 档表照 ArchetypeRegistry 现行派生(下发形态:显式区间 + severity):
// 只有「身子」(致命消耗轴)会给 caution / danger;暖 / 地面 / 近人三档全 neutral(ADR-021 局内皮肤一节 勘察 1)。
const BODY: AxisBand[] = [
  { min: 0, max: 20, label: '撑不住', severity: 'danger' },
  { min: 21, max: 50, label: '有旧伤', severity: 'caution' },
  { min: 51, max: 100, label: '壮', severity: 'neutral' },
];
const WARMTH: AxisBand[] = [
  { min: 0, max: 20, label: '冻着', severity: 'neutral' },
  { min: 21, max: 50, label: '冷', severity: 'neutral' },
  { min: 51, max: 100, label: '暖和', severity: 'neutral' },
];
const GROUND: AxisBand[] = [
  { min: 0, max: 30, label: '没有地方', severity: 'neutral' },
  { min: 31, max: 60, label: '只剩几处', severity: 'neutral' },
  { min: 61, max: 100, label: '走得开', severity: 'neutral' },
];
const CLOSE: AxisBand[] = [
  { min: 0, max: 29, label: '躲着人', severity: 'neutral' },
  { min: 30, max: 59, label: '敢靠近', severity: 'neutral' },
  { min: 60, max: 100, label: '站得很近', severity: 'neutral' },
];
const AXES: AttributeAxisMeta[] = [
  { key: 'body', displayName: '身子', bands: BODY },
  { key: 'warmth', displayName: '暖', bands: WARMTH },
  { key: 'ground', displayName: '地面', bands: GROUND },
  { key: 'close', displayName: '近人', bands: CLOSE },
];
const VALUES = { body: 46, warmth: 72, ground: 35, close: 64 };

const tracks = (c: HTMLElement) => [...c.querySelectorAll<HTMLElement>('[data-track]')];
const beamOf = (track: Element) => track.querySelector<HTMLElement>('[data-beam]')!;

describe('登记', () => {
  it('animal_life 登记了皮肤:有入场、不挂任何轴、顶图不挂动效', () => {
    expect(resolveWorldTheme('animal_life').skin).toBe('animal_life');
    expect(skin.id).toBe('animal_life');
    expect(skin.hasIntro).toBe(true);
    expect(skin.signatureAxisKey).toBeUndefined();
    expect(skin.bannerImgClass).toBe('');
    expect(skin.pausedClass).toBe('');
    expect(skin.valueRoll).toEqual({ durationMs: 500, ease: 'power3.out' });
    expect(skin.backPressDurMs).toBeUndefined();
    expect(skin.Interlude).toBeUndefined();
    expect(skin.Prose).toBeUndefined();
  });
});

describe('光带长度(beamPercent):只从数值来', () => {
  it('取整后夹到 0–100;非数当 0', () => {
    expect(beamPercent(0)).toBe(0);
    expect(beamPercent(1)).toBe(1);
    expect(beamPercent(46)).toBe(46);
    expect(beamPercent(100)).toBe(100);
    expect(beamPercent(-5)).toBe(0);
    expect(beamPercent(140)).toBe(100);
    expect(beamPercent(45.6)).toBe(46);
    expect(beamPercent(Number.NaN)).toBe(0);
  });
});

describe('AnimalStats(地板线上的门缝光)· 共享语义与面板结构', () => {
  const view = () => withSkin(<StatsPanel axes={AXES} values={VALUES} />);

  it('没有面板头;每根轴一行:轴名 / 地板线 / 接缝 / 光带 / 遮片 / 门缝 / 数字 / 档名', () => {
    const { container } = view();
    const floor = container.querySelector(`.${styles.floor}`)!;
    // 四根轴 × 四个格子,直接是面板的子节点 —— 没有任何别的东西(面板头、标题)。
    expect(floor.children).toHaveLength(16);
    expect([...floor.querySelectorAll(`.${styles.sName}`)].map((n) => n.textContent)).toEqual([
      '身子', '暖', '地面', '近人',
    ]);
    expect([...floor.querySelectorAll(`.${styles.sNum}`)].map((n) => n.textContent)).toEqual([
      '46', '72', '35', '64',
    ]);
    expect([...floor.querySelectorAll('[data-band]')].map((n) => n.textContent)).toEqual([
      '有旧伤', '暖和', '只剩几处', '站得很近',
    ]);
    for (const t of tracks(container)) {
      expect(t.querySelector(`.${styles.ground}`)).not.toBeNull();
      expect(t.querySelector(`.${styles.joint}`)).not.toBeNull();
      expect(t.querySelector('[data-beam]')).not.toBeNull();
      expect(t.querySelector('[data-veil]')).not.toBeNull();
      expect(t.querySelector('[data-gap]')).not.toBeNull();
    }
  });

  it('光带的位置只跟数值走:translateX(100 − v)%', () => {
    const { container } = view();
    expect(tracks(container).map((t) => beamOf(t).style.transform)).toEqual([
      'translateX(54%)', 'translateX(28%)', 'translateX(65%)', 'translateX(36%)',
    ]);
  });

  it('数值到头:0 → 整条移出,100 → 不平移;越界与小数照 beamPercent', () => {
    const one = (v: number) => {
      const { container, unmount } = withSkin(
        <StatsPanel axes={[{ key: 'k', displayName: '某轴', bands: [] }]} values={{ k: v }} />,
      );
      const t = beamOf(tracks(container)[0]).style.transform;
      unmount();
      return t;
    };
    expect(one(0)).toBe('translateX(100%)');
    expect(one(1)).toBe('translateX(99%)');
    expect(one(100)).toBe('translateX(0%)');
    expect(one(140)).toBe('translateX(0%)');
    expect(one(-3)).toBe('translateX(100%)');
  });

  it('读屏:地板线那一格念 a11yText(含风险词);三个可见格子不重复念', () => {
    const { container } = view();
    const [body, warmth] = tracks(container);
    expect(body).toHaveAttribute('role', 'img');
    expect(body).toHaveAttribute('aria-label', '身子 · 46 · 有旧伤 · 注意');
    expect(warmth).toHaveAttribute('aria-label', '暖 · 72 · 暖和 · 正常');
    for (const sel of [`.${styles.sName}`, `.${styles.sNum}`, '[data-band]']) {
      container.querySelectorAll(sel).forEach((n) => expect(n).toHaveAttribute('aria-hidden', 'true'));
    }
  });

  it('屏上不出现「正常 / 注意 / 危险」这几个字(只在 aria-label 里)', () => {
    const { container } = withSkin(
      <StatsPanel axes={AXES} values={{ body: 14, warmth: 17, ground: 9, close: 20 }} />,
    );
    expect(container.textContent).not.toMatch(/正常|注意|危险/);
    expect(tracks(container)[0]).toHaveAttribute('aria-label', '身子 · 14 · 撑不住 · 危险');
  });
});

describe('AnimalStats · 行数跟 axes 走(不写死四行,主题层不认轴)', () => {
  const axesOf = (n: number): AttributeAxisMeta[] =>
    Array.from({ length: n }, (_, i) => ({ key: `k${i}`, displayName: `轴${i}`, bands: [] }));
  const valuesOf = (n: number) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`k${i}`, 10 * i]));

  it.each([1, 3, 4, 6])('%i 根轴 → %i 行', (n) => {
    const { container } = withSkin(<StatsPanel axes={axesOf(n)} values={valuesOf(n)} />);
    expect(tracks(container)).toHaveLength(n);
    expect(container.querySelector(`.${styles.floor}`)!.children).toHaveLength(n * 4);
  });

  it('接缝位置按行号取 22% / 61% / 38% / 74%,第 5 行起循环', () => {
    const { container } = withSkin(<StatsPanel axes={axesOf(6)} values={valuesOf(6)} />);
    const lefts = tracks(container).map((t) => t.querySelector<HTMLElement>(`.${styles.joint}`)!.style.left);
    expect(lefts).toEqual(['22%', '61%', '38%', '74%', '22%', '61%']);
    expect([...JOINTS]).toEqual(['22%', '61%', '38%', '74%']);
  });

  it('行按 axes 的顺序排:换个顺序,行跟着换,接缝位置留在行号上', () => {
    const { container } = withSkin(<StatsPanel axes={[...AXES].reverse()} values={VALUES} />);
    expect([...container.querySelectorAll(`.${styles.sName}`)].map((n) => n.textContent)).toEqual([
      '近人', '地面', '暖', '身子',
    ]);
    const first = tracks(container)[0];
    expect(first).toHaveAttribute('aria-label', '近人 · 64 · 站得很近 · 正常');
    expect(first.querySelector<HTMLElement>(`.${styles.joint}`)!.style.left).toBe('22%');
  });

  it('没有档表的轴:数字照常,档名为空但那一格还在(占位)', () => {
    const { container } = withSkin(<StatsPanel axes={axesOf(1)} values={{ k0: 33 }} />);
    const band = container.querySelector('[data-band]')!;
    expect(band).toBeInTheDocument();
    expect(band.textContent).toBe('');
    expect(band.querySelector('i')).not.toBeNull();
  });
});

describe('AnimalStats · 状态只跟 severity 走(不读 key、不读数值高低)', () => {
  function one(severity: AxisBand['severity'] | undefined, key = 'whatever', value = 77) {
    const band: AxisBand = { min: 0, max: 100, label: '档', ...(severity ? { severity } : {}) };
    const { container } = withSkin(
      <StatsPanel axes={[{ key, displayName: '某轴', bands: [band] }]} values={{ [key]: value }} />,
    );
    return {
      track: container.querySelector('[data-track]')!,
      band: container.querySelector('[data-band]')!,
    };
  }

  it('neutral → 常态:无注意 / 危险态;小方块节点在(占位),不带状态 class', () => {
    const { track, band } = one('neutral');
    expect(track.className).toBe(styles.track);
    expect(band.className).toBe(styles.sBand);
    expect(band.querySelector('i')).not.toBeNull();
    expect(track).toHaveAttribute('data-severity', 'neutral');
  });

  it('caution → 地板线与档名都进注意态(光带转淡、空心小方块由 CSS 负责)', () => {
    const { track, band } = one('caution');
    expect(track).toHaveClass(styles.caution);
    expect(band).toHaveClass(styles.caution);
    expect(track).not.toHaveClass(styles.danger);
    expect(band).not.toHaveClass(styles.danger);
  });

  it('danger → 地板线与档名都进危险态(不画光带、门缝转灰、档名转暗红由 CSS 负责)', () => {
    const { track, band } = one('danger');
    expect(track).toHaveClass(styles.danger);
    expect(band).toHaveClass(styles.danger);
  });

  it('severity 缺省(null)→ 与 neutral 同样的常态,绝不默认危险', () => {
    const { track, band } = one(undefined);
    expect(track.className).toBe(styles.track);
    expect(band.className).toBe(styles.sBand);
    expect(track).toHaveAttribute('data-severity', '');
    expect(band.querySelector('i')).not.toBeNull();
  });

  it('同一个 severity,轴的 key 叫什么都一样(不按 key 分派)', () => {
    const a = one('danger', 'body');
    const b = one('danger', 'close');
    expect(a.track.className).toBe(b.track.className);
    expect(a.band.className).toBe(b.band.className);
  });

  it('数值很低但 severity 是 neutral → 仍是常态(不按数值高低自己判断)', () => {
    const { track, band } = one('neutral', 'warmth', 3);
    expect(track).not.toHaveClass(styles.danger);
    expect(track).not.toHaveClass(styles.caution);
    expect(band).not.toHaveClass(styles.danger);
  });

  it('现行服务端的档表下:只有「身子」那一行会进危险态', () => {
    const { container } = withSkin(
      <StatsPanel axes={AXES} values={{ body: 14, warmth: 17, ground: 9, close: 20 }} />,
    );
    expect(tracks(container).map((t) => t.classList.contains(styles.danger))).toEqual([
      true, false, false, false,
    ]);
  });
});

describe('AnimalActions(一块面板里几行)', () => {
  const actions: ClientAction[] = [
    { id: 'A', text: '趴到门边去,等', hint: '' },
    { id: 'B', text: '去闻那只新来的箱子', hint: '那里有不认得的味道' },
  ];

  it('渲染编号 / 正文 / hint;hint 缺失不渲染;只回传 id', () => {
    const onChoose = vi.fn();
    const { container } = render(<AnimalActions actions={actions} disabled={false} onChoose={onChoose} />);
    const buttons = screen.getAllByRole('button');
    expect(buttons).toHaveLength(2);
    // 一块面板:所有行在同一个容器里。
    expect(container.querySelectorAll(`.${styles.opts}`)).toHaveLength(1);
    expect(buttons[0].parentElement).toBe(buttons[1].parentElement);
    expect(screen.getByText('那里有不认得的味道')).toHaveClass(styles.optHint);
    expect(container.querySelectorAll(`.${styles.optHint}`)).toHaveLength(1);
    fireEvent.click(screen.getByText('趴到门边去,等'));
    expect(onChoose).toHaveBeenCalledWith('A');
  });

  it('不带 tag(现在服务端没有登记):没有小签节点,行也不多出第三栏', () => {
    const { container } = render(<AnimalActions actions={actions} disabled={false} onChoose={() => {}} />);
    expect(container.querySelector(`.${styles.optTag}`)).toBeNull();
    screen.getAllByRole('button').forEach((b) => expect(b).not.toHaveClass(styles.optTagged));
  });

  it('带 tag 时会显示(ADR-039 决策 5);不带的那一行没有这个节点', () => {
    const { container } = render(
      <AnimalActions
        actions={[{ id: 'A', text: '叫一声', hint: '', tag: '老办法' }, actions[1]]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    const buttons = screen.getAllByRole('button');
    expect(screen.getByText('老办法')).toHaveClass(styles.optTag);
    expect(buttons[0]).toHaveClass(styles.optTagged);
    expect(buttons[1].querySelector(`.${styles.optTag}`)).toBeNull();
    expect(buttons[1]).not.toHaveClass(styles.optTagged);
    expect(container.querySelectorAll(`.${styles.optTag}`)).toHaveLength(1);
  });

  it('所有 tag 同一种样式(不按内容区分)', () => {
    render(
      <AnimalActions
        actions={[
          { id: 'A', text: '甲', hint: '', tag: '老办法' },
          { id: 'B', text: '乙', hint: '', tag: '新地方' },
        ]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    expect(screen.getByText('老办法').className).toBe(screen.getByText('新地方').className);
  });

  it('生成中禁用', () => {
    render(<AnimalActions actions={actions} disabled onChoose={() => {}} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });
});

describe('AnimalAmbient · 一级记忆点「门缝光」(进局时光照进来一次)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  // 根 class 的作用域照 GameScreen.PlayingScreen:连同「皮肤 + 回合」一起存,换回合即作废。
  function Harness({
    turn = 0,
    generating = false,
    onIntroDone = () => {},
  }: {
    turn?: number;
    generating?: boolean;
    onIntroDone?: () => void;
  }) {
    const runtime = useSkinRuntime('animal_life', turn);
    const root = useRef<HTMLElement | null>(null);
    const scope = `animal_life|${turn}`;
    const [state, setState] = useState({ scope, cls: '' });
    const setRootClass = useCallback((cls: string) => setState({ scope, cls }), [scope]);
    const cls = state.scope === scope ? state.cls : '';
    return (
      <main ref={root} data-testid="root" className={cls}>
        <AnimalAmbient
          runtime={runtime}
          rootRef={root}
          paused={false}
          generating={generating}
          turn={turn}
          setRootClass={setRootClass}
          onIntroDone={onIntroDone}
          signatureTick={0}
        />
      </main>
    );
  }

  const dark = () => screen.getByTestId('root').classList.contains(styles.closed);

  it('新开局:挂载即暗 → 500ms 摘掉(光开始铺)→ 再 900ms 放行正文,只放一次', () => {
    vi.useFakeTimers();
    const onIntroDone = vi.fn();
    render(<Harness onIntroDone={onIntroDone} />);
    // 挂载后的第一次读取就已经是暗的(useLayoutEffect:不是等一帧之后才暗)。
    expect(dark()).toBe(true);
    act(() => vi.advanceTimersByTime(INTRO_DARK_MS - 1));
    expect(dark()).toBe(true);
    act(() => vi.advanceTimersByTime(1));
    expect(dark()).toBe(false);
    expect(onIntroDone).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(INTRO_SPREAD_MS - 1));
    expect(onIntroDone).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(1));
    expect(onIntroDone).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(5000));
    expect(onIntroDone).toHaveBeenCalledTimes(1);
    expect(dark()).toBe(false);
  });

  it('时长:暗 500ms、铺光 900ms(样张原值)', () => {
    expect(INTRO_DARK_MS).toBe(500);
    expect(INTRO_SPREAD_MS).toBe(900);
  });

  it('续局(回合 > 0)也播', () => {
    vi.useFakeTimers();
    render(<Harness turn={9} />);
    expect(dark()).toBe(true);
    act(() => vi.advanceTimersByTime(INTRO_DARK_MS));
    expect(dark()).toBe(false);
  });

  it('换回合不重播', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness turn={3} />);
    act(() => vi.advanceTimersByTime(INTRO_DARK_MS + INTRO_SPREAD_MS));
    rerender(<Harness turn={4} />);
    expect(dark()).toBe(false);
    act(() => vi.advanceTimersByTime(5000));
    expect(dark()).toBe(false);
  });

  it('暗着的时候换了回合 → 立刻是亮着的终态,不补播', () => {
    vi.useFakeTimers();
    const { rerender } = render(<Harness turn={3} />);
    expect(dark()).toBe(true);
    rerender(<Harness turn={4} />);
    expect(dark()).toBe(false);
    act(() => vi.advanceTimersByTime(5000));
    expect(dark()).toBe(false);
  });

  it('减弱动效:不播,直接是亮着的终态,并立即放行正文', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const onIntroDone = vi.fn();
    render(<Harness onIntroDone={onIntroDone} />);
    expect(dark()).toBe(false);
    expect(onIntroDone).toHaveBeenCalledTimes(1);
    act(() => vi.advanceTimersByTime(5000));
    expect(dark()).toBe(false);
    expect(onIntroDone).toHaveBeenCalledTimes(1);
  });

  it('挂载时正在生成:不播,立即放行正文(照规则怪谈)', () => {
    vi.useFakeTimers();
    const onIntroDone = vi.fn();
    render(<Harness generating onIntroDone={onIntroDone} />);
    expect(dark()).toBe(false);
    expect(onIntroDone).toHaveBeenCalledTimes(1);
  });

  it('入场途中开始生成 → 照常播完(只在挂载那一刻看 generating)', () => {
    vi.useFakeTimers();
    const onIntroDone = vi.fn();
    const { rerender } = render(<Harness onIntroDone={onIntroDone} />);
    rerender(<Harness generating onIntroDone={onIntroDone} />);
    expect(dark()).toBe(true);
    act(() => vi.advanceTimersByTime(INTRO_DARK_MS + INTRO_SPREAD_MS));
    expect(dark()).toBe(false);
    expect(onIntroDone).toHaveBeenCalledTimes(1);
  });

  // StrictMode 下 effect 走 挂载 → 清理 → 再挂载,中间那次清理会 teardown 掉已排期的定时器。
  it('StrictMode 双挂载(开发模式)后仍会播完,且正文仍等入场走完', () => {
    vi.useFakeTimers();
    const onIntroDone = vi.fn();
    render(
      <StrictMode>
        <Harness onIntroDone={onIntroDone} />
      </StrictMode>,
    );
    expect(dark()).toBe(true);
    expect(onIntroDone).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(INTRO_DARK_MS));
    expect(dark()).toBe(false);
    expect(onIntroDone).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(INTRO_SPREAD_MS));
    expect(onIntroDone).toHaveBeenCalledTimes(1);
  });

  it('卸载后定时器不残留;非 debug 时不渲染重播按钮;氛围层对读屏不可见', () => {
    vi.useFakeTimers();
    const { container, unmount } = render(<Harness />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
    expect(container.querySelector(`.${styles.ambient}`)).toHaveAttribute('aria-hidden', 'true');
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
});
