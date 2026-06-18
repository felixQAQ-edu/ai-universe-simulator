// 统一 JSON Schema · v0.2 的 TypeScript 类型
// 真理之源是 docs/CONTEXT.md §二。本文件只定义类型,不写任何逻辑。
// 约定要点(v0.2,见 ADR-001 / bakeoff FINDINGS F-001):
//   - rules[].id 是【整数】,endings[].id 是【snake_case 字符串】,两者刻意不同。
//   - character.attributes 为【必填对象】,至少含该模式核心数值(规则怪谈: hp/san)。
//   - rules[].isTrue / hiddenLogic 是作者/引擎视角字段,绝不泄露给玩家。

export const SCHEMA_VERSION = '0.2' as const;

export type Mode = 'single' | 'hybrid';

export type Archetype =
  | 'rules_creepy' // 规则怪谈
  | 'life_sim' // 人生模拟
  | 'cultivation' // 修仙
  | 'cyberpunk' // 赛博朋克
  | 'apocalypse'; // 末日生存

export type DangerLevel = 'low' | 'medium' | 'high' | 'extreme';

export type GameStatus = 'ongoing' | 'ended';

export interface World {
  title: string;
  background: string;
  dangerLevel: DangerLevel;
  tone: string;
}

/** 数值字段因模式而异(规则怪谈: hp/san;修仙: 灵根/境界…)。默认 0–100。 */
export type CharacterAttributes = Record<string, number>;

export interface Character {
  /** 必填对象,至少含该模式的核心数值。 */
  attributes: CharacterAttributes;
  traits: string[];
  inventory: string[];
}

export interface Rule {
  /** 整数,便于引擎引用与状态回传。 */
  id: number;
  content: string;
  /** 作者/引擎视角,绝不泄露给玩家。 */
  isTrue: boolean;
  /** 作者/引擎视角,绝不泄露给玩家。 */
  hiddenLogic: string;
  discovered: boolean;
}

export interface LogEntry {
  turn: number;
  narrative: string;
  playerAction: string;
}

/** 每回合连同它一起回传模型的真理之源(注意与顶层对象区分)。 */
export interface GameState {
  turn: number;
  status: GameStatus;
  /** 当前世界线一句话摘要。 */
  timeline: string;
  /** 旧回合压缩后的摘要(成本控制)。 */
  logSummary: string;
  log: LogEntry[];
}

export interface AvailableAction {
  id: string;
  text: string;
  hint: string;
}

export interface Ending {
  /** snake_case 英文字符串,作为稳定语义标识。 */
  id: string;
  /** 短标题,必填。 */
  title: string;
  /** 整句结局描述,可选。 */
  description?: string;
  /** 可判定的中文条件。 */
  condition: string;
  reached: boolean;
}

/** 一局游戏的完整设定 + 状态(顶层对象)。state 字段才是每回合回传的真理之源。 */
export interface GameWorld {
  schemaVersion: string;
  mode: Mode;
  /** 1 个 = 单体,2–3 个 = 混合。 */
  archetypes: Archetype[];
  world: World;
  character: Character;
  rules: Rule[];
  state: GameState;
  availableActions: AvailableAction[];
  endings: Ending[];
}
