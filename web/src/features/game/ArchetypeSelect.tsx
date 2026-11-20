import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import { useGameStore } from '../../state/gameStore';
import { useHistoryStore } from '../../state/historyStore';
import { SAVE_LIST_VISIBLE, onSavePointersChanged, type SaveSummary } from '../../state/savePointers';
import { lobbyPrefs, onLobbyPrefsChanged, type LobbyZone } from '../../state/lobbyPrefs';
import { describeSave } from './saveText';
import { discoveredFusions, hasUndiscovered, initialZone, zoneOfWorld } from './lobby';
import type { ArchetypeSummary } from '../../api';
import { classifyPair, fusionKey, isFusionAllowed, pairOf } from './fusion/drag';
import { magnetHintText, rejectHintText } from './fusion/hints';
import { useFusionDrag, type HintSide } from './fusion/useFusionDrag';
import { cardTheme } from './theme/registry';
import { reducedMotion } from './theme/motion';
import styles from './game.module.css';
import lobbyStyles from './lobby.module.css';

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
// **入口不演动画**(ADR-019 订正 2026-11-09,Felix 裁定):松手 → 被拖卡照常归位,
// 成品卡直接挂在已开放卡之后、获得焦点(浏览器把它滚进视野),播报区念一句。
//
// 卡片氛围 class 由**单一主题注册表**给出(ADR-018 §4.1);未登记世界 → '' 中性卡。
//
// ── 三区大厅(ADR-041)──────────────────────────────────────────────────────
// 浮世 / 深界按后端 `experienceMode` 分卡;渗界只放已发现的融合世界(浏览器记录 ∪ 存档推导)。
// 拖拽合成只在深界发生(浮世卡不注册拖拽)。刚合成的成品卡只在这一次停留深界期间挂在深界末尾,
// 离开深界即清空 —— 之后只在渗界出现(决策 6「成品卡的去处」)。

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

  /**
   * 已揉出的融合组合键,按揉出先后(纯组件 state、零持久化 —— 离开选择屏即遗忘,回来要重新拖)。
   * 卡列里排在已开放卡之后;同一组合再揉一次不重复、不挪位置。
   */
  const [fused, setFused] = useState<readonly string[]>([]);
  /**
   * 待聚焦的那张成品卡。`n` 每次揉合 +1:同一组合再揉一次,已挂着的那张也要再聚焦一次
   * (只比 key 的话,第二次 setState 是同值,effect 不会再跑)。聚焦一次之后归玩家。
   */
  const [focusReq, setFocusReq] = useState<{ key: string; n: number } | null>(null);
  /** 松手在非法目标上的那一句(不弹 Toast;下一次抓起或成功揉合即散)。 */
  const [rejectNote, setRejectNote] = useState<FusionHint | null>(null);

  // ── 分区(ADR-041 决策 2)────────────────────────────────────────────────────
  /** 浏览器记下的已发现组合(发现先后);别的页签改了也跟着刷新。 */
  const [storedDiscovered, setStoredDiscovered] = useState<readonly string[]>(() => lobbyPrefs.readDiscovered());
  /** 第三区出现是否已开演过(ADR-018 §4.9:记「已开演」,不记「已排期」)。 */
  const [revealed, setRevealed] = useState(() => lobbyPrefs.readRevealed());
  /**
   * 别的页签带来了发现:本页签直接显示渗界标签,不播出现动画、不写 revealed(决策 2「不在本页签播」;
   * revealed 由开演的那个页签写)。
   */
  const [quietReveal, setQuietReveal] = useState(false);
  useEffect(
    () =>
      onLobbyPrefsChanged(() => {
        const next = lobbyPrefs.readDiscovered();
        setStoredDiscovered(next);
        setRevealed(lobbyPrefs.readRevealed());
        if (next.length > 0) setQuietReveal(true);
      }),
    [],
  );
  /** 本页签正在演第三区出现(决策 6 的 900ms 那一刻起):给标签条挂 FLIP、给「渗界」两字挂淡入。 */
  const [revealing, setRevealing] = useState(false);
  /** 合成那一刻用:这一次合成会不会带出第三区(reduced 下两句并播)。在 effect 里跟着 state 更新。 */
  const pendingRevealRef = useRef(false);
  /** reduced 下第二句已跟着合成那一句播过了。 */
  const revealAnnouncedRef = useRef(false);
  /** 进门时读到的「上次停的区」;本次停留中玩家切过区则以 chosenZone 为准。 */
  const [storedZone] = useState<LobbyZone | null>(() => lobbyPrefs.readZone());
  const [chosenZone, setChosenZone] = useState<LobbyZone | null>(null);
  /** 屏幕阅读器播报区(aria-live=polite)。直接写 DOM,只在文本变化时写 —— 见 {@link announce}。 */
  const liveRef = useRef<HTMLParagraphElement>(null);
  /** 本次手势里最后写进播报区的文本(去重依据;抓起新的一张时清空)。 */
  const lastAnnounced = useRef<string | null>(null);

  useEffect(() => {
    void loadArchetypes();
  }, [loadArchetypes]);

  const discovered = useMemo(
    () => discoveredFusions({ stored: storedDiscovered, saves, fusions, hasCard: hasFusionCard }),
    [storedDiscovered, saves, fusions],
  );
  const catalogLoaded = archetypes.length > 0;
  /** 渗界标签在不在:已发现非空,且已经开演过(或正在演 / 别的页签带来的)。 */
  const rongShown = discovered.length > 0 && (revealed || revealing || quietReveal);
  // 目录到手前不知道哪些组合还合法:先按浏览器记录估「渗界在不在」,免得记的是渗界的老玩家进门先闪一下浮世。
  const rongCount = catalogLoaded ? (rongShown ? discovered.length : 0) : revealed ? storedDiscovered.length : 0;
  const zone = initialZone(chosenZone ?? storedZone, rongCount);

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

  // 松手提交:成品卡直接挂在已开放卡之后,不演任何揉合动画(ADR-019 订正 2026-11-09)。
  // 被拖卡由手势层照常归位;成品卡挂上后聚焦,浏览器按默认行为把它滚进视野(不平滑)。
  const onCommit = useCallback(
    (host: string, foreign: string) => {
      const key = fusionKey(host, foreign);
      setRejectNote(null);
      setFused((f) => (f.includes(key) ? f : [...f, key]));
      // 写入已发现(决策 2):存储写不进时本次停留仍按已发现处理(state 自己追加,不靠读回)。
      lobbyPrefs.addDiscovered(key);
      setStoredDiscovered((d) => (d.includes(key) ? d : [...d, key]));
      setFocusReq((r) => ({ key, n: (r?.n ?? 0) + 1 }));
      // 读屏播报(沿用同一个 aria-live 区与去重)。文案 Felix 2026-11-09 定稿;刻意不说「列表末尾」,
      // 以后调整插入位置时,播报不会指错路(ADR-019 订正 2026-11-09 §2)。
      const name = FUSION_CARDS[key]?.titles[2];
      let text = name ? `融合成功。《${name}》已加入世界列表。` : '';
      // reduced-motion 下第三区与成品卡同时出现:两句并成一次写入(连写两次,读屏多半只念后一句)。
      // 原句不改,第二句接在后面(审定 2)。非 reduced 时第二句在 900ms 开演那一刻单独播。
      if (reducedMotion() && pendingRevealRef.current) {
        text += REVEAL_ANNOUNCEMENT;
        revealAnnouncedRef.current = true;
      }
      if (text) announce(text);
    },
    [announce],
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
    reduced: reducedMotion(),
  });

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

  const visibleZones: LobbyZone[] = rongShown ? ['fushi', 'shen', 'rong'] : ['fushi', 'shen'];

  // ── 第三区第一次出现(决策 6;大厅的一级记忆点)──────────────────────────────────
  // 一条路径管两种来源:本次合成出第一个组合(从松手算 900ms),或进门时已发现非空而还没开演过
  // (上次 900ms 之前就离开了 / 玩过融合局的老玩家,审定 8;从卡片列表挂好算 900ms,在当时所在的区播)。
  // 依赖里不放 zone:0–900ms 之间切区照常在 900ms 播。卸载即清计时器 → 没开演 → 不写 revealed。
  const needsReveal = catalogLoaded && discovered.length > 0 && !revealed && !quietReveal;
  const nothingYet = discovered.length === 0 && !revealed && !quietReveal;
  useLayoutEffect(() => {
    pendingRevealRef.current = nothingYet;
  }, [nothingYet]);
  /** FLIP 的起点:开演前一刻,两个旧标签的位置。 */
  const flipFrom = useRef<Partial<Record<LobbyZone, number>>>({});
  useEffect(() => {
    if (!needsReveal) return;
    const start = () => {
      const reduced = reducedMotion();
      flipFrom.current = {};
      if (!reduced) {
        for (const z of ['fushi', 'shen'] as const) {
          const el = tabRefs.current[z];
          if (el) flipFrom.current[z] = el.getBoundingClientRect().left;
        }
      }
      lobbyPrefs.markRevealed(); // 开演这一刻才写(ADR-018 §4.9)
      setRevealed(true);
      setRevealing(!reduced);
      if (!revealAnnouncedRef.current) announce(REVEAL_ANNOUNCEMENT);
      revealAnnouncedRef.current = false;
    };
    // reduced-motion:没有 900ms 停顿,标签与成品卡同时出现。
    if (reducedMotion()) {
      start();
      return;
    }
    const t = window.setTimeout(start, REVEAL_WAIT_MS);
    return () => window.clearTimeout(t);
  }, [needsReveal, announce]);

  // 标签挂上之后:前两个标签从旧位置平移到新位置(FLIP,只动 transform)。Web Animations,不留内联样式;
  // 「渗界」两字的淡入是 CSS(.tabRevealing,只动 opacity,从平移的一半开始)。
  useLayoutEffect(() => {
    if (!revealing) return;
    const running: Animation[] = [];
    for (const z of ['fushi', 'shen'] as const) {
      const el = tabRefs.current[z];
      const from = flipFrom.current[z];
      if (!el || from === undefined || typeof el.animate !== 'function') continue;
      const dx = from - el.getBoundingClientRect().left;
      if (dx === 0) continue;
      running.push(
        el.animate([{ transform: `translateX(${dx}px)` }, { transform: 'none' }], {
          duration: REVEAL_FLIP_MS,
          easing: 'ease-out',
        }),
      );
    }
    return () => running.forEach((a) => a.cancel());
  }, [revealing]);

  /** 切区(决策 5):记住这一区;离开深界即清空本次合成的成品卡与拖拽提示。 */
  const selectZone = (next: LobbyZone) => {
    if (next === zone) return;
    setChosenZone(next);
    lobbyPrefs.writeZone(next);
    if (next !== 'shen') {
      setFused([]);
      setFocusReq(null);
      setRejectNote(null);
      setShownHint(null);
    }
  };

  // 标签键盘(决策 3 / 审定 7):←/→ 在可见标签间移焦点并循环,Home / End 到两端;
  // Enter / Space 才切区(按钮原生把它们变成 click)。roving tabindex:只有选中的标签在 Tab 序列里。
  const tabRefs = useRef<Partial<Record<LobbyZone, HTMLButtonElement | null>>>({});
  const onTabKeyDown = (e: ReactKeyboardEvent<HTMLButtonElement>, from: LobbyZone) => {
    const i = visibleZones.indexOf(from);
    const n = visibleZones.length;
    let to: number;
    if (e.key === 'ArrowRight') to = (i + 1) % n;
    else if (e.key === 'ArrowLeft') to = (i - 1 + n) % n;
    else if (e.key === 'Home') to = 0;
    else if (e.key === 'End') to = n - 1;
    else return;
    e.preventDefault();
    tabRefs.current[visibleZones[to]]?.focus();
  };

  const inZone = (a: ArchetypeSummary) => zoneOfWorld(a.experienceMode) === zone;
  const active = archetypes.filter((a) => a.active && inZone(a));
  const locked = archetypes.filter((a) => !a.active && inZone(a));
  const draggable = zone === 'shen';

  const dragStateOf = (id: string): CardDragState => {
    if (drag.view.draggingId === id) return 'dragging';
    if (drag.view.targetId === id) return drag.view.valid ? 'valid' : 'invalid';
    return null;
  };

  return (
    <div className={`${lobbyStyles.lobby} ${ZONE_CLASS[zone]}`} data-zone={zone}>
      <LobbyAmbient />
      <main className={styles.screen}>
        <header className={styles.selectHeader}>
          <p className={styles.phase}>AI Universe Simulator</p>
          <h1 className={styles.title}>选择你的世界</h1>
          {/* 副标题随区换句(决策 3);标题、存档、回看上局不随区变。 */}
          <p className={styles.muted}>{ZONE_SUBTITLE[zone]}</p>
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
          <>
            <div role="tablist" aria-label="分区" className={lobbyStyles.tabs}>
              {visibleZones.map((z) => (
                <button
                  key={z}
                  ref={(el) => {
                    tabRefs.current[z] = el;
                  }}
                  type="button"
                  role="tab"
                  id={TAB_ID[z]}
                  aria-selected={z === zone}
                  tabIndex={z === zone ? 0 : -1}
                  className={lobbyStyles.tab}
                  onClick={() => selectZone(z)}
                  onKeyDown={(e) => onTabKeyDown(e, z)}
                >
                  <span className={z === 'rong' && revealing ? lobbyStyles.tabRevealing : undefined}>
                    {ZONE_NAME[z]}
                  </span>
                </button>
              ))}
            </div>
            <div role="tabpanel" aria-labelledby={TAB_ID[zone]} className={lobbyStyles.panel}>
              {zone === 'shen' && <p className={lobbyStyles.hint}>别把两个世界放得太近。</p>}
              {/* key 随区换:切区整列重挂,卡片入场 cardEnter 重播(审定 3)。 */}
              <div key={zone} className={styles.cardList}>
                {zone === 'rong'
                  ? discovered.map((key) => (
                      <FusionCard key={key} combo={key} onChoose={() => startGame(pairOf(key))} />
                    ))
                  : active.map((a) => (
                      <ArchetypeCard
                        key={a.archetype}
                        summary={a}
                        onChoose={() => startGame(a.archetype)}
                        drag={
                          draggable
                            ? {
                                ref: drag.registerCard(a.archetype),
                                onPointerDown: (e) => drag.onPointerDown(a.archetype, e),
                                shouldSwallowClick: () => drag.shouldSwallowClick(a.archetype),
                                state: dragStateOf(a.archetype),
                              }
                            : undefined
                        }
                      />
                    ))}
                {draggable &&
                  fused.map((key) => (
                    <FusionCard
                      key={key}
                      combo={key}
                      onChoose={() => startGame(pairOf(key))}
                      focusSignal={focusReq?.key === key ? focusReq.n : undefined}
                      fresh
                    />
                  ))}
                {zone !== 'rong' &&
                  locked.map((a) => (
                    <ArchetypeCard key={a.archetype} summary={a} onChoose={() => startGame(a.archetype)} />
                  ))}
                {/* 放在列表末尾:不打乱卡片的 nth-child 入场错峰。换目标即换 key → 重新淡入。 */}
                {draggable && hint && (
                  <FusionHintView
                    key={hint.id}
                    hint={hint}
                    leaving={currentHint === null}
                    elementOf={drag.elementOf}
                  />
                )}
              </div>
              {/* 渗界底句只在还有未发现的组合时显示(审定 6)。 */}
              {zone === 'rong' && hasUndiscovered(fusions, discovered, hasFusionCard) && (
                <p className={lobbyStyles.foot}>还有别的世界,正在深界里互相渗着。</p>
              )}
            </div>
          </>
        )}
      </main>
    </div>
  );
}

