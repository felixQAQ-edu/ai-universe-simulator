import { StrictMode, useRef } from 'react';
import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../../../api';
import type { AvailableAction } from '../../../../types/schema';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime, useSkinRuntime } from '../lifecycle';
import { SKINS } from '../skins';
import { RulesActions } from './RulesActions';
import { RulesAmbient } from './RulesAmbient';

// 规则怪谈皮肤(刀 1 试验田)的测试。**双层口径(ADR-018 §5 Q8)**:
//   ① 共享语义:value / displayName / 当前档 label 可读、severity 正确映射、缺省不崩;
//   ② 每形态一条特征元素存在性冒烟。
// **不断言 DOM 层数 / 像素 / 分段数量 / class 排列** —— 同 golden 哲学:守行为,不守像素。

const skin = SKINS.rules_creepy!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

// 服务端派生的档表(ADR-018 severity 契约的三个方向)。
const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '濒危', severity: 'danger' },
  { min: 21, max: 50, label: '受创', severity: 'caution' },
  { min: 51, max: 100, label: '充沛', severity: 'neutral' },
];
const REALM_BANDS: AxisBand[] = [
  { min: 0, max: 29, label: '初入', severity: 'neutral' },
  { min: 30, max: 59, label: '有成', severity: 'neutral' },
  { min: 60, max: 100, label: '通玄', severity: 'neutral' },
];
const KNOWLEDGE_BANDS: AxisBand[] = [
  { min: 0, max: 29, label: '蒙昧', severity: 'neutral' },
  { min: 30, max: 59, label: '初窥', severity: 'caution' },
  { min: 60, max: 100, label: '深陷', severity: 'danger' },
];

describe('RulesStats(OSD 形态)· 共享语义', () => {
  it('数值 / 中文名 / 当前档 label 全部可读', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 71 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('71')).toBeInTheDocument();
    expect(screen.getByText('充沛')).toBeInTheDocument();
    // 读屏一句读全(通用层产出的可访问文本)。
    expect(screen.getByLabelText('体力 · 71 · 充沛 · 正常')).toBeInTheDocument();
  });

  // 放行标准 7:severity 三方向 —— 低位危险 / 高位纯成长中性 / 高位双刃危险。
  it('致命 depletion 低位 → 危险', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '气血', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 8 }} />);
    expect(screen.getByText('危险')).toBeInTheDocument();
    expect(screen.getByLabelText('气血 · 8 · 濒危 · 危险')).toBeInTheDocument();
  });

  it('accumulation 纯成长 高位 → 仍是正常(前端不因「值高」自行推断)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'realm', displayName: '境界', bands: REALM_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ realm: 88 }} />);
    expect(screen.getByText('正常')).toBeInTheDocument();
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
  });

  it('accumulation 双刃 高位 → 危险(与纯成长同为 accumulation,结果相反)', () => {
    const axes: AttributeAxisMeta[] = [
      { key: 'knowledge', displayName: '禁忌知识', bands: KNOWLEDGE_BANDS },
    ];
    withSkin(<StatsPanel axes={axes} values={{ knowledge: 77 }} />);
    expect(screen.getByText('危险')).toBeInTheDocument();
  });

  it('中位 → 注意', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 33 }} />);
    expect(screen.getByText('注意')).toBeInTheDocument();
  });

  it('无 bands:数字与中文名照常,不显档名、不显任何风险字样(缺省不崩、不进危险态)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力' }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 5 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('05')).toBeInTheDocument(); // 监控读数补零
    ['危险', '注意', '正常'].forEach((w) =>
      expect(screen.queryByText(w)).not.toBeInTheDocument(),
    );
  });

  it('未知 severity 取值:安全降级为不显风险(绝不默认 danger)', () => {
    const axes: AttributeAxisMeta[] = [
      {
        key: 'hp',
        displayName: '体力',
        bands: [{ min: 0, max: 100, label: '未知档', severity: 'catastrophic' as never }],
      },
    ];
    withSkin(<StatsPanel axes={axes} values={{ hp: 3 }} />);
    expect(screen.getByText('未知档')).toBeInTheDocument(); // 档名照常
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
  });

  it('多轴通吃:轴集换成末日的 体力/补给 也照常渲染(对 key 无知)', () => {
    const axes: AttributeAxisMeta[] = [
      { key: 'hp', displayName: '体力', bands: HP_BANDS },
      { key: 'hunger', displayName: '补给', bands: HP_BANDS },
    ];
    withSkin(<StatsPanel axes={axes} values={{ hp: 60, hunger: 12 }} />);
    expect(screen.getByText('补给')).toBeInTheDocument();
    expect(screen.getByLabelText('补给 · 12 · 濒危 · 危险')).toBeInTheDocument();
  });

  it('形态冒烟:走的确实是监控 OSD(REC 抬头在场),不是旧进度条', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 71 }} />);
    expect(screen.getByText('REC')).toBeInTheDocument();
  });

  it('未登记皮肤(无 Provider)= 旧形态:无 REC、无补零(放行标准 3)', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    render(<StatsPanel axes={axes} values={{ hp: 5 }} />);
    expect(screen.queryByText('REC')).not.toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
  });
});

