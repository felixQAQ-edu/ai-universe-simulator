import { StrictMode, act, useRef, useState } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../../../api';
import type { AvailableAction } from '../../../../types/schema';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime, useSkinRuntime } from '../lifecycle';
import { SKINS } from '../skins';
import { XianActions } from './XianActions';
import { XianAmbient } from './XianAmbient';

// 修仙皮肤(刀 2)的测试。**双层口径(ADR-018 §5 Q8)**:
//   ① 共享语义:value / displayName / 当前档 label 可读、severity 正确映射、缺省不崩;
//   ② 每形态一条特征元素存在性冒烟 + 钟鸣的触发条件。
// **不断言 DOM 层数 / 像素 / 光带高度 / class 排列** —— 同 golden 哲学:守行为,不守像素。

const skin = SKINS.cultivation!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

/** 修仙三轴的服务端派生档表(致命 depletion / 非致命资源 / 纯成长 accumulation)。 */
const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '气血枯竭', severity: 'danger' },
  { min: 21, max: 50, label: '气血亏损', severity: 'caution' },
  { min: 51, max: 100, label: '气血充盈', severity: 'neutral' },
];
const MANA_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '灵力枯竭', severity: 'neutral' },
  { min: 21, max: 50, label: '灵力见底', severity: 'neutral' },
  { min: 51, max: 100, label: '灵力充裕', severity: 'neutral' },
];
const REALM_BANDS: AxisBand[] = [
  { min: 0, max: 30, label: '初境', severity: 'neutral' },
  { min: 31, max: 60, label: '小成', severity: 'neutral' },
  { min: 61, max: 100, label: '高深', severity: 'neutral' },
];

describe('XianStats(灵脉形态)· 共享语义', () => {
  it('数值 / 中文名 / 当前档 label 全部可读', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 86 }} />);
    expect(screen.getByText('气血')).toBeInTheDocument();
    expect(screen.getByText('86')).toBeInTheDocument();
    expect(screen.getByText('气血充盈')).toBeInTheDocument();
    expect(screen.getByLabelText('气血 · 86 · 气血充盈 · 正常')).toBeInTheDocument();
  });

  it('致命 depletion 低位 → 危险(风险字样露面)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 8 }} />);
    expect(screen.getByText('危险')).toBeInTheDocument();
    expect(screen.getByLabelText('气血 · 8 · 气血枯竭 · 危险')).toBeInTheDocument();
  });

  it('非致命资源轴归零 → 仍是常态(灵力枯竭是力竭,不是危险;前端不按数值高低自行推断)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'mana', displayName: '灵力', bands: MANA_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ mana: 0 }} />);
    expect(screen.getByText('灵力枯竭')).toBeInTheDocument(); // 档名照显
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
    expect(screen.queryByText('注意')).not.toBeInTheDocument();
  });

  it('纯成长 accumulation 高位 → 仍是常态(境界越高越好)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'realm', displayName: '境界', bands: REALM_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ realm: 92 }} />);
    expect(screen.getByText('高深')).toBeInTheDocument();
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
  });

  it('中位 → 注意', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 33 }} />);
    expect(screen.getByText('注意')).toBeInTheDocument();
  });

  it('无 bands:数字与中文名照常,不显档名、不显任何风险字样(缺省不崩、不进危险态)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血' }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 5 }} />);
    expect(screen.getByText('气血')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    ['危险', '注意', '正常'].forEach((w) => expect(screen.queryByText(w)).not.toBeInTheDocument());
  });

  it('未知 severity 取值:安全降级为不显风险(绝不默认 danger)', () => {
    const axes: AttributeAxisMeta[] = [
      {
        key: 'hp',
        displayName: '气血',
        bands: [{ min: 0, max: 100, label: '未知档', severity: 'catastrophic' as never }],
      },
    ];
    withSkin(<StatsPanel axes={axes} values={{ hp: 3 }} />);
    expect(screen.getByText('未知档')).toBeInTheDocument();
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
  });

  it('多轴通吃:融合局(识海遗蜕)四轴照常渲染,含换皮轴「道心」', () => {
    const axes: AttributeAxisMeta[] = [
      { key: 'hp', displayName: '气血', bands: HP_BANDS },
      { key: 'mana', displayName: '灵力', bands: MANA_BANDS },
      { key: 'realm', displayName: '境界', bands: REALM_BANDS },
      { key: 'san', displayName: '道心', bands: HP_BANDS },
    ];
    withSkin(
      <StatsPanel axes={axes} values={{ hp: 70, mana: 40, realm: 45, san: 12 }} />,
    );
    expect(screen.getByText('道心')).toBeInTheDocument();
    // 换皮轴的 severity 自动等同来源单体(AxisSkin 不换 lethal/axisRole/perilAtHigh)。
    expect(screen.getByLabelText('道心 · 12 · 气血枯竭 · 危险')).toBeInTheDocument();
  });

  it('形态冒烟:走的确实是灵脉竖向光带,不是旧进度条', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    const { container } = withSkin(<StatsPanel axes={axes} values={{ hp: 86 }} />);
    // 特征:光带高度按百分比(竖向),而不是旧形态的宽度(横向)。
    const fill = container.querySelector('[style*="height"]');
    expect(fill).not.toBeNull();
    expect(container.querySelector('[style*="width"]')).toBeNull();
  });

  it('未登记皮肤(无 Provider)= 旧形态:横向进度条(宽度)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    const { container } = render(<StatsPanel axes={axes} values={{ hp: 86 }} />);
    expect(container.querySelector('[style*="width"]')).not.toBeNull();
  });
});

