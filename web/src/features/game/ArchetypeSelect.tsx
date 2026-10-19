import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import { useGameStore } from '../../state/gameStore';
import { useHistoryStore } from '../../state/historyStore';
import { SAVE_LIST_VISIBLE, onSavePointersChanged, type SaveSummary } from '../../state/savePointers';
import { describeSave } from './saveText';
import type { ArchetypeSummary } from '../../api';
import { classifyPair, fusionKey, isFusionAllowed, pairOf, type Rect } from './fusion/drag';
import { magnetHintText, rejectHintText } from './fusion/hints';
import { FusionMerge } from './fusion/FusionMerge';
import { materialOf } from './fusion/shards';
import { useFusionDrag, type CommitRects, type HintSide } from './fusion/useFusionDrag';
import { cardTheme } from './theme/registry';
import { reducedMotion } from './theme/motion';
import styles from './game.module.css';

// 世界选择第一屏(产品门面,ADR-008 决策 4)。玩家进游戏 → 看见可玩世界 → 选一个 →
// startGame(archetype) → 复用 Phase 1 已验证整局闭环。
//
// 守 ADR-003 边界:纯展示 + store 调用,无平台 IO(目录经 store.loadArchetypes → api/ 适配层),
// 永不渲染 isTrue/hiddenLogic(选择屏本就只有玩家可见的 displayName/tagline/vibeTag)。
//
// ── 融合入口 = 把一张卡拖到另一张上(ADR-019;线 B1 真机验通)────────────────
// **被拖者 = foreign,承接者 = host** —— 动作本身表达语义,松手直接生成有序双值
// `[host, foreign]` 喂 init(ADR-013 已立 host 在前),不新增字段。
// 合法组合来自后端 `fusions` 只读投影(ADR-019),前端不自备组合表 ——
// 否则玩家能拖出一个后端 400 的组合。
//
// **ADR-013 决策 4 的「误入手势」(依次长按两张卡)本轮退役**(理由见 ADR-019 §7):
// 拖拽抓起 180ms 是长按 600ms 的**真子集**,两者并存只能靠「让长按永不触发」——
// 那是「长按已死但代码还留着」的最坏形态。**渗漏卡的形态与 CSS 保留**,
// 改由拖拽揉合产出(复用的是形式,变化的是解释)。
//
// 卡片氛围 class 由**单一主题注册表**给出(ADR-018 §4.1);未登记世界 → '' 中性卡。