describe('RulesActions(值班登记表条目形态)', () => {
  const actions: AvailableAction[] = [
    { id: 'A', text: '用红笔划掉陌生名字', hint: '依据规则二,但你不确定它是真的' },
    { id: 'B', text: '回放走廊监控', hint: '' }, // hint 空串 = 后端「没给提示」的形态
  ];

  it('渲染编号 / 正文 / hint,点击回传 id(交互语义与通用决策圈一致)', async () => {
    const onChoose = vi.fn();
    render(<RulesActions actions={actions} disabled={false} onChoose={onChoose} />);
    expect(screen.getByText('A')).toBeInTheDocument();
    expect(screen.getByText('用红笔划掉陌生名字')).toBeInTheDocument();
    expect(screen.getByText('依据规则二,但你不确定它是真的')).toBeInTheDocument();

    screen.getByRole('button', { name: /回放走廊监控/ }).click();
    expect(onChoose).toHaveBeenCalledWith('B');
  });

  it('hint 缺失不渲染、不占位', () => {
    render(<RulesActions actions={[actions[1]]} disabled={false} onChoose={vi.fn()} />);
    expect(screen.getByText('回放走廊监控')).toBeInTheDocument();
    expect(screen.queryByText(/依据规则二/)).not.toBeInTheDocument();
  });

  it('生成中禁用(与旧决策圈同语义)', () => {
    render(<RulesActions actions={actions} disabled onChoose={vi.fn()} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });
});

describe('RulesAmbient(入场灯闪的触发条件)', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const mount = (props: {
    generating: boolean;
    setRootClass: (c: string) => void;
    onIntroDone?: () => void;
  }) => {
    const runtime = new SkinRuntime();
    // 主题根:皮肤把灯闪的连续量(亮度/色温/压暗)写成它上面的 CSS 自定义属性。
    const root = { current: document.createElement('main') } as React.RefObject<HTMLElement | null>;
    render(
      <RulesAmbient
        runtime={runtime}
        rootRef={root}
        paused={false}
        generating={props.generating}
        turn={0}
        setRootClass={props.setRootClass}
        onIntroDone={props.onIntroDone ?? (() => {})}
        signatureTick={0}
      />,
    );
    return runtime;
  };

  // ★ 刀 1 冒烟「灯闪到底有没有发生」的第二个真因(与遮挡无关):
  // StrictMode 下 effect 走 挂载 → 清理 → 再挂载,中间那次清理会 teardown 掉已排期的定时器。
  // 若守卫记的是「排过期了」,第二次挂载就直接跳过 —— 本地开发下入场序列永远不会播。
  it('StrictMode 双挂载(开发模式)后仍会播 —— 守卫记「已开演」而非「已排期」', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    // 必须用**真实接线**(useSkinRuntime + 主题根)才能复现:StrictMode 的
    // 挂载 → 清理 → 再挂载里,中间那次清理会 runtime.teardown() 清掉已排期的定时器。
    // 只 new 一个 SkinRuntime 手动传进去是复现不出来的 —— 没人在两次挂载之间 teardown。
    function Harness() {
      const runtime = useSkinRuntime('rules_creepy', 0);
      const root = useRef<HTMLElement | null>(null);
      return (
        <main ref={root}>
          <RulesAmbient
            runtime={runtime}
            rootRef={root}
            paused={false}
            generating={false}
            turn={0}
            setRootClass={setRootClass}
            onIntroDone={() => {}}
            signatureTick={0}
          />
        </main>
      );
    }
    render(
      <StrictMode>
        <Harness />
      </StrictMode>,
    );

    vi.advanceTimersByTime(1000);

    expect(setRootClass).toHaveBeenCalled();
  });

  it('入场序列播完会放行正文(串行:先环境,后叙事)', () => {
    vi.useFakeTimers();
    const onIntroDone = vi.fn();
    mount({ generating: true, setRootClass: vi.fn(), onIntroDone }); // 被抑制也必须放行
    vi.advanceTimersByTime(1000);
    expect(onIntroDone).toHaveBeenCalledTimes(1);
  });

  it('正常入场:放一次(前一拍会给主题根挂状态 class)', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    mount({ generating: false, setRootClass });

    vi.advanceTimersByTime(600);

    expect(setRootClass).toHaveBeenCalled();
  });

  // 放行标准 4
  it('生成文本期间:不触发(不排队、不补发)', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    mount({ generating: true, setRootClass });

    vi.advanceTimersByTime(5000);

    expect(setRootClass).not.toHaveBeenCalled();
  });

  // 放行标准 6
  it('reduced motion:不闪(只留静态氛围)', () => {
    vi.useFakeTimers();
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    const setRootClass = vi.fn();
    mount({ generating: false, setRootClass });

    vi.advanceTimersByTime(5000);

    expect(setRootClass).not.toHaveBeenCalled();
  });

  // 放行标准 5
  it('入场定时器未到点就换了 turn(teardown):绝不补发', () => {
    vi.useFakeTimers();
    const setRootClass = vi.fn();
    const runtime = mount({ generating: false, setRootClass });

    runtime.teardown(); // 换 turn
    vi.advanceTimersByTime(5000);

    expect(setRootClass).not.toHaveBeenCalled();
  });

  it('氛围层对读屏不可见(aria-hidden),不参与内容语义', () => {
    const { container } = render(
      <RulesAmbient
        runtime={new SkinRuntime()}
        rootRef={{ current: document.createElement('main') } as React.RefObject<HTMLElement | null>}
        paused={false}
        generating={false}
        turn={0}
        setRootClass={() => {}}
        onIntroDone={() => {}}
        signatureTick={0}
      />,
    );
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull();
  });
});
