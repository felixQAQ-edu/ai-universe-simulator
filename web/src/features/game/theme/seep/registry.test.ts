import { describe, expect, it } from 'vitest';
import type { AttributeAxisMeta, AxisBand } from '../../../../api';
import { resolveWorldTheme } from '../registry';
import { SKINS } from '../skins';
import {
  LOW_FREQ_EMPTY_HOSTS,
  SEEPS,
  resolveSeep,
  seepSignals,
  stainLevelSpec,
  type SeepId,
} from './registry';

// ADR-043 决策 9 的登记表测试:登记项自洽、与宿主皮肤对得上;没登记的组合与单体世界解析结果不多一个键。

const FUSION_KEYS: [string, string][] = [
  ['cultivation', 'rules_creepy'],
  ['rules_creepy', 'apocalypse'],
  ['cultivation', 'cthulhu'],
];

describe('渗入登记表 · 自洽', () => {
  const entries = Object.values(SEEPS);

  it('至少登记了《验讫》,且 id 与键一致', () => {
    expect(SEEPS.yanqi?.id).toBe('yanqi');
    for (const [k, e] of Object.entries(SEEPS)) expect(e!.id).toBe(k);
  });

  it('宿主皮肤存在于 SKINS', () => {
    for (const e of entries) expect(SKINS[e!.hostSkin]).toBeDefined();
  });

  it('登了压印的宿主声明了 signatureAccessory(否则通用层永不交挂件,印无处可挂)', () => {
    for (const e of entries) {
      if (e!.stamp) expect(SKINS[e!.hostSkin]!.signatureAccessory, e!.id).toBe(true);
    }
  });

  it('登了字洇 / 正文墨渍的宿主没有 Prose 槽;登了字洇的宿主低频槽为空', () => {
    for (const e of entries) {
      const prose = !!e!.bleed || !!e!.stain?.levels.some((l) => l.corners.some((c) => c.surface === 'prose'));
      if (prose) expect(SKINS[e!.hostSkin]!.Prose).toBeUndefined();
      if (e!.bleed) expect(LOW_FREQ_EMPTY_HOSTS.has(e!.hostSkin)).toBe(true);
    }
  });

  it('墨洇:档非空、不透明度在 [0, 1]、尺寸为正', () => {
    for (const e of entries) {
      const s = e!.stain;
      if (!s) continue;
      expect(s.levels.length).toBeGreaterThan(0);
      // 圆角 = 外扩:挖空恰是行框的 holePadPx 邻域(裁定 5);圆角大于外扩会让四角露出行框外扩区
      expect(s.holeRadiusPx, e!.id).toBe(s.holePadPx);
      for (const l of s.levels) {
        for (const o of [l.fillOpacity, l.strokeOpacity]) {
          expect(o).toBeGreaterThanOrEqual(0);
          expect(o).toBeLessThanOrEqual(1);
        }
        for (const c of l.corners) {
          expect(c.w).toBeGreaterThan(0);
          expect(c.h).toBeGreaterThan(0);
        }
      }
    }
  });

  it('字洇:每档截断区间合法(min ≤ mean ≤ max)', () => {
    for (const e of entries) {
      for (const w of Object.values(e!.bleed?.schedule ?? {})) {
        expect(w!.minMs).toBeLessThanOrEqual(w!.meanMs);
        expect(w!.meanMs).toBeLessThanOrEqual(w!.maxMs);
      }
    }
  });

  it('每个融合组合登记的 seep 都在 SEEPS 里,且宿主对得上', () => {
    for (const [h, f] of FUSION_KEYS) {
      const t = resolveWorldTheme([h, f]);
      if (t.seep) expect(resolveSeep(t.seep, t.skin)).not.toBeNull();
    }
  });
});