export function ArchetypeSelect() {
  const archetypes = useGameStore((s) => s.archetypes);
  const fusions = useGameStore((s) => s.fusions);
  const loading = useGameStore((s) => s.archetypesLoading);
  const error = useGameStore((s) => s.archetypesError);
  const loadArchetypes = useGameStore((s) => s.loadArchetypes);
  const startGame = useGameStore((s) => s.startGame);
  const saves = useGameStore((s) => s.saves);
  const saveNotice = useGameStore((s) => s.saveNotice);
  const refreshSaves = useGameStore((s) => s.refreshSaves);
  const resumeGame = useGameStore((s) => s.resumeGame);
  /** 「回看上局」只跟随最近一条(ADR-034 已决 9)。 */
  const latestSaveId = saves.length > 0 ? saves[0].saveId : null;

  /** 已揉出的融合组合键(纯组件 state、零持久化 —— 离开选择屏即遗忘,回来要重新拖)。 */
  const [fused, setFused] = useState<readonly string[]>([]);
  /** 正在演的那一组揉合(null = 没有)。**同时只允许一组**:它在场时手势整体停摆。 */
  const [merging, setMerging] = useState<Merging | null>(null);
  /** 刚揉出来、待聚焦一次的那张卡(聚焦只做一次,之后归玩家)。 */
  const [focusKey, setFocusKey] = useState<string | null>(null);
  /** 松手在非法目标上的那一句(不弹 Toast;下一次抓起或成功揉合即散)。 */
  const [rejectNote, setRejectNote] = useState<FusionHint | null>(null);
  /** 屏幕阅读器播报区(aria-live=polite)。直接写 DOM,只在文本变化时写 —— 见 {@link announce}。 */
  const liveRef = useRef<HTMLParagraphElement>(null);
  /** 本次手势里最后写进播报区的文本(去重依据;抓起新的一张时清空)。 */
  const lastAnnounced = useRef<string | null>(null);

  useEffect(() => {
    void loadArchetypes();
  }, [loadArchetypes]);

  // 跨页签:别的页签开局 / 落回合时刷新列表显示(ADR-034 已决 8;只刷新,不写)。
  useEffect(() => onSavePointersChanged(refreshSaves), [refreshSaves]);

  // 「回看上局」入口(ADR-025 刀 3):只在有存档时才可能出现,且只跟随最近一条(ADR-034 已决 9);
  // 显示与否靠探测(口径 B)。
  // 去重与缓存在 historyStore 里:同一 saveId 在途不重发,已确认可见不再发,失败不记。
  // 只在后端声明有历史存储时才探测(capabilities.history;非 pg 环境那里恒为 501,不去撞它)。
  const historyCapable = useGameStore((s) => s.capabilities.history);
  const probeHistory = useHistoryStore((s) => s.probe);
  const openHistory = useHistoryStore((s) => s.open);
  const historyVisible = useHistoryStore((s) =>
    latestSaveId ? s.visible[latestSaveId] === true : false,
  );
  useEffect(() => {
    if (latestSaveId && historyCapable) probeHistory(latestSaveId);
  }, [latestSaveId, historyCapable, probeHistory]);

  const canFuse = useCallback(
    (host: string, foreign: string) => isFusionAllowed(fusions, host, foreign),
    [fusions],
  );
  /** 世界名 = 卡片上显示的标题(提示里不出现 id)。 */
  const nameOf = useCallback(
    (id: string) => archetypes.find((a) => a.archetype === id)?.displayName ?? id,
    [archetypes],
  );

  /**
   * 播报去重(ADR-019 实施记录):**只在文本变化时写入**播报区。同一次手势里离开目标再回到同一目标,
   * 文本没变 → 不写 → 不重播;换到另一个合法目标 → 文本变了 → 写。抓起新的一张时清空(见 onPickUp),
   * 所以下一次手势回到同一目标会重新播报。离开目标时**不清空**播报区 —— 清空再写回同一句就是重播。
   */
  const announce = useCallback((text: string) => {
    if (text === lastAnnounced.current) return;
    lastAnnounced.current = text;
    if (liveRef.current) liveRef.current.textContent = text;
  }, []);

  const revealFusion = useCallback((key: string) => {
    setFused((f) => (f.includes(key) ? f : [...f, key]));
    setFocusKey(key);
  }, []);

  const onCommit = useCallback(
    (host: string, foreign: string, rects: CommitRects | null) => {
      const key = fusionKey(host, foreign);
      setRejectNote(null);
      announce('');
      // 量不到位置(理论上不该发生)→ 不演动画,直接给卡:入口的视觉是加分项,
      // 「拖成了」这件事本身不能因为动画演不了就丢掉。
      if (!rects) {
        revealFusion(key);
        return;
      }
      setMerging({ key, host, foreign, hostRect: rects.host, foreignRect: rects.foreign });
    },
    [revealFusion, announce],
  );

  // 落在无效目标上松手 → 一句提示,放在目标卡旁(与磁吸提示同一位置体系),不挡路、不弹 Toast。
  // 在**松手**时报而不是悬停时报:悬停即报 = 路过一张卡就被念一句(拖反的组合同样只在松手时说)。
  // 拖反 / 不存在两种说法由 classifyPair 分;合法性本身仍只认 isFusionAllowed(手势层)。
  const onReject = useCallback(
    (host: string, foreign: string) => {
      const text = rejectHintText(classifyPair(fusions, host, foreign), nameOf(host), nameOf(foreign));
      if (!text) return;
      // 侧位在落位时(ref 回调)按 DOM 定:被拖那张正往原位回弹,提示放在它不经过的那一侧。
      setRejectNote({ id: `reject:${host}:${text}`, kind: 'reject', hostId: host, awayFrom: foreign, side: null, text, armed: false });
      announce(text);
    },
    [fusions, nameOf, announce],
  );

  const onPickUp = useCallback(() => {
    setRejectNote(null);
    announce('');
  }, [announce]);

  const drag = useFusionDrag({
    canFuse,
    onCommit,
    onReject,
    // 抓起新的一张:上一次的拒绝提示散掉,播报去重从头算(下一次回到同一目标会重新播报)。
    onPickUp,
    // 揉合期间手势整体停摆(护栏:同时只允许一组融合动画运行,动画未结束不可再拖)。
    enabled: merging === null,
    reduced: reducedMotion(),
  });
  /** 揉合演完:原卡归位(被揉碎的是投影不是世界本体),融合卡落位并获得一次聚焦。 */
  const onMergeDone = useCallback(() => {
    setMerging((m) => {
      if (m) revealFusion(m.key);
      return null;
    });
  }, [revealFusion]);

  // ── 方向提示(ADR-019 实施记录)────────────────────────────────────────────
  // 磁吸提示只给 classify = ok 的目标(拖反 / 不存在在悬停时一律不说,松手才说)。
  const { draggingId, targetId, targetSide, armed } = drag.view;
  const magnet: FusionHint | null =
    draggingId && targetId && classifyPair(fusions, targetId, draggingId) === 'ok'
      ? {
          id: `magnet:${targetId}:${draggingId}`,
          kind: 'magnet',
          hostId: targetId,
          awayFrom: draggingId,
          side: targetSide,
          text: magnetHintText(nameOf(draggingId), nameOf(targetId)),
          armed,
        }
      : null;
  const currentHint = magnet ?? (draggingId ? null : rejectNote);
  // 提示消失时要淡出,得记住最后一条显示过的(渲染期按变化调整 state,React 官方的派生写法)。
  const [shownHint, setShownHint] = useState<FusionHint | null>(null);
  if (currentHint && (currentHint.id !== shownHint?.id || currentHint.armed !== shownHint.armed)) {
    setShownHint(currentHint);
  }
  const hint = currentHint ?? shownHint;
  const magnetText = magnet?.text ?? null;
  useEffect(() => {
    if (magnetText) announce(magnetText);
  }, [magnetText, announce]);

  const active = archetypes.filter((a) => a.active);
  const locked = archetypes.filter((a) => !a.active);

  const dragStateOf = (id: string): CardDragState => {
    if (drag.view.draggingId === id) return 'dragging';
    if (drag.view.targetId === id) return drag.view.valid ? 'valid' : 'invalid';
    return null;
  };

  return (
    <main className={styles.screen}>
      <header className={styles.selectHeader}>
        <p className={styles.phase}>AI Universe Simulator</p>
        <h1 className={styles.title}>选择你的世界</h1>
        <p className={styles.muted}>每一个世界都由 AI 即时生成,真假难辨,不可回头。</p>
        {/* 三类方向提示的唯一播报区:始终在场(播报区须先于内容存在),只由 announce() 写。
            放在 header 里而不是 <main> 下:<main> 的子元素结构有对拍(SaveList.test)。 */}
        <p ref={liveRef} className={styles.srOnly} aria-live="polite" data-testid="fusion-live" />
      </header>

      {/* 我的存档(ADR-034):放在原「继续上局」的位置;没有任何条目时整个区块不渲染(DOM 与改动前相同)。 */}
      {saves.length > 0 && (
        <SaveList
          saves={saves}
          notice={saveNotice}
          nameOf={(a) => archetypes.find((x) => x.archetype === a)?.displayName ?? a}
          onResume={(id) => void resumeGame(id)}
        />
      )}
      {/* 回看上局:辅助入口,视觉权重低于存档条目;不预留空位,探测成功后淡入(无位移动画)。 */}
      {latestSaveId && historyVisible && (
        <button
          type="button"
          className={styles.historyEntry}
          onClick={() => openHistory(latestSaveId)}
        >
          <span className={styles.historyEntryTitle}>回看上局</span>
          <span className={styles.historyEntryHint}>看看这一局已经发生过什么</span>
        </button>
      )}

      {loading && archetypes.length === 0 ? (
        <div className={styles.centered}>
          <div className={styles.spinner} />
          <p className={styles.muted}>正在载入世界……</p>
        </div>
      ) : error && archetypes.length === 0 ? (
        <div className={styles.centered}>
          <p className={styles.muted}>{error}</p>
          <button type="button" className={styles.primaryBtn} onClick={() => void loadArchetypes()}>
            重试
          </button>
        </div>
      ) : (
        <div className={styles.cardList}>
          {active.map((a) => (
            <ArchetypeCard
              key={a.archetype}
              summary={a}
              onChoose={() => startGame(a.archetype)}
              drag={{
                ref: drag.registerCard(a.archetype),
                onPointerDown: (e) => drag.onPointerDown(a.archetype, e),
                shouldSwallowClick: () => drag.shouldSwallowClick(a.archetype),
                state: dragStateOf(a.archetype),
                // 揉合期间原卡让位给覆盖层上的投影;演完即归位(不消耗)。
                yielding: merging !== null && (merging.host === a.archetype || merging.foreign === a.archetype),
              }}
            />
          ))}
          {fused.map((key) => (
            <FusionCard
              key={key}
              combo={key}
              onChoose={() => startGame(pairOf(key))}
              focusOnMount={focusKey === key}
            />
          ))}
          {locked.map((a) => (
            <ArchetypeCard key={a.archetype} summary={a} onChoose={() => startGame(a.archetype)} />
          ))}
          {/* 放在列表末尾:不打乱卡片的 nth-child 入场错峰。换目标即换 key → 重新淡入。 */}
          {hint && (
            <FusionHintView
              key={hint.id}
              hint={hint}
              leaving={currentHint === null}
              elementOf={drag.elementOf}
            />
          )}
        </div>
      )}
      {merging && (
        <FusionMerge
          host={merging.host}
          foreign={merging.foreign}
          hostRect={merging.hostRect}
          foreignRect={merging.foreignRect}
          // 减弱动效,或任一侧没有登记材质 → 降级(重叠 + 停顿 + 淡入,不碎裂不旋转)。
          degraded={reducedMotion() || !materialOf(merging.host) || !materialOf(merging.foreign)}
          onDone={onMergeDone}
        />
      )}
    </main>
  );
}

