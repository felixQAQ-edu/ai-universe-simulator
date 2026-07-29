import { StrictMode } from 'react';
import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { AttributeAxisMeta } from '../../../api';
import type { WorldSkin } from './contract';
import { useSignalCrossing } from './useSignalCrossing';

// 签名轴跨档信号(ADR-018 §5 Q5)的五条触发规矩,逐条钉住。
// 这是一级记忆点「鸣不鸣」的唯一判定口 —— 刀 1 的教训是这类一次性效果的**触发条件**
// 最难肉眼验证,所以它必须有测试,而不是靠冒烟时盯着屏幕猜。

const REALM: AttributeAxisMeta = {
  key: 'realm',
  displayName: '境界',
  bands: [
    { min: 0, max: 30, label: '初境', severity: 'neutral' },
    { min: 31, max: 60, label: '小成', severity: 'neutral' },
    { min: 61, max: 100, label: '高深', severity: 'neutral' },
  ],
};
const HP: AttributeAxisMeta = {
  key: 'hp',
  displayName: '气血',
  bands: [
    { min: 0, max: 20, label: '枯竭', severity: 'danger' },
    { min: 21, max: 50, label: '亏损', severity: 'caution' },
    { min: 51, max: 100, label: '充盈', severity: 'neutral' },
  ],
};

/** 只用到 signatureAxisKey 的替身皮肤(形态组件与本判定无关)。 */
const skinWith = (signatureAxisKey?: string) => ({ signatureAxisKey }) as WorldSkin;

/** 挂一个只读信号的探针组件,按序喂值,收集每次渲染后的 tick。 */
function harness(
  skin: WorldSkin | null,
  axes: AttributeAxisMeta[],
  strict = false,
): { push: (values: Record<string, number>) => number } {
  let latest = 0;
  function Probe({ values }: { values: Record<string, number> }) {
    latest = useSignalCrossing(skin, axes, values).tick;
    return null;
  }
  const wrap = (values: Record<string, number>) =>
    strict ? (
      <StrictMode>
        <Probe values={values} />
      </StrictMode>
    ) : (
      <Probe values={values} />
    );

  let rerender: ((ui: React.ReactNode) => void) | null = null;
  return {
    push(values) {
      if (!rerender) {
        rerender = render(wrap(values)).rerender;
      } else {
        rerender(wrap(values));
      }
      return latest;
    },
  };
}

