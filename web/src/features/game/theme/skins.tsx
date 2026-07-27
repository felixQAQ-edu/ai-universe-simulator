import { WASTE, XIAN } from './motion';
import type { WorldSkin } from './contract';
import type { SkinId } from './registry';
import { RulesActions } from './rules/RulesActions';
import { RulesAmbient } from './rules/RulesAmbient';
import { RulesStats } from './rules/RulesStats';
import rules from './rules/rules.module.css';
import { WasteActions } from './waste/WasteActions';
import { WasteAmbient } from './waste/WasteAmbient';
import { WasteRadio } from './waste/WasteRadio';
import { WasteStats } from './waste/WasteStats';
import waste from './waste/waste.module.css';
import { XianActions } from './xian/XianActions';
import { XianAmbient } from './xian/XianAmbient';
import { XianStats } from './xian/XianStats';
import xian from './xian/xian.module.css';

// 皮肤表(ADR-018 §4.5 feature gate 的另一半):skinId → 一套形态组件 + class 令牌。
//
// **刀 1 只有规则怪谈**(试验田:先用一个世界证明这套共享基建能活在生产里,
// 而不是四个世界一起上——那样等于没有试验田)。刀 2 加修仙;末日 / 克苏鲁等刀 3 / 刀 4。
//
// 回滚路径:`registry.ts` 里把某世界的 `skin` 置回 null,该世界立刻回到旧实现,
// **不必回滚任何共享基建**;表是 Partial —— 两张表万一对不上也只是降级,不会崩。
export const SKINS: Partial<Record<SkinId, WorldSkin>> = {
  rules_creepy: {
    id: 'rules_creepy',
    // 入场序列(场景进入 → 稳一拍 → 灯闪三拍 → 余韵)→ 之后才放行正文逐字(ADR-018 §4.7)。
    hasIntro: true,
    // 数值滚动的时间感:准时、机械、线性(与 `--t-ease: linear` 同源);同步逻辑在通用层。
    valueRoll: { durationMs: 900, ease: 'none' },
    // 一级记忆点是入场灯闪,不挂任何轴 —— 故收到的 signatureTick 恒为 0。
    screenClass: rules.screen,
    pausedClass: rules.paused,
    bannerImgClass: rules.cctvBreathe,
    Ambient: RulesAmbient,
    Stats: RulesStats,
    Actions: RulesActions,
  },
  cultivation: {
    id: 'cultivation',
    // 修仙的一级记忆点是**状态事件**(钟鸣)不是入场序列 —— 不占开场,正文照常立即逐字。
    hasIntro: false,
    // 时间感:慢、从容、缓出长尾(与 `--t-ease` 的 cubic-bezier 同源,§4.3 成对)。
    valueRoll: {
      durationMs: Math.round(XIAN.dur(1.1) * 1000),
      ease: XIAN.ease,
      // 跨档那一回合先让钟响,数值随之而变(氛围层 t=550ms 鸣、数值 t=1100ms 起滚)。
      // 反过来 = 数字先跳、钟来配个音,仪式的因果就倒了。
      ceremonyDelayMs: 1100,
    },
    // 「盯 realm」是**注册表里的按 key 配置**(ADR-018 §5 Q5 立字:锁在注册表内,
    // 不得渗进通用数值组件)—— 通用层只按这个 key 算档序号,不知道 realm 是境界。
    signatureAxisKey: 'realm',
    screenClass: xian.screen,
    pausedClass: xian.paused,
    // 顶部场景图 = 钟鸣的**主承载面**(不透明、占屏最大、在视线起点);此处不挂持续动画,
    // 本世界的持续槽给了天穹层(流云 + 微粒同层)。
    bannerImgClass: xian.bannerGlow,
    Ambient: XianAmbient,
    Stats: XianStats,
    Actions: XianActions,
  },
  apocalypse: {
    id: 'apocalypse',
    // 一级记忆点是**电台**(环境/状态事件),不是进门那一下 → 不占开场。
    hasIntro: false,
    // 时间感:迟钝、磨损(启动带迟滞);与 `--t-ease` 同源(§4.3 成对)。
    valueRoll: { durationMs: Math.round(WASTE.dur(0.95) * 1000), ease: WASTE.easeRoll },
    // **不配 signatureAxisKey**:签名机制现在判的是「向上跨档 = 成就」,
    // 而末日的饥饿向下跨档是**恶化** —— 语义相反。为末日把机制扩成「可配置方向」,
    // 它就从**签名事件检测器**退化为**通用跨档广播器**;通用广播器一旦存在,
    // 每个世界都会想挂点什么(理智跌破闪一下、体力见底抖一下),
    // 「一世界一记忆点」与动效预算两条护栏同时受压。**保持机制专一**(Felix 2026-07-27)。
    screenClass: waste.screen,
    pausedClass: waste.paused,
    bannerImgClass: waste.bannerImg,
    Ambient: WasteAmbient,
    Stats: WasteStats,
    Actions: WasteActions,
    // 间奏槽:手摇电台,渲染在正文之后、决策圈之前(ADR-018 §3 刀 3)。
    Interlude: WasteRadio,
  },
};
