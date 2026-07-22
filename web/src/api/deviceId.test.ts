import { describe, expect, it } from 'vitest';
import { getDeviceId } from './deviceId';

// 软闸设备键(ADR-016):首次生成并落 localStorage,后续调用恒定。

describe('getDeviceId', () => {
  it('生成非空 id,重复调用恒定,且持久化到 localStorage', () => {
    const first = getDeviceId();
    expect(first).toBeTruthy();
    expect(getDeviceId()).toBe(first);
    expect(globalThis.localStorage?.getItem('aiuniverse.deviceId')).toBe(first);
  });
});
