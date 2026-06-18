import type { GameWorld } from '../types/schema';
import { SCHEMA_VERSION } from '../types/schema';

// 硬编码示例 state,仅用于在脚手架阶段验证「类型能用 + 工程能跑」。
// Phase 1 起由运行模型(DeepSeek,见 ADR-001)真实生成,届时删除本文件。
export const sampleWorld: GameWorld = {
  schemaVersion: SCHEMA_VERSION,
  mode: 'single',
  archetypes: ['rules_creepy'],
  world: {
    title: '雨夜便利店',
    background:
      '午夜两点,你被困在一家城郊的二十四小时便利店。雨没有停的迹象,店门外是化不开的浓雾。墙上贴着一张《致夜班顾客须知》。',
    dangerLevel: 'high',
    tone: '克制、潮湿、规则压迫感',
  },
  character: {
    attributes: { hp: 100, san: 100 },
    traits: ['观察敏锐', '容易心软'],
    inventory: ['湿透的手机(电量 12%)', '一瓶温牛奶'],
  },
  rules: [
    {
      id: 1,
      content: '凌晨三点前不要回应任何敲玻璃的声音。',
      isTrue: true,
      hiddenLogic: '回应会让「门外的东西」确认店内有活人,触发 san 急剧下降。',
      discovered: false,
    },
    {
      id: 2,
      content: '收银台的电话响起时,必须在三声内接听。',
      isTrue: false,
      hiddenLogic: '伪规则。接听反而会暴露位置;不接听无惩罚。',
      discovered: false,
    },
  ],
  state: {
    turn: 0,
    status: 'ongoing',
    timeline: '雨夜被困便利店的第一分钟,你刚读完墙上的须知。',
    logSummary: '',
    log: [],
  },
  availableActions: [
    { id: 'A', text: '走到收银台后面查看监控', hint: '' },
    { id: 'B', text: '重新逐条细读墙上的须知', hint: '' },
    { id: 'C', text: '试着拨打手机求救', hint: '' },
  ],
  endings: [
    {
      id: 'survive_dawn',
      title: '熬到天亮',
      description: '当第一缕灰白的天光透进玻璃,雾散了,你还活着。',
      condition: 'turn 达到天亮且 hp > 0 且未严重违规',
      reached: false,
    },
    {
      id: 'lost_to_fog',
      title: '消失在雾里',
      description: '',
      condition: 'san 归零或违反真规则导致被「门外的东西」带走',
      reached: false,
    },
  ],
};