describe('渗入登记 · 解析(没登记 = 不出现这个键)', () => {
  it('《验讫》带 seep: yanqi,宿主皮肤仍是修仙', () => {
    const t = resolveWorldTheme(['cultivation', 'cthulhu']);
    expect(t.seep).toBe('yanqi');
    expect(t.skin).toBe('cultivation');
  });

  it('识海遗蜕、人防工程、全部单体世界:解析结果里没有 seep 键', () => {
    expect('seep' in resolveWorldTheme(['cultivation', 'rules_creepy'])).toBe(false);
    expect('seep' in resolveWorldTheme(['rules_creepy', 'apocalypse'])).toBe(false);
    for (const id of ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation', 'cyberpunk', 'time_travel', 'animal_life', 'life_sim']) {
      expect('seep' in resolveWorldTheme(id)).toBe(false);
    }
    // 未登记的反向组合回落 host 单体,同样不带
    expect('seep' in resolveWorldTheme(['cthulhu', 'cultivation'])).toBe(false);
  });

  it('宿主对不上 → null(只降级,不崩)', () => {
    expect(resolveSeep('yanqi', 'cultivation')).toBe(SEEPS.yanqi);
    expect(resolveSeep('yanqi', 'cthulhu')).toBeNull();
    expect(resolveSeep('yanqi', null)).toBeNull();
    expect(resolveSeep(undefined, 'cultivation')).toBeNull();
    expect(resolveSeep('nope' as SeepId, 'cultivation')).toBeNull();
  });
});

describe('seepSignals · 通用层按登记的 key 算档序号与 severity', () => {
  const KNOWLEDGE: AxisBand[] = [
    { min: 0, max: 30, label: '未觉', severity: 'neutral' },
    { min: 31, max: 60, label: '见格', severity: 'caution' },
    { min: 61, max: 100, label: '知渔', severity: 'danger' },
  ];
  const SAN: AxisBand[] = [
    { min: 0, max: 20, label: '将失', severity: 'danger' },
    { min: 21, max: 50, label: '起雾', severity: 'caution' },
    { min: 51, max: 100, label: '澄澈', severity: 'neutral' },
  ];
  const AXES: AttributeAxisMeta[] = [
    { key: 'realm', displayName: '境界' },
    { key: 'san', displayName: '清明', bands: SAN },
    { key: 'knowledge', displayName: '窥天', bands: KNOWLEDGE },
  ];
  const yanqi = SEEPS.yanqi!;

  it('窥天 12 / 41 / 76 → 档序号 0 / 1 / 2', () => {
    expect(seepSignals(yanqi, AXES, { knowledge: 12, san: 80 }).stainLevel).toBe(0);
    expect(seepSignals(yanqi, AXES, { knowledge: 41, san: 80 }).stainLevel).toBe(1);
    expect(seepSignals(yanqi, AXES, { knowledge: 76, san: 80 }).stainLevel).toBe(2);
  });

  it('清明 80 / 44 / 10 → neutral / caution / danger(服务端派生,只读)', () => {
    expect(seepSignals(yanqi, AXES, { san: 80 }).bleedSeverity).toBe('neutral');
    expect(seepSignals(yanqi, AXES, { san: 44 }).bleedSeverity).toBe('caution');
    expect(seepSignals(yanqi, AXES, { san: 10 }).bleedSeverity).toBe('danger');
  });

  it('没登记 / 找不到轴 / 无档表 → null', () => {
    expect(seepSignals(null, AXES, { knowledge: 50 })).toEqual({ stainLevel: null, bleedSeverity: null });
    const bare: AttributeAxisMeta[] = [{ key: 'knowledge', displayName: '窥天' }];
    expect(seepSignals(yanqi, bare, { knowledge: 50 })).toEqual({ stainLevel: null, bleedSeverity: null });
  });

  it('档参数:超出取最后一档,null 不画', () => {
    const s = yanqi.stain!;
    expect(stainLevelSpec(s, 0)!.fillOpacity).toBe(0.1);
    expect(stainLevelSpec(s, 2)!.fillOpacity).toBe(0.21);
    expect(stainLevelSpec(s, 9)!.fillOpacity).toBe(0.21);
    expect(stainLevelSpec(s, null)).toBeNull();
  });
});
