import { describe, expect, it } from 'vitest';
import type { SaveSummary } from '../../../state/savePointers';
import { pickContinue } from './pick';

const s = (id: string, extra: Partial<SaveSummary> = {}): SaveSummary => ({
  saveId: id, title: id, archetypes: ['rules_creepy'], turn: 1, ended: false,
  lastPlayedAt: 1, availability: 'available', ...extra,
});

describe('pickContinue(ADR-042 决策 4)', () => {
  it('取第一条没结局、能找回的;已结局与暂时无法找回的跳过往下找', () => {
    expect(
      pickContinue([s('e', { ended: true }), s('u', { availability: 'unavailable' }), s('ok'), s('later')])?.saveId,
    ).toBe('ok');
  });
  it('没有候选 → null', () => {
    expect(pickContinue([])).toBeNull();
    expect(pickContinue([s('e', { ended: true }), s('u', { availability: 'unavailable' })])).toBeNull();
  });
});