// 刀 2.5:签名轴换形态(境界印)。**分派只认通用层打的 `signature` 标**——
// 主题层若改用 `axis.key === 'realm'`,下面这组用例里「换个 key 当签名轴」那条会立刻变红。
describe('RealmSeal(境界印)· 签名轴形态分派', () => {
  const THREE: AttributeAxisMeta[] = [
    { key: 'hp', displayName: '气血', bands: HP_BANDS },
    { key: 'mana', displayName: '灵力', bands: MANA_BANDS },
    { key: 'realm', displayName: '境界', bands: REALM_BANDS },
  ];

  it('签名轴走印:中央是档名,数字降为次级(两者都在,层级由形态给)', () => {
    withSkin(<StatsPanel axes={THREE} values={{ hp: 86, mana: 64, realm: 20 }} />);
    // 印上的档名与数字都可读(不断言字号/层数,守「行为不守像素」)
    expect(screen.getByText('初境')).toBeInTheDocument();
    expect(screen.getByText('20')).toBeInTheDocument();
    expect(screen.getByLabelText('境界 · 20 · 初境 · 正常')).toBeInTheDocument();
  });

  it('非签名轴仍走灵脉:印**只有一个**', () => {
    const { container } = withSkin(
      <StatsPanel axes={THREE} values={{ hp: 86, mana: 64, realm: 20 }} />,
    );
    expect(container.querySelectorAll('[class*="seal"]').length).toBeGreaterThan(0);
    // 印是整块(含内部填充与文字容器),但**顶层印**只该有一个:按 aria-label 数更稳。
    const labelled = [...container.querySelectorAll('[aria-label]')];
    expect(labelled).toHaveLength(3); // 三根轴各一个可访问节点
  });

  it('皮肤未登记签名轴 → 全部整齐走灵脉(优雅降级,不是半印半脉)', () => {
    const noSignature = { ...skin, signatureAxisKey: undefined };
    const { container } = render(
      <SkinContext.Provider value={{ skin: noSignature, runtime: new SkinRuntime() }}>
        <StatsPanel axes={THREE} values={{ hp: 86, mana: 64, realm: 20 }} />
      </SkinContext.Provider>,
    );
    expect(container.querySelector('[class*="seal"]')).toBeNull();
    expect(screen.getByText('初境')).toBeInTheDocument(); // 档名照常(只是回到灵脉形态)
  });

  it('换一根轴当签名轴,印就跟着换 —— 分派认标不认 key', () => {
    const hpSignature = { ...skin, signatureAxisKey: 'hp' };
    const { container } = render(
      <SkinContext.Provider value={{ skin: hpSignature, runtime: new SkinRuntime() }}>
        <StatsPanel axes={THREE} values={{ hp: 86, mana: 64, realm: 20 }} />
      </SkinContext.Provider>,
    );
    const seal = container.querySelector('[class*="seal"]');
    expect(seal).not.toBeNull();
    expect(seal!.getAttribute('aria-label')).toContain('气血'); // 印落在气血上,不是境界
  });

  it('印同样消费 severity(不因「签名轴」就假定安全)', () => {
    const hpSignature = { ...skin, signatureAxisKey: 'hp' };
    render(
      <SkinContext.Provider value={{ skin: hpSignature, runtime: new SkinRuntime() }}>
        <StatsPanel axes={THREE} values={{ hp: 8, mana: 64, realm: 20 }} />
      </SkinContext.Provider>,
    );
    expect(screen.getByText('危险')).toBeInTheDocument();
    expect(screen.getByLabelText('气血 · 8 · 气血枯竭 · 危险')).toBeInTheDocument();
  });

  it('融合局四轴:一印 + 三灵脉,换皮轴「道心」照常在', () => {
    const four: AttributeAxisMeta[] = [
      ...THREE,
      { key: 'san', displayName: '道心', bands: HP_BANDS },
    ];
    const { container } = withSkin(
      <StatsPanel axes={four} values={{ hp: 86, mana: 64, realm: 20, san: 72 }} />,
    );
    expect([...container.querySelectorAll('[aria-label]')]).toHaveLength(4);
    expect(screen.getByText('道心')).toBeInTheDocument();
  });
});