describe('useSignalCrossing(签名轴跨档)', () => {
  it('向上跨档 → tick 加一', () => {
    const h = harness(skinWith('realm'), [REALM]);
    expect(h.push({ realm: 28 })).toBe(0); // 首次装载只记前值
    expect(h.push({ realm: 35 })).toBe(1); // 初境 → 小成
  });

  it('首次装载(init / resume)不触发 —— 哪怕一上来就在高档', () => {
    const h = harness(skinWith('realm'), [REALM]);
    expect(h.push({ realm: 92 })).toBe(0);
  });

  it('同档内涨跌不触发', () => {
    const h = harness(skinWith('realm'), [REALM]);
    h.push({ realm: 35 });
    expect(h.push({ realm: 58 })).toBe(0);
  });

  it('下跌不鸣(向下跨档只更新前值)', () => {
    const h = harness(skinWith('realm'), [REALM]);
    h.push({ realm: 70 });
    expect(h.push({ realm: 20 })).toBe(0);
    // 而且跌下去之后再涨回来,是一次**新的**向上跨档,该鸣。
    expect(h.push({ realm: 70 })).toBe(1);
  });

  it('一次跨多档只鸣一次', () => {
    const h = harness(skinWith('realm'), [REALM]);
    h.push({ realm: 5 });
    expect(h.push({ realm: 95 })).toBe(1); // 初境 → 高深,跨两档
  });

  it('连续多回合各跨一档 → 各鸣一次', () => {
    const h = harness(skinWith('realm'), [REALM]);
    h.push({ realm: 10 });
    expect(h.push({ realm: 40 })).toBe(1);
    expect(h.push({ realm: 80 })).toBe(2);
  });

  it('只盯登记的那根轴:别的轴跨档不鸣', () => {
    const h = harness(skinWith('realm'), [REALM, HP]);
    h.push({ realm: 40, hp: 90 });
    expect(h.push({ realm: 40, hp: 10 })).toBe(0); // 气血从充盈跌到枯竭,与钟鸣无关
  });

  it('轴没有 bands → 静默降级、恒不触发', () => {
    const noBands: AttributeAxisMeta = { key: 'realm', displayName: '境界' };
    const h = harness(skinWith('realm'), [noBands]);
    h.push({ realm: 5 });
    expect(h.push({ realm: 95 })).toBe(0);
  });

  it('皮肤未配置签名轴 / 未登记皮肤 → 恒 0', () => {
    const a = harness(skinWith(undefined), [REALM]);
    a.push({ realm: 5 });
    expect(a.push({ realm: 95 })).toBe(0);

    const b = harness(null, [REALM]);
    b.push({ realm: 5 });
    expect(b.push({ realm: 95 })).toBe(0);
  });

  // ★ ADR-018 §4.9 那一类坑的近亲:渲染期直接写 ref 会被 StrictMode 的双调用吃掉跨档。
  // 这里用官方「据 props 变化调整 state」模式,故双调用下结论不变(且不会重复加一)。
  it('StrictMode 双调用下:跨档照样只算一次,不丢也不重', () => {
    const h = harness(skinWith('realm'), [REALM], true);
    expect(h.push({ realm: 28 })).toBe(0);
    expect(h.push({ realm: 35 })).toBe(1);
    expect(h.push({ realm: 35 })).toBe(1); // 同值重渲不再加
  });

  // ── 签名轴 severity(刀 4 扩点)────────────────────────────────────────
  // 克苏鲁的记忆点不是「跨档演一次」而是「随状态改频率」,需要当前档的**语义**。
  // 主题层不许自己按 key / 数值高低推断(§4.2),故通用层从同一根轴上一并读出来。
  describe('签名轴 severity(随状态调频率的记忆点用)', () => {
    const signalOf = (skin: WorldSkin | null, axes: AttributeAxisMeta[], values: Record<string, number>) => {
      let out: ReturnType<typeof useSignalCrossing> | null = null;
      function Probe() {
        out = useSignalCrossing(skin, axes, values);
        return null;
      }
      render(<Probe />);
      return out!;
    };

    it('交出签名轴当前档的 severity —— 且随值切换', () => {
      expect(signalOf(skinWith('hp'), [HP], { hp: 90 }).severity).toBe('neutral');
      expect(signalOf(skinWith('hp'), [HP], { hp: 35 }).severity).toBe('caution');
      expect(signalOf(skinWith('hp'), [HP], { hp: 8 }).severity).toBe('danger');
    });

    it('只读签名轴那一根:别的轴再危险也不算', () => {
      expect(signalOf(skinWith('realm'), [REALM, HP], { realm: 50, hp: 3 }).severity).toBe('neutral');
    });

    it('四种缺省一律 null(未配签名轴 / 无档表 / 未登记皮肤 / 轴不在场)——绝不默认 danger', () => {
      const noBands: AttributeAxisMeta = { key: 'hp', displayName: '气血' };
      expect(signalOf(skinWith(undefined), [HP], { hp: 3 }).severity).toBeNull();
      expect(signalOf(skinWith('hp'), [noBands], { hp: 3 }).severity).toBeNull();
      expect(signalOf(null, [HP], { hp: 3 }).severity).toBeNull();
      expect(signalOf(skinWith('san'), [HP], { hp: 3 }).severity).toBeNull();
    });

    it('severity 跟目标值走,与「跨没跨档」互不推导', () => {
      // 首次装载:tick 恒 0(没跨档),但 severity 立刻可用 —— 两者刻意不互相依赖。
      const s = signalOf(skinWith('hp'), [HP], { hp: 5 });
      expect(s.tick).toBe(0);
      expect(s.severity).toBe('danger');
    });
  });

  it('换局(轴集变了)重新观察:不拿上一局的档序号比', () => {
    // 同一个探针实例先玩修仙(realm 高档),再换成一个只有 hp 的世界,再换回来 —— 不该鸣。
    let latest = 0;
    function Probe({ axes, values }: { axes: AttributeAxisMeta[]; values: Record<string, number> }) {
      latest = useSignalCrossing(skinWith('realm'), axes, values).tick;
      return null;
    }
    const { rerender } = render(<Probe axes={[REALM]} values={{ realm: 10 }} />);
    rerender(<Probe axes={[HP]} values={{ hp: 80 }} />); // 换局:签名轴不在了
    rerender(<Probe axes={[REALM]} values={{ realm: 95 }} />); // 新一局一上来就在高档
    expect(latest).toBe(0);
  });
});
