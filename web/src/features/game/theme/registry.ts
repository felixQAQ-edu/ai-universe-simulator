// 单一主题注册表(ADR-018 §4.1):**世界判定只发生一次**,子组件消费判定结果、不得各自再判 archetype。
//
// 收编本刀之前散在四处的 per-world 分派:
//   ① scene.ts `SCENE_ARCHETYPES`(单体封面)   ② scene.ts `FUSION_SCENES`(融合封面)
//   ③ ArchetypeSelect `vibeClass`(选择卡氛围)  ④ ArchetypeSelect `FUSION_CARDS[].theme`(融合卡氛围)
// (第五处 StatsPanel.barClass 归数值通用层/主题层分工,见 StatsPanel.tsx。)
//
// 纯数据 + 纯函数,无平台 IO(守 ADR-003);测试直打这两个入口。
// **feature gate(ADR-018 §4.5)**:`skin` 是唯一的登记位——删掉某世界的 `skin` 字段,
// 该世界即刻回到旧实现,不必回滚任何基建。

import styles from '../game.module.css';

/**
 * 已登记新皮肤的世界 id。刀 1 规则怪谈(试验田)证明基建可用,刀 2 加修仙;刀 3/4 各自再加一条。
 * 未登记世界 → `skin: null` → 走旧实现。
 */
export type SkinId = 'rules_creepy' | 'cultivation' | 'apocalypse' | 'cthulhu' | 'cyberpunk';

/** 一个世界(单体或融合组合)的视觉登记。 */
export interface WorldTheme {
  /** 注册键:单体 = archetype id;融合 = `host×foreign`(有序,host 在前)。未知世界 = ''。 */
  key: string;
  /** 顶部氛围图路径;未配图 → null(优雅降级:不显图、布局不塌)。 */
  sceneUrl: string | null;
  /**
   * 顶图取景点(CSS `background-position` 值,如 `'78% 60%'`),随 `sceneUrl` 一起解析。
   * **可选、缺省即不带这个键** —— 未登记取景的世界解析结果与加字段之前逐字节一致,横幅照旧居中取景。
   */
  scenePosition?: string;
  /** 选择屏卡片氛围 class;未登记 → ''(中性卡)。 */
  cardClass: string;
  /** 新视觉皮肤 id;null = 未登记 → 旧实现(feature gate)。 */
  skin: SkinId | null;
}

/** 中性主题:未知世界 / 无上下文时的安全缺省——不显图、中性卡、旧实现。 */
export const NEUTRAL_THEME: WorldTheme = { key: '', sceneUrl: null, cardClass: '', skin: null };

interface WorldEntry {
  sceneUrl: string | null;
  /** 顶图取景点,见 {@link WorldTheme.scenePosition}。只给主体不在图中央的图登记。 */
  scenePosition?: string;
  cardClass: string;
  skin: SkinId | null;
}

/**
 * 单体世界登记表。加一个世界 = 加一条(图放 `web/public/scenes/<archetype>.webp`,后端零改)。
 * 四刀走完,四个基础世界**全部登记**(刀 1 规则怪谈试验田 / 刀 2 修仙 / 刀 3 末日 / 刀 4 克苏鲁)。
 * feature gate 仍在:任一世界出问题,删它的 `skin` 一条即回到旧实现。
 */