describe('XianActions(玉简签形态)', () => {
  const actions: AvailableAction[] = [
    { id: 'A', text: '闭关冲击炼气八层', hint: '一日不成,道心受损' },
    { id: 'B', text: '静坐炼气,任机缘擦身而过', hint: '' },
  ];

  it('渲染正文 / hint,点击回传 id(交互语义与通用决策圈一致)', () => {
    const onChoose = vi.fn();
    render(<XianActions actions={actions} disabled={false} onChoose={onChoose} />);
    expect(screen.getByText('闭关冲击炼气八层')).toBeInTheDocument();
    expect(screen.getByText('一日不成,道心受损')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /静坐炼气/ }));
    expect(onChoose).toHaveBeenCalledWith('B');
  });

  it('整张签都是按钮本体:hint 小字上的点击照样回传(不是只有正文可点)', () => {
    const onChoose = vi.fn();
    render(<XianActions actions={actions} disabled={false} onChoose={onChoose} />);
    // 点在 hint 这个子元素上 —— 事件冒泡到签本体。
    fireEvent.click(screen.getByText('一日不成,道心受损'));
    expect(onChoose).toHaveBeenCalledWith('A');
  });

  it('hint 缺失不渲染、不占位', () => {
    render(<XianActions actions={[actions[1]]} disabled={false} onChoose={vi.fn()} />);
    expect(screen.queryByText(/道心受损/)).not.toBeInTheDocument();
  });

  it('生成中禁用(与旧决策圈同语义)', () => {
    render(<XianActions actions={actions} disabled onChoose={vi.fn()} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });

  it('墨色确认:按下即上墨,约 480ms 后退去(输入已接收的即时反馈)', () => {
    vi.useFakeTimers();
    try {
      const { container } = render(
        <XianActions actions={actions} disabled={false} onChoose={vi.fn()} />,
      );
      const before = container.innerHTML;
      fireEvent.click(screen.getByRole('button', { name: /闭关冲击/ }));
      expect(container.innerHTML).not.toBe(before); // 上墨(多了一个状态 class)
      act(() => vi.advanceTimersByTime(600));
      expect(container.innerHTML).toBe(before); // 退墨,回到原样
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('XianAmbient(钟鸣的触发条件)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  /** 真实接线:runtime 由 useSkinRuntime 给(turn 变即 teardown),主题根是真的 DOM 节点。 */
  function Harness({
    tick,
    turn = 1,
    setRootClass,
  }: {
    tick: number;
    turn?: number;
    setRootClass: (c: string) => void;
  }) {
    const runtime = useSkinRuntime('cultivation', turn);
    const root = useRef<HTMLElement | null>(null);
    return (
      <main ref={root}>
        <XianAmbient
          runtime={runtime}
          rootRef={root}
          paused={false}
          generating={false}
          turn={turn}
          setRootClass={setRootClass}
          onIntroDone={() => {}}
          signatureTick={tick}
        />
      </main>
    );
  }

  it('首次装载(tick=0)不鸣 —— init / resume 绝不误触发', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    render(<Harness tick={0} setRootClass={setRootClass} />);
    act(() => vi.advanceTimersByTime(3000));
    expect(setRootClass).not.toHaveBeenCalled();
  });

  it('跨档(tick 加一)→ 收住一拍后开演', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    const { rerender } = render(<Harness tick={0} setRootClass={setRootClass} />);
    rerender(<Harness tick={1} setRootClass={setRootClass} />);

    act(() => vi.advanceTimersByTime(200));
    expect(setRootClass).not.toHaveBeenCalled(); // 还没到「环境收住」那一拍
    act(() => vi.advanceTimersByTime(200));
    expect(setRootClass).toHaveBeenCalled(); // +300ms 收住
  });

  // ★ ADR-018 §4.9 的守卫口径:StrictMode 双挂载后仍要能演(记「已开演」不记「已排期」)。
  // 钟鸣的守卫天然是 tick(外部单调量),此处钉住它不因双挂载而丢演出。
  it('StrictMode 双挂载后仍会演', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    const { rerender } = render(
      <StrictMode>
        <Harness tick={0} setRootClass={setRootClass} />
      </StrictMode>,
    );
    rerender(
      <StrictMode>
        <Harness tick={1} setRootClass={setRootClass} />
      </StrictMode>,
    );
    act(() => vi.advanceTimersByTime(600));
    expect(setRootClass).toHaveBeenCalled();
  });

  it('reduced motion:不鸣(静态氛围照旧)', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const setRootClass = vi.fn();
    const { rerender } = render(<Harness tick={0} setRootClass={setRootClass} />);
    rerender(<Harness tick={1} setRootClass={setRootClass} />);
    act(() => vi.advanceTimersByTime(5000));
    expect(setRootClass).not.toHaveBeenCalled();
  });

  it('连点不堆叠:明显阶段内重复触发被忙态锁挡下,只演一轮', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    const { rerender } = render(<Harness tick={0} setRootClass={setRootClass} />);
    rerender(<Harness tick={1} setRootClass={setRootClass} />);
    act(() => vi.advanceTimersByTime(400)); // 已收住
    const afterFirst = setRootClass.mock.calls.length;

    rerender(<Harness tick={2} setRootClass={setRootClass} />); // 仪式未收尾就再来一次
    act(() => vi.advanceTimersByTime(400));
    // 第二次被 busy 挡下:除了第一轮自己的 t=550ms 解除收住,不该再多出一轮「收住」。
    expect(setRootClass.mock.calls.filter(([c]) => c !== '').length).toBe(
      setRootClass.mock.calls.slice(0, afterFirst).filter(([c]) => c !== '').length,
    );
  });

  it('演到一半换了 turn(teardown):不补播、状态 class 收回', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    const { rerender } = render(<Harness tick={0} turn={1} setRootClass={setRootClass} />);
    rerender(<Harness tick={1} turn={1} setRootClass={setRootClass} />);
    act(() => vi.advanceTimersByTime(400)); // 收住中
    setRootClass.mockClear();

    rerender(<Harness tick={1} turn={2} setRootClass={setRootClass} />); // 新回合到达 → teardown
    expect(setRootClass).toHaveBeenCalledWith(''); // 状态收回
    setRootClass.mockClear();
    act(() => vi.advanceTimersByTime(5000));
    expect(setRootClass).not.toHaveBeenCalled(); // 旧仪式的后续拍子不补发
  });

  it('氛围层对读屏不可见(aria-hidden),不参与内容语义', () => {
    const { container } = render(<Harness tick={0} setRootClass={() => {}} />);
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull();
  });
});

