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
 * 已登记新皮肤的世界 id。刀 1 只有规则怪谈(试验田);刀 2–4 各自把自己那条加进来。
 * 未登记世界 → `skin: null` → 走旧实现。
 */
export type SkinId = 'rules_creepy';

/** 一个世界(单体或融合组合)的视觉登记。 */
export interface WorldTheme {
  /** 注册键:单体 = archetype id;融合 = `host×foreign`(有序,host 在前)。未知世界 = ''。 */
  key: string;
  /** 顶部氛围图路径;未配图 → null(优雅降级:不显图、布局不塌)。 */
  sceneUrl: string | null;
  /** 选择屏卡片氛围 class;未登记 → ''(中性卡)。 */
  cardClass: string;
  /** 新视觉皮肤 id;null = 未登记 → 旧实现(feature gate)。 */
  skin: SkinId | null;
}

/** 中性主题:未知世界 / 无上下文时的安全缺省——不显图、中性卡、旧实现。 */
export const NEUTRAL_THEME: WorldTheme = { key: '', sceneUrl: null, cardClass: '', skin: null };

interface WorldEntry {
  sceneUrl: string | null;
  cardClass: string;
  skin: SkinId | null;
}

/**
 * 单体世界登记表。加一个世界 = 加一条(图放 `web/public/scenes/<archetype>.webp`,后端零改)。
 * `skin` 目前全为 null(基建段):四世界一律旧实现,下一刀只把规则怪谈那条翻过来。
 */
const WORLDS: Readonly<Record<string, WorldEntry>> = {
  rules_creepy: { sceneUrl: '/scenes/rules_creepy.webp', cardClass: styles.cardCreepy, skin: null },
  apocalypse: { sceneUrl: '/scenes/apocalypse.webp', cardClass: styles.cardApocalypse, skin: null },
  cthulhu: { sceneUrl: '/scenes/cthulhu.webp', cardClass: styles.cardCthulhu, skin: null },
  cultivation: { sceneUrl: '/scenes/cultivation.webp', cardClass: styles.cardCultivation, skin: null },
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
  return { key: host, sceneUrl: hostEntry.sceneUrl, cardClass: hostEntry.cardClass, skin: hostSkin };
}

/** 选择屏一张卡的氛围登记(单体传 archetype id,融合卡传组合键)。未登记 → 中性。 */
export function cardTheme(key: string): WorldTheme {
  const fusion = FUSIONS[key];
  if (fusion) return { key, sceneUrl: fusion.sceneUrl, cardClass: fusion.cardClass, skin: null };
  const world = WORLDS[key];
  if (!world) return NEUTRAL_THEME;
  return { key, sceneUrl: world.sceneUrl, cardClass: world.cardClass, skin: world.skin };
}
