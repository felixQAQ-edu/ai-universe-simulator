import { describe, expect, it } from 'vitest';
import { gapLines, turnHeading } from './historyText';

// ADR-025 刀 3 口径 E/F 的文案(逐字)。

describe('turnHeading', () => {
  it('turn 0 =「开场」,turn ≥ 1 =「第 N 回合」', () => {
    expect(turnHeading(0)).toBe('开场');
    expect(turnHeading(7)).toBe('第 7 回合');
  });
});

describe('gapLines', () => {
  it('before_recording', () => {
    expect(gapLines({ kind: 'gap', reason: 'before_recording', fromTurn: 0, toTurn: 12 })).toEqual([
      '更早的回合没有留下记录',
    ]);
  });
  it('write_failed 单回合', () => {
    expect(gapLines({ kind: 'gap', reason: 'write_failed', fromTurn: 7, toTurn: 7 })).toEqual([
      '第 7 回合没有留下记录',
    ]);
  });
  it('write_failed 多回合(en dash)', () => {
    expect(gapLines({ kind: 'gap', reason: 'write_failed', fromTurn: 7, toTurn: 8 })).toEqual([
      '第 7–8 回合没有留下记录',
    ]);
  });
  it('write_failed 仅 turn 0', () => {
    expect(gapLines({ kind: 'gap', reason: 'write_failed', fromTurn: 0, toTurn: 0 })).toEqual([
      '开场没有留下记录',
    ]);
  });
  it('write_failed 0–3 → 拆两行', () => {
    expect(gapLines({ kind: 'gap', reason: 'write_failed', fromTurn: 0, toTurn: 3 })).toEqual([
      '开场没有留下记录',
      '第 1–3 回合没有留下记录',
    ]);
  });
});