describe('修仙皮肤登记', () => {
  it('钟鸣盯的轴锁在注册表内(ADR-018 §5 Q5),通用数值组件不认识它', () => {
    expect(skin.signatureAxisKey).toBe('realm');
  });

  it('仪式延迟由皮肤给时间感、通用层施加(跨档那一回合数值后动)', () => {
    expect(skin.valueRoll.ceremonyDelayMs).toBeGreaterThan(0);
  });

  it('钟鸣不是入场序列:不占开场,正文照常立即逐字', () => {
    expect(skin.hasIntro).toBe(false);
  });

  it('reduced-motion 下数值不被仪式延迟按住:目标值同帧到位', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const axes: AttributeAxisMeta[] = [{ key: 'realm', displayName: '境界', bands: REALM_BANDS }];
    function Probe() {
      const [v, setV] = useState(20);
      return (
        <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>
          <button type="button" onClick={() => setV(70)}>
            升
          </button>
          <StatsPanel axes={axes} values={{ realm: v }} signatureTick={v === 20 ? 0 : 1} />
        </SkinContext.Provider>
      );
    }
    render(<Probe />);
    fireEvent.click(screen.getByRole('button', { name: '升' }));
    expect(screen.getByText('70')).toBeInTheDocument(); // 不插值、不延迟,直接落位
    vi.restoreAllMocks();
  });
});
