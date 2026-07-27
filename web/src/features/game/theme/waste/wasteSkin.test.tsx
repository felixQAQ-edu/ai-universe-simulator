import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../../../api';
import type { AvailableAction } from '../../../../types/schema';
import { StatsPanel } from '../../StatsPanel';
import { SkinContext } from '../contract';
import { SkinRuntime } from '../lifecycle';
import { SKINS } from '../skins';
import { WasteActions } from './WasteActions';
import { WasteRadio } from './WasteRadio';

// 末日皮肤(刀 3)的测试。**双层口径(ADR-018 §5 Q8)**:共享语义 + 每形态一条特征元素冒烟。
// 电台的纯逻辑层(内容池 / 抽取约束)另见 `radio.test.ts` —— 那两层零 React、可确定性钉死。

const skin = SKINS.apocalypse!;

function withSkin(ui: React.ReactNode) {
  return render(
    <SkinContext.Provider value={{ skin, runtime: new SkinRuntime() }}>{ui}</SkinContext.Provider>,
  );
}

/** 末日两轴:体力(致命 depletion)/ 饥饿(致命 depletion,**数值越高越糟**)。 */
const HP_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '濒死', severity: 'danger' },
  { min: 21, max: 50, label: '虚弱', severity: 'caution' },
  { min: 51, max: 100, label: '尚可', severity: 'neutral' },
];
/**
 * 饥饿轴的档表(服务端派生):**低值 = 断粮 = 危险**。
 * 这张表正是「主题层不许自己判危险」的活样本 —— 样板间那版 `value < 35 ? is-low`
 * 在这里会把「饥饿 20」误报成告警,而服务端给的 severity 才是对的。
 */
const HUNGER_BANDS: AxisBand[] = [
  { min: 0, max: 20, label: '断粮', severity: 'danger' },
  { min: 21, max: 50, label: '紧缺', severity: 'caution' },
  { min: 51, max: 100, label: '充足', severity: 'neutral' },
];

describe('WasteStats(磨损机械仪表)· 共享语义', () => {
  it('数值 / 中文名 / 当前档 label 全部可读', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hp: 66 }} />);
    expect(screen.getByText('体力')).toBeInTheDocument();
    expect(screen.getByText('66')).toBeInTheDocument();
    expect(screen.getByText('尚可')).toBeInTheDocument();
    expect(screen.getByLabelText('体力 · 66 · 尚可 · 正常')).toBeInTheDocument();
  });

  it('低位 → 危险 / 中位 → 注意', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    const { unmount } = withSkin(<StatsPanel axes={axes} values={{ hp: 8 }} />);
    expect(screen.getByText('危险')).toBeInTheDocument();
    unmount();
    withSkin(<StatsPanel axes={axes} values={{ hp: 33 }} />);
    expect(screen.getByText('注意')).toBeInTheDocument();
  });

  it('★「饥饿 20」按服务端 severity 报危险,而不是按「数值小 = 安全」自行推断', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hunger', displayName: '饥饿', bands: HUNGER_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hunger: 20 }} />);
    // 样板间那版的 `value < 35` 会把这一格判成告警**并给出相反的理由**;
    // 这里的告警来自服务端派生的 severity,档名也来自服务端。
    expect(screen.getByText('断粮')).toBeInTheDocument();
    expect(screen.getByText('危险')).toBeInTheDocument();
  });

  it('高位饥饿(充足)不进告警态 —— 主题层不认识「饥饿越高越糟」这种事', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hunger', displayName: '饥饿', bands: HUNGER_BANDS }];
    withSkin(<StatsPanel axes={axes} values={{ hunger: 88 }} />);
    expect(screen.getByText('充足')).toBeInTheDocument();
    expect(screen.queryByText('危险')).not.toBeInTheDocument();
    expect(screen.queryByText('注意')).not.toBeInTheDocument();
  });

  it('无 bands / 未知 severity:安全降级(数字与名照常,不进告警态)', () => {
    const axes: AttributeAxisMeta[] = [
      { key: 'hp', displayName: '体力' },
      {
        key: 'hunger',
        displayName: '饥饿',
        bands: [{ min: 0, max: 100, label: '未知档', severity: 'meltdown' as never }],
      },
    ];
    withSkin(<StatsPanel axes={axes} values={{ hp: 5, hunger: 3 }} />);
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('未知档')).toBeInTheDocument();
    ['危险', '注意', '正常'].forEach((w) => expect(screen.queryByText(w)).not.toBeInTheDocument());
  });

  it('形态冒烟:走的确实是圆表盘(指针按角度旋转),不是竖光带、不是旧进度条', () => {
    const axes: AttributeAxisMeta[] = [{ key: 'hp', displayName: '体力', bands: HP_BANDS }];
    const { container } = withSkin(<StatsPanel axes={axes} values={{ hp: 50 }} />);
    expect(container.querySelector('[style*="rotate"]')).not.toBeNull();
    expect(container.querySelector('[style*="height"]')).toBeNull(); // 灵脉才用 height
    expect(container.querySelector('[style*="width"]')).toBeNull(); // 旧形态才用 width
  });
});