/**
 * 「我的存档」区块(ADR-034 已决 9):静态呈现,不新增动效。默认只展示最近 8 条,
 * 其余由「查看全部」展开 —— **只管展示,不管保存**,所有指针都留在存储里。第一版没有「移除」。
 * 导出供组件测试。
 */
export function SaveList({
  saves,
  notice,
  nameOf,
  onResume,
}: {
  saves: SaveSummary[];
  notice: string | null;
  nameOf: (archetype: string) => string;
  onResume: (saveId: string) => void;
}) {
  const [expanded, setExpanded] = useState(false);
  const shown = expanded ? saves : saves.slice(0, SAVE_LIST_VISIBLE);
  const hidden = saves.length - shown.length;
  return (
    <section className={styles.saves} aria-label="我的存档">
      <h2 className={styles.savesTitle}>我的存档</h2>
      {notice && <p className={styles.savesNotice}>{notice}</p>}
      <ul className={styles.saveList}>
        {shown.map((s) => (
          <li key={s.saveId}>
            <button
              type="button"
              className={[styles.resumeBtn, s.availability === 'unavailable' ? styles.saveUnavailable : '']
                .filter(Boolean)
                .join(' ')}
              onClick={() => onResume(s.saveId)}
            >
              <span className={styles.resumeTitle}>{s.title || '上一局(续上后显示世界名)'}</span>
              <span className={styles.resumeHint}>{describeSave(s, nameOf)}</span>
              {s.availability === 'unavailable' && (
                <span className={styles.saveStatus}>暂时无法找回 · 点此重试</span>
              )}
            </button>
          </li>
        ))}
      </ul>
      {hidden > 0 && (
        <button type="button" className={styles.savesMore} onClick={() => setExpanded(true)}>
          查看全部({saves.length})
        </button>
      )}
    </section>
  );
}