const WORLDS: Readonly<Record<string, WorldEntry>> = {
  rules_creepy: {
    sceneUrl: '/scenes/rules_creepy.webp',
    cardClass: styles.cardCreepy,
    skin: 'rules_creepy', // 刀 1 试验田:唯一登记新皮肤的世界
  },
  apocalypse: {
    sceneUrl: '/scenes/apocalypse.webp',
    cardClass: styles.cardApocalypse,
    skin: 'apocalypse', // 刀 3:一级记忆点「电台信号」走间奏槽(不挂轴,故无 signatureAxisKey)
  },
  cthulhu: {
    sceneUrl: '/scenes/cthulhu.webp',
    cardClass: styles.cardCthulhu,
    // 刀 4:一级记忆点「文字异常」——**不挂跨档事件**,而是按签名轴当前档的 severity
    // 调频率(ADR-018 §5 Q4:只在低位启动会退化为状态播报)。故 signatureAxisKey 登记的是
    // 「盯哪根轴」,消费方是 `ProseProps.signatureSeverity` 而非 signatureTick。
    skin: 'cthulhu',
  },
  cultivation: {
    sceneUrl: '/scenes/cultivation.webp',
    cardClass: styles.cardCultivation,
    skin: 'cultivation', // 刀 2:一级记忆点「一声钟鸣」挂 realm 向上跨档
  },
  // 《寻常》(ADR-020):只补静态视觉 —— 顶图 + 浮世卡面。**皮肤不登记**(`skin: null` = 局内走旧实现),
  // 同 ADR-020 裁定一的顺序:先验玩法再投视觉,这一刀只补到静态为止,不带记忆点与动效。
  life_sim: {
    sceneUrl: '/scenes/life_sim.webp',
    // 取景(原图 1774×1040,实测校准,2026-11-09):
    // - 横向 100%:窗一直延伸到原图右缘,只有贴右才能在 375 / 480 宽下把窗完整留在画面里(90% 已切掉窗右侧 20px)。
    // - 纵向 50%:手机宽度下横幅比图窄、按高度铺满,纵向值不起作用(碗底恒在 51.3%);
    //   宽屏(横幅 640 宽、高 ≤ 375)改按宽度铺满,纵向才有余量。最极端的 640×300 下 50% → 碗底 51.6%,
    //   40% → 54.1% 越过 53% 的标准,下限约 44%。
    scenePosition: '100% 50%',
    cardClass: styles.cardLifeSim,
    skin: null,
  },
  // 《动物人生》(ADR-021):同《寻常》那一刀,只补静态视觉 —— 顶图 + 浮世卡面,**皮肤不登记**(局内走旧实现)。
  animal_life: {
    sceneUrl: '/scenes/animal_life.webp',
    // 取景(原图 1672×941,实测校准,2026-11-09):碗在原图 x 1075–1254、门缝在 x 1198–1255(右侧约 64%–75%)。
    // - 横向 78%:375 宽(横幅高 300–380)下可行区间约 44%–100%,480 宽约 14%–100%;78% 让碗和门缝落在画面
    //   中线偏右(375×340 下碗在 210–275px,两侧各留 ≥ 100px),左侧还留着纸箱与拖鞋,右侧留住门。
    // - 纵向 50%:手机宽度按高度铺满,纵向不起作用(碗底恒在 51.9%);宽屏 640×300 下 50% → 碗底 52.2%,
    //   40% → 54.2% 越过 53% 的标准,下限约 47%(46% → 53.04%)。
    scenePosition: '78% 50%',
    cardClass: styles.cardAnimalLife,
    skin: null,
  },
  // 《派单》(ADR-036):G-1 补顶图 + 卡面;G-2 登记局内皮肤「夜班」(义眼读数 / 接单卡 / 积水反光 / 义眼投影浮字)。
  cyberpunk: {
    sceneUrl: '/scenes/cyberpunk.webp',
    // 取景(图 1672×1104,门口以下地面已纵向拉长,外卖袋底在图高 53.0%,2026-11-11 实测):主体在横向中央,不登记、居中。
    // 手机宽度按高度铺满,袋底恒为 53.0%;宽屏按宽度铺满,640×380 → 53.3%、最极端的 640×300 → 54.2%
    // (横幅最宽 640:.screen 640 且 border-box)。Felix 2026-11-11 定居中不改:53.3% 在误差内,640×300 少见且
    // 文字在左、袋子在中不遮;'50% 60%' 虽能把 640×300 收回 50.1%,但会裁掉空中连廊。
    cardClass: styles.cardPaidan,
    skin: 'cyberpunk', // G-2:一级记忆点「已为你优化时限。」挂 profile 向上跨档
  },
};

