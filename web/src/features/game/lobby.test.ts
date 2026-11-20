// ADR-041 决策 2:三区大厅的纯函数。
import { describe, expect, it } from 'vitest';
import type { FusionCombo } from '../../api';
import type { SaveSummary } from '../../state/savePointers';
import { discoveredFusions, hasUndiscovered, initialZone, zoneOfWorld } from './lobby';

const FUSIONS: FusionCombo[] = [
  { host: 'cultivation', foreign: 'cthulhu', key: 'cultivation×cthulhu' },
  { host: 'cultivation', foreign: 'rules_creepy', key: 'cultivation×rules_creepy' },
  { host: 'rules_creepy', foreign: 'apocalypse', key: 'rules_creepy×apocalypse' },
];
const all = () => true;

const save = (saveId: string, archetypes: string[]): SaveSummary =>
  ({
    saveId,
    title: saveId,
    archetypes,
    turn: 3,
    ended: false,
    lastPlayedAt: 1,
    availability: 'available',
  }) as SaveSummary;

describe('zoneOfWorld', () => {
  it('fushi → 浮世;shenjie → 深界', () => {
    expect(zoneOfWorld('fushi')).toBe('fushi');
    expect(zoneOfWorld('shenjie')).toBe('shen');
  });
});

describe('discoveredFusions:浏览器记录 ∪ 存档推导,与投影 / 卡文案取交集', () => {
  it('什么都没有 → 空', () => {
    expect(discoveredFusions({ stored: [], saves: [], fusions: FUSIONS, hasCard: all })).toEqual([]);
  });

  it('存档里的融合局(有序对)算已发现;单体存档与旧指针(空数组)不算', () => {
    const saves = [save('a', ['cultivation', 'cthulhu']), save('b', ['life_sim']), save('c', [])];
    expect(discoveredFusions({ stored: [], saves, fusions: FUSIONS, hasCard: all })).toEqual(['cultivation×cthulhu']);
  });

  it('方向敏感:反过来的有序对不是登记的组合 → 不算', () => {
    const saves = [save('a', ['cthulhu', 'cultivation'])];
    expect(discoveredFusions({ stored: [], saves, fusions: FUSIONS, hasCard: all })).toEqual([]);
  });

  it('浏览器记录在前(发现先后),存档推导接在后面;不重复', () => {
    const saves = [save('a', ['rules_creepy', 'apocalypse']), save('b', ['cultivation', 'cthulhu'])];
    const stored = ['cultivation×cthulhu', 'cultivation×rules_creepy'];
    expect(discoveredFusions({ stored, saves, fusions: FUSIONS, hasCard: all })).toEqual([
      'cultivation×cthulhu',
      'cultivation×rules_creepy',
      'rules_creepy×apocalypse',
    ]);
  });

  it('后端已撤的组合、前端没有卡文案的组合 → 不出现', () => {
    const stored = ['cultivation×cthulhu', 'gone×x', 'rules_creepy×apocalypse'];
    const hasCard = (k: string) => k !== 'rules_creepy×apocalypse';
    expect(discoveredFusions({ stored, saves: [], fusions: FUSIONS, hasCard })).toEqual(['cultivation×cthulhu']);
  });
});

describe('hasUndiscovered(渗界底句,审定 6)', () => {
  it('还有没发现的 → true;三个都发现了 → false', () => {
    expect(hasUndiscovered(FUSIONS, ['cultivation×cthulhu'], all)).toBe(true);
    expect(hasUndiscovered(FUSIONS, FUSIONS.map((f) => f.key), all)).toBe(false);
  });
  it('没有卡文案的组合不算「还没发现」(玩家本来就拿不到它)', () => {
    const hasCard = (k: string) => k === 'cultivation×cthulhu';
    expect(hasUndiscovered(FUSIONS, ['cultivation×cthulhu'], hasCard)).toBe(false);
  });
});

describe('initialZone(审定 4)', () => {
  it('没有记录 → 浮世', () => {
    expect(initialZone(null, 0)).toBe('fushi');
    expect(initialZone(null, 2)).toBe('fushi');
  });
  it('记的是浮世 / 深界 → 照用', () => {
    expect(initialZone('shen', 0)).toBe('shen');
    expect(initialZone('fushi', 3)).toBe('fushi');
  });
  it('记的是渗界:有已发现 → 渗界;已发现为空(比如被清了)→ 浮世', () => {
    expect(initialZone('rong', 1)).toBe('rong');
    expect(initialZone('rong', 0)).toBe('fushi');
  });
});
