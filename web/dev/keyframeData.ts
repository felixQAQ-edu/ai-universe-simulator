import type { ArchetypeSummary } from '../src/api';
import type { CardDragBinding } from '../src/features/game/ArchetypeSelect';

// 《验讫》静态关键帧(YanqiKeyframes)与短运动稿(YanqiMotion)共用的数据。

/** 选择屏目录:与后端 ArchetypeRegistry.listForSelection() 的顺序与文案一致(2026-10-20 抄)。 */
export const CATALOG: ArchetypeSummary[] = [
  { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: '一纸诡异守则,真假混杂。读懂它,或者付出代价。', vibeTag: '诡异 · 高危', active: true },
  { archetype: 'apocalypse', displayName: '末日生存', tagline: '废土求生,饥饿是另一个敌人。撑过下一个夜晚。', vibeTag: '荒凉 · 绝境', active: true },
  { archetype: 'cthulhu', displayName: '克苏鲁', tagline: '凝视深渊,深渊回以低语。知道得越多,离疯狂越近。', vibeTag: '深渊 · 疯狂', active: true },
  { archetype: 'cultivation', displayName: '修仙', tagline: '逆天改命,踏上仙途。一念成圣,一念成魔。', vibeTag: '缥缈 · 仙途', active: true },
  { archetype: 'life_sim', displayName: '人生模拟', tagline: '一生只有一次,而它大部分时候都很寻常。', vibeTag: '平常 · 一生', active: true },
  { archetype: 'animal_life', displayName: '动物人生', tagline: '熟悉的一切变了，你学会的规则还作数吗？', vibeTag: '视角错位 · 误读回收', active: true },
  { archetype: 'cyberpunk', displayName: '赛博朋克', tagline: null, vibeTag: null, active: false },
];

export const HOST = 'cultivation';
export const FOREIGN = 'cthulhu';
export const COMBO = `${HOST}×${FOREIGN}`;

/**
 * 被拖者相对「正对修仙卡位」的偏移。dy 以卡高 H 为单位(在修仙卡位之下的量),
 * 取值的唯一约束:两个标题都露在外面。
 */
export const POSE: Record<1 | 2, { dyH: number; dx: number; rot: number }> = {
  1: { dyH: 0.56, dx: 14, rot: 3.5 }, // 交接:斜压,压到修仙标题行之下
  2: { dyH: 0.5, dx: 0, rot: 0 }, // 靠拢:摆正,只差一个标题行
};

export const noop = () => {};

/** 真卡的「抓起」样式令牌(.cardDragging:层级 + 投影),手势一概不接。 */
export const LIFTED: CardDragBinding = {
  ref: noop,
  onPointerDown: noop,
  shouldSwallowClick: () => false,
  state: 'dragging',
};
