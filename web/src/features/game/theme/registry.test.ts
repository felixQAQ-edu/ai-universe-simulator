import { describe, expect, it } from 'vitest';
import styles from '../game.module.css';
import { NEUTRAL_THEME, cardTheme, fusionKey, resolveWorldTheme } from './registry';

// 单一主题注册表(ADR-018 §4.1):世界判定只发生一次。

// 「未登记世界」样例:ADR-036 Slice G-1 登记了 cyberpunk 之后,KNOWN 里已没有未登记的真实 id,
// 改用一个不存在的 id 守同一条降级路径(《寻常》那一刀从 life_sim 换成 cyberpunk 是同一件事)。
const UNREGISTERED = 'not_registered_world';
// 封面用例整体自 scene.test.ts 迁来(原 `sceneImageUrl`,覆盖零损失);
// 卡片氛围与皮肤登记是本刀新收编的两项(原 vibeClass 私有且无测试)。

describe('resolveWorldTheme · 封面(原 sceneImageUrl 用例,逐条保留)', () => {
  it('已配图世界 → /scenes/<archetype>.webp', () => {
    expect(resolveWorldTheme('rules_creepy').sceneUrl).toBe('/scenes/rules_creepy.webp');
    expect(resolveWorldTheme('apocalypse').sceneUrl).toBe('/scenes/apocalypse.webp');
    expect(resolveWorldTheme('cthulhu').sceneUrl).toBe('/scenes/cthulhu.webp');
    expect(resolveWorldTheme('cultivation').sceneUrl).toBe('/scenes/cultivation.webp');
    expect(resolveWorldTheme('life_sim').sceneUrl).toBe('/scenes/life_sim.webp'); // 《寻常》静态视觉刀
    expect(resolveWorldTheme('animal_life').sceneUrl).toBe('/scenes/animal_life.webp'); // 《动物人生》静态视觉刀
    expect(resolveWorldTheme('cyberpunk').sceneUrl).toBe('/scenes/cyberpunk.webp'); // 《派单》ADR-036 Slice G-1
    expect(resolveWorldTheme('time_travel').sceneUrl).toBe('/scenes/time_travel.webp'); // 《七日开张》ADR-038 Slice G-1
  });

  it('未配图 / 未知 / undefined → null(优雅降级,不显图)', () => {
    expect(resolveWorldTheme(UNREGISTERED).sceneUrl).toBeNull();
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

  it('融合世界(修仙×克苏鲁,ADR-035)→ 融合专属封面 验讫', () => {
    expect(resolveWorldTheme(['cultivation', 'cthulhu']).sceneUrl).toBe('/scenes/fusion-yanqi.webp');
  });

  it('未登记融合组合 → 回落 host([0])的单体图,不盲取错图', () => {
    expect(resolveWorldTheme(['rules_creepy', 'cultivation']).sceneUrl).toBe(
      '/scenes/rules_creepy.webp',
    );
    // host 已配图、组合未登记 → host 的单体图(《寻常》配图后,本例从「双无图」改成守这条)
    expect(resolveWorldTheme(['life_sim', UNREGISTERED]).sceneUrl).toBe('/scenes/life_sim.webp');
    expect(resolveWorldTheme([UNREGISTERED, 'life_sim']).sceneUrl).toBeNull();
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

  it('克苏鲁(刀 4)登记了新皮肤 —— 四刀走完,四个基础世界全部登记', () => {
    expect(resolveWorldTheme('cthulhu').skin).toBe('cthulhu');
  });

  it('未知 / 未开放世界 → 中性主题(不显图、中性卡、旧实现)', () => {
    expect(resolveWorldTheme('totally_unknown')).toEqual(NEUTRAL_THEME);
    expect(resolveWorldTheme(UNREGISTERED)).toEqual(NEUTRAL_THEME);
  });

  // 《寻常》只有顶图 + 卡面(卡面自 2026-11-12 起带环境动效,纯 CSS,这里测不到),皮肤刻意不登记 → 局内仍走旧实现。
  it('《寻常》life_sim 有图有卡、但不登记皮肤', () => {
    const t = resolveWorldTheme('life_sim');
    expect(t.sceneUrl).toBe('/scenes/life_sim.webp');
    expect(t.cardClass).toBe(styles.cardLifeSim);
    expect(t.skin).toBeNull();
  });

  // 《动物人生》:顶图 + 卡面(卡面自 2026-11-12 起带环境动效);2026-11-14 起登记局内皮肤(ADR-021 局内皮肤一节)。
  it('《动物人生》animal_life 有图有卡,登记了自己的皮肤,取景点照旧', () => {
    const t = resolveWorldTheme('animal_life');
    expect(t.key).toBe('animal_life');
    expect(t.sceneUrl).toBe('/scenes/animal_life.webp');
    expect(t.cardClass).toBe(styles.cardAnimalLife);
    expect(t.cardClass).toBeTruthy();
    expect(t.skin).toBe('animal_life');
    expect(t.scenePosition).toBe('78% 50%');
    expect(cardTheme('animal_life').skin).toBe('animal_life'); // 卡片本身不消费 skin
  });

  // 《派单》:G-1 只补顶图 + 卡面;G-2 登记局内皮肤「夜班」。
  it('《派单》cyberpunk 有图有卡,G-2 起登记了自己的皮肤', () => {
    const t = resolveWorldTheme('cyberpunk');
    expect(t.key).toBe('cyberpunk');
    expect(t.sceneUrl).toBe('/scenes/cyberpunk.webp');
    expect(t.cardClass).toBe(styles.cardPaidan);
    expect(t.cardClass).toBeTruthy();
    expect(t.skin).toBe('cyberpunk');
  });

  // 《七日开张》(ADR-038 Slice G-1 顶图 + 卡面;Slice G-2 登记局内皮肤);主体居中,不带取景点。
  it('《七日开张》time_travel 有图有卡、登记了皮肤、不带 scenePosition', () => {
    const t = resolveWorldTheme('time_travel');
    expect(t.cardClass).toBeTruthy();
    expect(t).toStrictEqual({
      key: 'time_travel', sceneUrl: '/scenes/time_travel.webp', cardClass: styles.cardQiri, skin: 'time_travel',
    });
    expect(Object.prototype.hasOwnProperty.call(t, 'scenePosition')).toBe(false);
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

  // ADR-035《验讫》:局内皮肤随 host 取修仙;卡皮肤是融合专属的,不是修仙单体卡。
  it('验讫(host=修仙)随 host 用修仙皮肤,卡皮肤为融合专属 cardFusionYanqi', () => {
    const yanqi = resolveWorldTheme(['cultivation', 'cthulhu']);
    expect(yanqi.key).toBe(fusionKey('cultivation', 'cthulhu'));
    expect(yanqi.skin).toBe('cultivation');
    expect(yanqi.cardClass).toBe(styles.cardFusionYanqi);
    expect(yanqi.cardClass).not.toBe(cardTheme('cultivation').cardClass);
  });

  // 刀 4 之后**四个基础世界全部登记**,故「host 未登记 → 融合局走旧实现」这条降级路径
  // 在基础世界里已无样本可举 —— 改用未开放世界作 host 来守它(路径本身仍须成立:
  // 未来上新世界时,未登记的 host 必须整齐降级,而不是错拿别人的皮肤)。
  it('host 未登记皮肤的融合局仍走旧实现(降级路径本身仍成立)', () => {
    expect(resolveWorldTheme(['life_sim', 'apocalypse']).skin).toBeNull();
  });

  // 克苏鲁作 host 的组合尚未登记任何一组;真出现时按 Q2 纯 host 呈现 = 克苏鲁皮肤。
  it('克苏鲁作 host 的未登记组合 → 回落克苏鲁单体登记(不盲取 foreign 的皮肤)', () => {
    const t = resolveWorldTheme(['cthulhu', 'apocalypse']);
    expect(t.skin).toBe('cthulhu');
    expect(t.sceneUrl).toBe('/scenes/cthulhu.webp');
  });
});

describe('resolveWorldTheme · 顶图取景点 scenePosition', () => {
  it('《寻常》登记了取景点,随 sceneUrl 一起解析', () => {
    const t = resolveWorldTheme('life_sim');
    expect(t.sceneUrl).toBe('/scenes/life_sim.webp');
    expect(t.scenePosition).toBe('100% 50%');
  });

  it('《动物人生》登记了取景点,随 sceneUrl 一起解析', () => {
    const t = resolveWorldTheme('animal_life');
    expect(t.sceneUrl).toBe('/scenes/animal_life.webp');
    expect(t.scenePosition).toBe('78% 50%');
  });

  it('未登记取景的世界不带这个键(解析结果与加字段之前逐字节一致)', () => {
    const cases: (string | string[] | undefined)[] = [
      'rules_creepy', 'apocalypse', 'cthulhu', 'cultivation',
      ['cultivation', 'rules_creepy'], ['rules_creepy', 'apocalypse'], ['cultivation', 'cthulhu'],
      'cyberpunk', // 《派单》主体在横向中央,居中取景,不登记
      'time_travel', // 《七日开张》蒸笼在横向中央,居中取景,不登记
      UNREGISTERED, 'totally_unknown', undefined,
    ];
    for (const a of cases) {
      expect(Object.prototype.hasOwnProperty.call(resolveWorldTheme(a), 'scenePosition')).toBe(false);
    }
  });

  it('host 有取景的未登记融合组合 → 回落 host 的图,取景点跟着图走', () => {
    const t = resolveWorldTheme(['life_sim', UNREGISTERED]);
    expect(t.sceneUrl).toBe('/scenes/life_sim.webp');
    expect(t.scenePosition).toBe('100% 50%');
    // 《动物人生》作 host 同理:图和取景点一起回落,不会出现「别人的图 + 自己的取景」。
    const a = resolveWorldTheme(['animal_life', UNREGISTERED]);
    expect(a.sceneUrl).toBe('/scenes/animal_life.webp');
    expect(a.scenePosition).toBe('78% 50%');
    // 反向:foreign 是《动物人生》、host 未配图 → 不带图也不带取景点。
    const b = resolveWorldTheme([UNREGISTERED, 'animal_life']);
    expect(b.sceneUrl).toBeNull();
    expect(Object.prototype.hasOwnProperty.call(b, 'scenePosition')).toBe(false);
  });
});

// 《动物人生》这一刀只加了一条登记:其余每个世界的解析结果逐字段钉死,与加登记之前完全相同。
describe('resolveWorldTheme · 加《动物人生》后其余世界零回归', () => {
  it('其余世界的解析结果逐字段不变', () => {
    expect(resolveWorldTheme('rules_creepy')).toStrictEqual({
      key: 'rules_creepy', sceneUrl: '/scenes/rules_creepy.webp', cardClass: styles.cardCreepy, skin: 'rules_creepy',
    });
    expect(resolveWorldTheme('apocalypse')).toStrictEqual({
      key: 'apocalypse', sceneUrl: '/scenes/apocalypse.webp', cardClass: styles.cardApocalypse, skin: 'apocalypse',
    });
    expect(resolveWorldTheme('cthulhu')).toStrictEqual({
      key: 'cthulhu', sceneUrl: '/scenes/cthulhu.webp', cardClass: styles.cardCthulhu, skin: 'cthulhu',
    });
    expect(resolveWorldTheme('cultivation')).toStrictEqual({
      key: 'cultivation', sceneUrl: '/scenes/cultivation.webp', cardClass: styles.cardCultivation, skin: 'cultivation',
    });
    expect(resolveWorldTheme('life_sim')).toStrictEqual({
      key: 'life_sim', sceneUrl: '/scenes/life_sim.webp', scenePosition: '100% 50%', cardClass: styles.cardLifeSim, skin: null,
    });
    expect(resolveWorldTheme(['cultivation', 'rules_creepy'])).toStrictEqual({
      key: 'cultivation×rules_creepy', sceneUrl: '/scenes/fusion-shihai.webp', cardClass: styles.cardFusion, skin: 'cultivation',
    });
    expect(resolveWorldTheme(['rules_creepy', 'apocalypse'])).toStrictEqual({
      key: 'rules_creepy×apocalypse', sceneUrl: '/scenes/fusion-renfang.webp', cardClass: styles.cardFusionRenfang, skin: 'rules_creepy',
    });
    expect(resolveWorldTheme(['cultivation', 'cthulhu'])).toStrictEqual({
      key: 'cultivation×cthulhu', sceneUrl: '/scenes/fusion-yanqi.webp', cardClass: styles.cardFusionYanqi, skin: 'cultivation',
    });
    expect(resolveWorldTheme(UNREGISTERED)).toEqual(NEUTRAL_THEME);
    expect(resolveWorldTheme('totally_unknown')).toEqual(NEUTRAL_THEME);
  });

  it('《派单》登记后的解析结果逐字段钉死(不带 scenePosition)', () => {
    expect(resolveWorldTheme('cyberpunk')).toStrictEqual({
      key: 'cyberpunk', sceneUrl: '/scenes/cyberpunk.webp', cardClass: styles.cardPaidan, skin: 'cyberpunk',
    });
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

  it('验讫(修仙×克苏鲁)有自己的渗漏卡氛围 cardFusionYanqi,与另两张融合卡及两个单体卡都不同', () => {
    const yanqi = cardTheme('cultivation×cthulhu');
    expect(yanqi.cardClass).toBe(styles.cardFusionYanqi);
    expect(yanqi.cardClass).toBeTruthy();
    expect(yanqi.skin).toBeNull(); // 卡片层不带局内皮肤(同另两张融合卡)
    for (const other of ['cultivation×rules_creepy', 'rules_creepy×apocalypse', 'cultivation', 'cthulhu']) {
      expect(yanqi.cardClass).not.toBe(cardTheme(other).cardClass);
    }
  });

  it('《寻常》life_sim → 自己的浮世卡 cardLifeSim,与四个基础世界的卡都不同', () => {
    const c = cardTheme('life_sim').cardClass;
    expect(c).toBe(styles.cardLifeSim);
    expect(c).toBeTruthy();
    for (const other of ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation']) {
      expect(c).not.toBe(cardTheme(other).cardClass);
    }
  });

  it('《动物人生》animal_life → 自己的浮世卡 cardAnimalLife,与四个基础世界及《寻常》的卡都不同', () => {
    const c = cardTheme('animal_life').cardClass;
    expect(c).toBe(styles.cardAnimalLife);
    expect(c).toBeTruthy();
    for (const other of ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation', 'life_sim']) {
      expect(c).not.toBe(cardTheme(other).cardClass);
    }
  });

  it('《派单》cyberpunk → 自己的卡 cardPaidan,与其余单体卡都不同', () => {
    const c = cardTheme('cyberpunk').cardClass;
    expect(c).toBe(styles.cardPaidan);
    expect(c).toBeTruthy();
    for (const other of ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation', 'life_sim', 'animal_life']) {
      expect(c).not.toBe(cardTheme(other).cardClass);
    }
  });

  it('《七日开张》time_travel → 自己的浮世卡 cardQiri,与其余单体卡都不同', () => {
    const c = cardTheme('time_travel').cardClass;
    expect(c).toBe(styles.cardQiri);
    expect(c).toBeTruthy();
    expect(cardTheme('time_travel').skin).toBe('time_travel'); // G-2 起登记(卡片本身不消费 skin)
    for (const other of ['rules_creepy', 'apocalypse', 'cthulhu', 'cultivation', 'life_sim', 'animal_life', 'cyberpunk']) {
      expect(c).not.toBe(cardTheme(other).cardClass);
    }
  });

  it('未登记 key → 中性卡(原 vibeClass 无测试无显式降级,收编时补上)', () => {
    expect(cardTheme(UNREGISTERED).cardClass).toBe('');
    expect(cardTheme('totally_unknown').cardClass).toBe('');
  });
});