/**
 * 背景气氛层(ADR-041 决策 8):纯装饰,aria-hidden、不接指针。三区的层都在,哪几层亮由区类决定(纯 CSS)。
 * 放在 <main> 外面:<main> 的子元素结构有对拍。
 */
function LobbyAmbient() {
  return (
    <div className={lobbyStyles.fx} aria-hidden="true" data-testid="lobby-ambient">
      <div className={lobbyStyles.fxWash} />
      <div className={lobbyStyles.fxRays}>
        <i />
        <i />
        <i />
        <i />
      </div>
      <div className={lobbyStyles.fxFlash} />
      <div className={lobbyStyles.fxBolt}>
        <svg viewBox="0 0 52 104" fill="none">
          <polyline points="38,0 30,30 40,34 22,70 31,74 16,104" stroke="#d6dbff" strokeWidth="1" vectorEffect="non-scaling-stroke" />
          <polyline points="30,30 20,44" stroke="#d6dbff" strokeWidth="0.7" vectorEffect="non-scaling-stroke" />
        </svg>
      </div>
    </div>
  );
}

const ZONE_CLASS: Record<LobbyZone, string> = {
  fushi: lobbyStyles.zoneFushi,
  shen: lobbyStyles.zoneShen,
  rong: lobbyStyles.zoneRong,
};