/** 一条方向提示(磁吸 = 悬停在合法目标上;reject = 松手在非法目标上)。 */
interface FusionHint {
  /** 换 id = 换一条提示(重新淡入);同 id 只改 armed 不重放。 */
  id: string;
  kind: 'magnet' | 'reject';
  /** 提示挂在哪张卡旁(这次的被拖到的那张)。 */
  hostId: string;
  /** 被拖的那张;side 为 null 时按它的原位决定侧位。 */
  awayFrom: string;
  side: HintSide | null;
  text: string;
  /** 磁吸已进入可提交态 → 提示加强。 */
  armed: boolean;
}

/**
 * 方向提示的视觉层(aria-hidden:播报由页面上那一个 aria-live 区承担,不在这里重复)。
 * 相对 `.cardList` 绝对定位在目标卡上沿 / 下沿之外的卡间缝里,不压任何一张卡的标题;
 * 位置在挂上 DOM 那一刻(ref 回调)量一次 —— 卡片的磁吸位移是 transform,不影响 offsetTop。
 */
function FusionHintView({
  hint,
  leaving,
  elementOf,
}: {
  hint: FusionHint;
  leaving: boolean;
  elementOf: (id: string) => HTMLElement | null;
}) {
  const place = useCallback(
    (el: HTMLElement | null) => {
      if (!el) return;
      const host = elementOf(hint.hostId);
      if (!host) return;
      let s = hint.side;
      if (!s) {
        const away = elementOf(hint.awayFrom);
        s = away && away.offsetTop < host.offsetTop ? 'below' : 'above';
      }
      // 侧位与位置一起在这里写(data-side 驱动 CSS 的半个卡间缝偏移),不经 state、不多渲染一次。
      el.dataset.side = s;
      el.style.top = `${s === 'above' ? host.offsetTop : host.offsetTop + host.offsetHeight}px`;
    },
    [elementOf, hint.hostId, hint.awayFrom, hint.side],
  );
  return (
    <p
      ref={place}
      aria-hidden="true"
      data-testid="fusion-hint"
      data-kind={hint.kind}
      className={[
        styles.fusionNote,
        hint.kind === 'magnet' ? styles.fusionNoteMagnet : '',
        hint.armed ? styles.fusionNoteArmed : '',
        leaving ? styles.fusionNoteLeaving : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {hint.text}
    </p>
  );
}

interface Merging {
  key: string;
  host: string;
  foreign: string;
  hostRect: Rect;
  foreignRect: Rect;
}

/** 卡片在拖拽中的角色(纯样式令牌;差异只在样式故不开组件槽,ADR-018 §4.17)。 */
export type CardDragState = 'dragging' | 'valid' | 'invalid' | null;

export interface CardDragBinding {
  ref: (el: HTMLElement | null) => void;
  onPointerDown: (e: ReactPointerEvent<HTMLElement>) => void;
  /** 抓起后那一次 click 必须吞掉(否则松手即进世界)。 */
  shouldSwallowClick: () => boolean;
  state: CardDragState;
  /** 揉合期间让位给覆盖层上的投影(演完归位,原卡不消耗)。 */
  yielding?: boolean;
}

/** 单张氛围卡片(纯展示)。已激活=可点钩子卡;未激活=灰显「敬请期待」。导出供组件测试。 */
export function ArchetypeCard({
  summary,
  onChoose,
  drag,
}: {
  summary: ArchetypeSummary;
  onChoose: () => void;
  /** 融合拖拽接线;缺省=不可拖(未开放卡、纯展示用例)。 */
  drag?: CardDragBinding;
}) {
  const { archetype, displayName, tagline, vibeTag, active } = summary;

  const handleClick = () => {
    // 抓起过 → 吞掉随后的 click,不进入世界(单击语义不变)。
    if (drag?.shouldSwallowClick()) return;
    onChoose();
  };

  if (!active) {
    return (
      <div className={`${styles.card} ${styles.cardLocked}`} aria-disabled="true">
        <div className={styles.cardTop}>
          <h2 className={styles.cardTitle}>{displayName}</h2>
          <span className={styles.cardSoon}>敬请期待</span>
        </div>
      </div>
    );
  }

  return (
    <button
      type="button"
      ref={drag?.ref}
      className={[
        styles.card,
        cardTheme(archetype).cardClass,
        drag ? styles.cardDraggable : '',
        drag?.state ? DRAG_CLASS[drag.state] : '',
        drag?.yielding ? styles.cardYielding : '',
      ]
        .filter(Boolean)
        .join(' ')}
      onClick={handleClick}
      onPointerDown={drag?.onPointerDown}
      onContextMenu={(e) => drag && e.preventDefault()}
    >
      <div className={styles.cardTop}>
        <h2 className={styles.cardTitle}>{displayName}</h2>
        {vibeTag && <span className={styles.cardTag}>{vibeTag}</span>}
      </div>
      {tagline && <p className={styles.cardTagline}>{tagline}</p>}
    </button>
  );
}

const DRAG_CLASS: Record<NonNullable<CardDragState>, string> = {
  dragging: styles.cardDragging,
  valid: styles.cardTargetValid,
  invalid: styles.cardTargetInvalid,
};

/**
 * per-combo 融合卡**文案**(ADR-014 参数化;展示层配置,不入后端)。
 * 卡片氛围 class 不在这里 —— 它与单体卡同源,由主题注册表按组合键给出(ADR-018 §4.1)。
 */
const FUSION_CARDS: Record<
  string,
  { titles: [string, string, string]; ariaLabel: string; tagline: string }
> = {
  'cultivation×rules_creepy': {
    titles: ['修仙', '规则怪谈', '识海遗蜕'],
    ariaLabel: '识海遗蜕(融合世界)',
    tagline: '两个世界之间,有什么渗了过来。',
  },
  'rules_creepy×apocalypse': {
    titles: ['规则怪谈', '末日生存', '缺页的人防工程'],
    ariaLabel: '缺页的人防工程(融合世界)',
    tagline: '缺的那几页,和消失的人对得上号。',
  },
  'cultivation×cthulhu': {
    titles: ['修仙', '克苏鲁', '验讫'],
    ariaLabel: '验讫(融合世界)',
    tagline: '满门道贺之后,飞升台上留下一方印。',
  },
};

/**
 * 融合卡(揉合的产物;ADR-013 决策 4 立的渗漏卡形态原样保留,ADR-019 只换触发方式):
 * 视觉异常的「多出来的一张卡」——两世界氛围互噬,标题在「世界A/世界B/融合定稿名」间
 * 闪烁撕裂浮现(三层叠放,CSS 轮换 opacity,不引动画库)。点击 → 发该组合有序双值 init。
 * 导出供组件测试。
 */
export function FusionCard({
  combo,
  onChoose,
  focusOnMount = false,
}: {
  combo: string;
  onChoose: () => void;
  /** 刚被揉出来 → 获得一次聚焦(只此一次,之后归玩家)。 */
  focusOnMount?: boolean;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (focusOnMount) ref.current?.focus({ preventScroll: false });
  }, [focusOnMount]);

  const card = FUSION_CARDS[combo];
  if (!card) return null; // 未配文案的组合不渲染(登记齐组合表 + 卡文案再上)
  return (
    <button
      type="button"
      ref={ref}
      className={`${styles.card} ${cardTheme(combo).cardClass}`}
      onClick={onChoose}
      aria-label={card.ariaLabel}
    >
      <div className={styles.cardTop}>
        <h2 className={`${styles.cardTitle} ${styles.fusionTitle}`} aria-hidden="true">
          {/* 三层标题叠放,CSS 轮换浮现(撕裂闪烁);aria 语义由按钮 label 承载。 */}
          <span className={styles.fusionTitleA}>{card.titles[0]}</span>
          <span className={styles.fusionTitleB}>{card.titles[1]}</span>
          <span className={styles.fusionTitleC}>{card.titles[2]}</span>
        </h2>
        <span className={styles.cardTag}>渗漏 · 勿入</span>
      </div>
      <p className={styles.cardTagline}>{card.tagline}</p>
    </button>
  );
}
