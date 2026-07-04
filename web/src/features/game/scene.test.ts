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

  // ── ADR-013:放开单键假设 —— 数组入参(单体单元素 / 融合双元素)──
  it('单元素数组 → 与单键同结果(单体零回归)', () => {
    expect(sceneImageUrl(['cultivation'])).toBe('/scenes/cultivation.webp');
    expect(sceneImageUrl(['rules_creepy'])).toBe('/scenes/rules_creepy.webp');
    expect(sceneImageUrl([])).toBeNull();
  });

  it('融合世界(修仙×规则怪谈,host 在前)→ 融合专属封面 识海遗蜕', () => {
    expect(sceneImageUrl(['cultivation', 'rules_creepy'])).toBe('/scenes/fusion-shihai.webp');
  });

  it('未登记融合组合 → 回落 host([0])的单体图,不盲取错图', () => {
    // 反向(host=规则怪谈)未登记融合封面 → 回落规则怪谈图。
    expect(sceneImageUrl(['rules_creepy', 'cultivation'])).toBe('/scenes/rules_creepy.webp');
    // host 也未配图 → null 优雅降级。
    expect(sceneImageUrl(['life_sim', 'cyberpunk'])).toBeNull();
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
