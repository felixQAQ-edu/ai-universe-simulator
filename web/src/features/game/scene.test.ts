import { describe, expect, it } from 'vitest';
import { dangerLabel, sceneImageUrl } from './scene';

// #8 顶部氛围图纯函数:选图路径解析 + 危险度中英映射。

describe('sceneImageUrl(archetype→图路径)', () => {
  it('已配图世界 → /scenes/<archetype>.webp', () => {
    expect(sceneImageUrl('rules_creepy')).toBe('/scenes/rules_creepy.webp');
    expect(sceneImageUrl('apocalypse')).toBe('/scenes/apocalypse.webp');
    expect(sceneImageUrl('cthulhu')).toBe('/scenes/cthulhu.webp');
    expect(sceneImageUrl('cultivation')).toBe('/scenes/cultivation.webp');
  });

  it('未配图 / 未知 / undefined → null(优雅降级,不显图)', () => {
    expect(sceneImageUrl('life_sim')).toBeNull(); // 已知未开放,暂无图
    expect(sceneImageUrl('cyberpunk')).toBeNull();
    expect(sceneImageUrl('totally_unknown')).toBeNull();
    expect(sceneImageUrl(undefined)).toBeNull();
  });
});

describe('dangerLabel(危险度中英映射)', () => {
  it('四档映射为中文短词', () => {
    expect(dangerLabel('low')).toBe('低');
    expect(dangerLabel('medium')).toBe('中');
    expect(dangerLabel('high')).toBe('高');
    expect(dangerLabel('extreme')).toBe('极危');
  });

  it('未知值回落原字符串(不崩、不吞信息)', () => {
    expect(dangerLabel('unknown')).toBe('unknown');
    expect(dangerLabel('')).toBe('');
  });
});