/**
 * 融合组合登记表(ADR-013/014),键 = `host×foreign`(有序)。只登记**封面与卡皮肤**——
 * **游戏内皮肤刻意不登记**:融合局的 `skin` 一律取 host 的(ADR-018 §5 Q2「签名未实现前纯 host 呈现」,
 * 同 scene.ts 未配图即降级的先例);融合专属视觉签名挂 ADR-019,不阻塞基础移植。
 */
const FUSIONS: Readonly<Record<string, { sceneUrl: string; cardClass: string }>> = {
  'cultivation×rules_creepy': {
    sceneUrl: '/scenes/fusion-shihai.webp',
    cardClass: styles.cardFusion, // 修仙青白仙气 × 怪谈冷蓝互噬
  },
  'rules_creepy×apocalypse': {
    sceneUrl: '/scenes/fusion-renfang.webp',
    cardClass: styles.cardFusionRenfang, // 怪谈冷蓝 × 末日锈土互噬
  },
  'cultivation×cthulhu': {
    sceneUrl: '/scenes/fusion-yanqi.webp',
    cardClass: styles.cardFusionYanqi, // 修仙青白微金 × 克苏鲁幽绿深渊互噬
  },
};

/** 融合组合键(与后端 FUSION_COMBOS / 前端 fusion.ts 同键)。 */
export function fusionKey(host: string, foreign: string): string {
  return `${host}×${foreign}`;
}

/**
 * **本项目唯一的世界判定入口**(ADR-018 §4.1)。据 `world.archetypes` 解析该局的全部视觉登记:
 * - 单体(单 key / 单元素数组)→ 该世界的登记;
 * - 融合(≥2 元素,host 在前)→ 融合封面与卡皮肤 + **host 的游戏内皮肤**;
 *   未登记组合 → 回落 host 的单体登记(绝不盲取 `[0]` 当单体键错认融合世界)。
 * 未知/缺省 → {@link NEUTRAL_THEME}。
 */
export function resolveWorldTheme(
  archetypes: string | readonly string[] | undefined,
): WorldTheme {
  if (!archetypes) return NEUTRAL_THEME;
  const list = typeof archetypes === 'string' ? [archetypes] : archetypes;
  if (list.length === 0) return NEUTRAL_THEME;

  const host = list[0];
  const hostEntry = WORLDS[host];
  // 融合局的游戏内皮肤 = host 的皮肤(未登记 host → null → 旧实现)。
  const hostSkin = hostEntry?.skin ?? null;

  if (list.length >= 2) {
    const key = fusionKey(host, list[1]);
    const fusion = FUSIONS[key];
    if (fusion) {
      return { key, sceneUrl: fusion.sceneUrl, cardClass: fusion.cardClass, skin: hostSkin };
    }
    // 未登记融合组合:回落 host 单体登记(仍优雅降级)。
  }

  if (!hostEntry) return NEUTRAL_THEME;
  return {
    key: host,
    sceneUrl: hostEntry.sceneUrl,
    // 取景点跟着图走:只有登记了的世界才带这个键,其余世界的解析结果不多出任何字段。
    ...(hostEntry.scenePosition ? { scenePosition: hostEntry.scenePosition } : {}),
    cardClass: hostEntry.cardClass,
    skin: hostSkin,
  };
}

/** 选择屏一张卡的氛围登记(单体传 archetype id,融合卡传组合键)。未登记 → 中性。 */
export function cardTheme(key: string): WorldTheme {
  const fusion = FUSIONS[key];
  if (fusion) return { key, sceneUrl: fusion.sceneUrl, cardClass: fusion.cardClass, skin: null };
  const world = WORLDS[key];
  if (!world) return NEUTRAL_THEME;
  return { key, sceneUrl: world.sceneUrl, cardClass: world.cardClass, skin: world.skin };
}
