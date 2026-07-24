import { describe, expect, it } from 'vitest';
import { dangerLabel } from './scene';

// #8 危险度中英映射(选图路径解析已随 `sceneImageUrl` 收编进 theme/registry.ts,
// 对应用例整体迁到 theme/registry.test.ts —— 覆盖零损失,只是换了归属)。

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