describe('WasteActions(铁皮告示)', () => {
  const actions: AvailableAction[] = [
    { id: 'A', text: '连夜赶往水厂', hint: '沙暴可能折返' },
    { id: 'B', text: '死守加油站', hint: '' },
  ];

  it('渲染正文 / hint,点击回传 id;hint 缺失不占位', () => {
    const onChoose = vi.fn();
    render(<WasteActions actions={actions} disabled={false} onChoose={onChoose} />);
    expect(screen.getByText('沙暴可能折返')).toBeInTheDocument();
    screen.getByRole('button', { name: /死守加油站/ }).click();
    expect(onChoose).toHaveBeenCalledWith('B');
  });

  it('生成中禁用(与旧决策圈同语义)', () => {
    render(<WasteActions actions={actions} disabled onChoose={vi.fn()} />);
    screen.getAllByRole('button').forEach((b) => expect(b).toBeDisabled());
  });
});

describe('WasteRadio(间奏槽 · 手摇电台)', () => {
  const mount = () =>
    render(
      <WasteRadio runtime={new SkinRuntime()} paused={false} generating={false} turn={1} />,
    );

  it('挂载即在场,静态停在「无信号」(家具先在,响不响是另一回事)', () => {
    mount();
    expect(screen.getByText('── 无信号 ──')).toBeInTheDocument();
    expect(screen.getByText('MHz')).toBeInTheDocument();
  });

  it('整段对读屏不可见 —— 电台是环境不是内容(逐字变化的频率读数对读屏是噪音)', () => {
    const { container } = mount();
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeNull();
    // 电台里没有任何参与内容语义的可访问节点
    expect(container.querySelectorAll('[aria-label]')).toHaveLength(0);
  });

  it('reduced motion:仍渲染面板,但永远停在无信号(不排期、不播)', () => {
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (q: string) => ({ matches: true, media: q }) as MediaQueryList,
    );
    mount();
    expect(screen.getByText('── 无信号 ──')).toBeInTheDocument();
    vi.restoreAllMocks();
  });
});

describe('末日皮肤登记', () => {
  it('电台挂在**间奏槽**(正文之后、决策圈之前),不是塞进三槽里的任何一个', () => {
    expect(skin.Interlude).toBe(WasteRadio);
  });

  it('**不配签名轴** —— 签名机制判的是「向上跨档 = 成就」,饥饿向下跨档是恶化,语义相反', () => {
    expect(skin.signatureAxisKey).toBeUndefined();
  });

  it('无入场序列(记忆点是电台,不是进门那一下)', () => {
    expect(skin.hasIntro).toBe(false);
  });

  it('时间感成对:末日有自己的时长与缓动(不与另两个世界共用)', () => {
    const xian = SKINS.cultivation!;
    const rules = SKINS.rules_creepy!;
    expect(skin.valueRoll.ease).not.toBe(xian.valueRoll.ease);
    expect(skin.valueRoll.ease).not.toBe(rules.valueRoll.ease);
  });
});
