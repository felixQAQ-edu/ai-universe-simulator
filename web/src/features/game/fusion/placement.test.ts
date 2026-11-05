import { describe, expect, it } from 'vitest';
import { cardOrder, placeFused } from './placement';

// 融合卡落位(Felix 2026-11-05 裁定选 A):成品卡插在宿主紧下方;同一宿主再揉一张,新的紧贴宿主、旧的往下。

const ACTIVE = ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation'];
const flat = (fused: readonly string[]) =>
  cardOrder(ACTIVE, fused).map((s) => (s.kind === 'world' ? s.id : `[${s.key}]`));

describe('cardOrder / placeFused', () => {
  it('没有融合卡:卡列就是已开放卡原序', () => {
    expect(flat([])).toEqual(ACTIVE);
  });

  it('成品卡插在宿主的紧下方(不是追加在已开放卡末尾)', () => {
    const fused = placeFused([], 'rules_creepy×apocalypse');
    expect(flat(fused)).toEqual(['rules_creepy', '[rules_creepy×apocalypse]', 'apocalypse', 'cthulhu', 'cultivation']);
  });

  it('同一宿主揉两次:新的那张紧贴宿主,旧的依次往下', () => {
    let fused = placeFused([], 'cultivation×rules_creepy');
    fused = placeFused(fused, 'cultivation×cthulhu');
    expect(flat(fused)).toEqual([
      'rules_creepy',
      'apocalypse',
      'cthulhu',
      'cultivation',
      '[cultivation×cthulhu]',
      '[cultivation×rules_creepy]',
    ]);
  });

  it('不同宿主各归各的,互不挤占', () => {
    let fused = placeFused([], 'cultivation×cthulhu');
    fused = placeFused(fused, 'rules_creepy×apocalypse');
    expect(flat(fused)).toEqual([
      'rules_creepy',
      '[rules_creepy×apocalypse]',
      'apocalypse',
      'cthulhu',
      'cultivation',
      '[cultivation×cthulhu]',
    ]);
  });

  it('同一组合再揉一次:回到紧贴宿主的位置,不重复', () => {
    let fused = placeFused([], 'cultivation×rules_creepy');
    fused = placeFused(fused, 'cultivation×cthulhu');
    fused = placeFused(fused, 'cultivation×rules_creepy');
    expect(fused).toEqual(['cultivation×rules_creepy', 'cultivation×cthulhu']);
    expect(flat(fused).slice(-3)).toEqual(['cultivation', '[cultivation×rules_creepy]', '[cultivation×cthulhu]']);
  });

  it('宿主不在已开放卡里(理论上不会):排在已开放卡之后,不丢', () => {
    expect(flat(['life_sim×cthulhu'])).toEqual([...ACTIVE, '[life_sim×cthulhu]']);
  });
});
