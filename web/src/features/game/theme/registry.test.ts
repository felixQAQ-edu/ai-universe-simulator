import { describe, expect, it } from 'vitest';
import { NEUTRAL_THEME, cardTheme, fusionKey, resolveWorldTheme } from './registry';

// 单一主题注册表(ADR-018 §4.1):世界判定只发生一次。
// 封面用例整体自 scene.test.ts 迁来(原 `sceneImageUrl`,覆盖零损失);
// 卡片氛围与皮肤登记是本刀新收编的两项(原 vibeClass 私有且无测试)。

describe('resolveWorldTheme · 封面(原 sceneImageUrl 用例,逐条保留)', () => {
  it('已配图世界 → /scenes/<archetype>.webp', () => {
    expect(resolveWorldTheme('rules_creepy').sceneUrl).toBe('/scenes/rules_creepy.webp');
    expect(resolveWorldTheme('apocalypse').sceneUrl).toBe('/scenes/apocalypse.webp');
    expect(resolveWorldTheme('cthulhu').sceneUrl).toBe('/scenes/cthulhu.webp');
    expect(resolveWorldTheme('cultivation').sceneUrl).toBe('/scenes/cultivation.webp');
  });

  it('未配图 / 未知 / undefined → null(优雅降级,不显图)', () => {
    expect(resolveWorldTheme('life_sim').sceneUrl).toBeNull(); // 已知未开放,暂无图
    expect(resolveWorldTheme('cyberpunk').sceneUrl).toBeNull();
    expect(resolveWorldTheme('totally_unknown').sceneUrl).toBeNull();
    expect(resolveWorldTheme(undefined).sceneUrl).toBeNull();
  });

  it('单元素数组 → 与单键同结果(单体零回归)', () => {
    expect(resolveWorldTheme(['cultivation']).sceneUrl).toBe('/scenes/cultivation.webp');
    expect(resolveWorldTheme(['rules_creepy']).sceneUrl).toBe('/scenes/rules_creepy.webp');
    expect(resolveWorldTheme([]).sceneUrl).toBeNull();
  });

  it('融合世界(修仙×规则怪谈,host 在前)→ 融合专属封面 识海遗蜕', () => {
    expect(resolveWorldTheme(['cultivation', 'rules_creepy']).sceneUrl).toBe(
      '/scenes/fusion-shihai.webp',
    );
  });

  it('融合世界(规则怪谈×末日,ADR-014)→ 融合专属封面 缺页的人防工程', () => {
    expect(resolveWorldTheme(['rules_creepy', 'apocalypse']).sceneUrl).toBe(
      '/scenes/fusion-renfang.webp',
    );
  });

  it('未登记融合组合 → 回落 host([0])的单体图,不盲取错图', () => {
    expect(resolveWorldTheme(['rules_creepy', 'cultivation']).sceneUrl).toBe(
      '/scenes/rules_creepy.webp',
    );
    expect(resolveWorldTheme(['life_sim', 'cyberpunk']).sceneUrl).toBeNull();
  });
});

describe('resolveWorldTheme · 皮肤登记(feature gate,ADR-018 §4.5)', () => {
  it('规则怪谈(刀 1 试验田)登记了新皮肤', () => {
    expect(resolveWorldTheme('rules_creepy').skin).toBe('rules_creepy');
  });

  it('修仙(刀 2)登记了新皮肤', () => {
    expect(resolveWorldTheme('cultivation').skin).toBe('cultivation');
  });

  it('末日(刀 3)登记了新皮肤', () => {
    expect(resolveWorldTheme('apocalypse').skin).toBe('apocalypse');
  });

  it('克苏鲁仍走旧实现(刀 3 不准顺手一起登记,等刀 4)', () => {
    expect(resolveWorldTheme('cthulhu').skin).toBeNull();
  });

  it('未知 / 未开放世界 → 中性主题(不显图、中性卡、旧实现)', () => {
    expect(resolveWorldTheme('totally_unknown')).toEqual(NEUTRAL_THEME);
    expect(resolveWorldTheme('life_sim').skin).toBeNull();
  });

  // 放行标准 2:规则怪谈作 host 的融合局,在没有融合视觉签名时**纯 host 呈现**。
  it('融合局的游戏内皮肤 = host 的皮肤(签名未实现前纯 host 呈现,ADR-018 §5 Q2)', () => {
    const renfang = resolveWorldTheme(['rules_creepy', 'apocalypse']);
    expect(renfang.key).toBe(fusionKey('rules_creepy', 'apocalypse'));
    expect(renfang.skin).toBe('rules_creepy'); // host = 规则怪谈 → 用规则怪谈皮肤
  });

  // 刀 2 的连带结果(设计如此,非顺手做):识海遗蜕 host=修仙 → 随 host 一起用上修仙皮肤。
  // 融合**专属**视觉签名仍未实现、仍挂 ADR-019;这里只是 Q2「签名未实现前纯 host 呈现」。
  it('识海遗蜕(host=修仙)随 host 用修仙皮肤,封面仍是融合封面', () => {
    const shihai = resolveWorldTheme(['cultivation', 'rules_creepy']);
    expect(shihai.key).toBe(fusionKey('cultivation', 'rules_creepy'));
    expect(shihai.skin).toBe('cultivation');
    expect(shihai.sceneUrl).toBe('/scenes/fusion-shihai.webp');
  });

  // 刀 3 的连带结果(同刀 2 的识海遗蜕):缺页的人防工程 host=规则怪谈,故仍用规则怪谈皮肤;
  // 而末日**作 host** 的组合(尚未登记任何一组)从此会拿到末日皮肤 —— Q2 降级路径,非融合签名。
  it('host 未登记皮肤的融合局仍走旧实现(克苏鲁作 host 时)', () => {
    expect(resolveWorldTheme(['cthulhu', 'apocalypse']).skin).toBeNull();
  });
});

describe('cardTheme · 选择屏卡片氛围(收编原私有 vibeClass)', () => {
  it('四个已激活世界各有一套且互不相同', () => {
    const classes = ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation'].map(
      (a) => cardTheme(a).cardClass,
    );
    classes.forEach((c) => expect(c).toBeTruthy());
    expect(new Set(classes).size).toBe(4);
  });

  it('两个融合组合各有一套渗漏卡氛围,且与单体卡不同', () => {
    const shihai = cardTheme('cultivation×rules_creepy').cardClass;
    const renfang = cardTheme('rules_creepy×apocalypse').cardClass;
    expect(shihai).toBeTruthy();
    expect(renfang).toBeTruthy();
    expect(shihai).not.toBe(renfang);
    expect(shihai).not.toBe(cardTheme('cultivation').cardClass);
  });

  it('未登记 key → 中性卡(原 vibeClass 无测试无显式降级,收编时补上)', () => {
    expect(cardTheme('life_sim').cardClass).toBe('');
    expect(cardTheme('totally_unknown').cardClass).toBe('');
  });
});