/** 第三区出现的时间线(决策 6,样张「甲」的默认值)。 */
const REVEAL_WAIT_MS = 900;
const REVEAL_FLIP_MS = 600;
/** 第一次出现渗界时的第二句播报(审定 2:原句不改,另播这一句)。 */
const REVEAL_ANNOUNCEMENT = '出现了新的分区:渗界。';

const ZONE_NAME: Record<LobbyZone, string> = { fushi: '浮世', shen: '深界', rong: '渗界' };

const TAB_ID: Record<LobbyZone, string> = {
  fushi: 'lobby-tab-fushi',
  shen: 'lobby-tab-shen',
  rong: 'lobby-tab-rong',
};

const ZONE_SUBTITLE: Record<LobbyZone, string> = {
  fushi: '每一个世界都由 AI 即时生成,烟火寻常,慢慢过活。',
  shen: '每一个世界都由 AI 即时生成,真假难辨,不可回头。',
  rong: '两个世界渗在一起,就不再是原来任何一个。',
};

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

/** 卡片在拖拽中的角色(纯样式令牌;差异只在样式故不开组件槽,ADR-018 §4.17)。 */
export type CardDragState = 'dragging' | 'valid' | 'invalid' | null;

export interface CardDragBinding {
  ref: (el: HTMLElement | null) => void;
  onPointerDown: (e: ReactPointerEvent<HTMLElement>) => void;
  /** 抓起后那一次 click 必须吞掉(否则松手即进世界)。 */
  shouldSwallowClick: () => boolean;
  state: CardDragState;
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
/** 前端配了卡文案的组合才进渗界(决策 2 的交集)。 */
function hasFusionCard(key: string): boolean {
  return key in FUSION_CARDS;
}

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
  focusSignal,
  fresh = false,
}: {
  combo: string;
  onChoose: () => void;
  /**
   * 刚被揉出来 → 获得一次聚焦;值每变一次聚焦一次(同一组合再揉一次也会再聚焦),之后归玩家。
   * 聚焦时浏览器按默认行为把卡滚进视野(瞬时,不平滑;reduced-motion 下同样如此)。
   */
  focusSignal?: number;
  /**
   * 「刚揉出」:标题先停在真名满一个标题循环,再接进正常轮换(ADR-019 订正 §4,纯 CSS,见 `.cardFresh`)。
   * 从挂载那一刻起算(订正 2026-11-09:入口不演动画,卡一挂上字就在);只影响第一个循环;
   * reduced-motion 下这一层不生效(标题本就静态停在真名)。
   */
  fresh?: boolean;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (focusSignal !== undefined) ref.current?.focus();
  }, [focusSignal]);

  const card = FUSION_CARDS[combo];
  if (!card) return null; // 未配文案的组合不渲染(登记齐组合表 + 卡文案再上)
  return (
    <button
      type="button"
      ref={ref}
      className={[styles.card, cardTheme(combo).cardClass, fresh ? styles.cardFresh : ''].filter(Boolean).join(' ')}
      onClick={onChoose}
      aria-label={card.ariaLabel}
      data-fusion-key={combo}
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
