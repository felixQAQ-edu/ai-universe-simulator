// 存档页(ADR-042 决策 2 / 3 / 5 / 6 / 8 / 10):选择屏的旁支屏,列出这台设备上的全部存档,
// 每条可以「从这台设备移除」,移除后底部 5 秒内可撤销。
// 不碰存储:移除只进撤销窗口(useRemovalWindow),删除只在窗口走完时由 store 做。平台 IO 一律不在这里(ADR-003)。
import { useEffect, useLayoutEffect, useRef, type MouseEvent } from 'react';
import { useGameStore } from '../../../state/gameStore';
import { useHistoryStore } from '../../../state/historyStore';
import { onSavePointersChanged, type SaveSummary } from '../../../state/savePointers';
import { screens, useSavesView } from '../../../state/screens';
import styles from '../game.module.css';
import lobbyStyles from '../lobby.module.css';
import { describeSave, removalMessage, UNTITLED_SAVE } from '../saveText';
import { ZONE_CLASS } from '../zoneClass';
import sv from './saves.module.css';
import { useRemovalWindow } from './useRemovalWindow';

type GroupId = 'saves-ongoing' | 'saves-ended';

/** 下一次渲染后焦点落哪(决策 10 的分流;在 layout effect 里落,落完即清)。 */
type FocusTarget =
  | { to: 'undo' }
  | { to: 'remove'; saveId: string }
  | { to: 'open'; saveId: string }
  | { to: 'head'; group: GroupId };

/** 键盘 / 读屏触发的 click 没有点击次数(detail 0);指针点击 ≥ 1。 */
const byKeyboard = (e: MouseEvent) => e.detail === 0;

export function SavesScreen() {
  const zone = useSavesView((s) => s.zone);
  const focusHint = useSavesView((s) => s.focus);
  const saves = useGameStore((s) => s.saves);
  const archetypes = useGameStore((s) => s.archetypes);
  const saveNotice = useGameStore((s) => s.saveNotice);
  const refreshSaves = useGameStore((s) => s.refreshSaves);
  const resumeGame = useGameStore((s) => s.resumeGame);
  const { batch, paused, ctl } = useRemovalWindow();

  // 跨页签:别的页签改了存档键时只刷新显示(ADR-034 已决 8);待删批不动,到点时由比对后删兜住。
  useEffect(() => onSavePointersChanged(refreshSaves), [refreshSaves]);

  // 画面上显示的列表 = 指针列表减去待删批(决策 3)。
  const pendingIds = new Set(batch?.entries.map((e) => e.saveId) ?? []);
  const shown = saves.filter((s) => !pendingIds.has(s.saveId));
  const ongoing = shown.filter((s) => !s.ended);
  const ended = shown.filter((s) => s.ended);

  // 「回看上局」只跟随画面上的最近一条(ADR-034 已决 9 / ADR-042 决策 6):刚把最近一局移掉,入口不该还指着它;
  // 窗口作废时它指回原来那条。探测语义不变,大厅多半已经探过(historyStore 去重)。
  const latest = shown.length > 0 ? shown[0].saveId : null;
  const historyCapable = useGameStore((s) => s.capabilities.history);
  const probeHistory = useHistoryStore((s) => s.probe);
  const historyVisible = useHistoryStore((s) => (latest ? s.visible[latest] === true : false));
  useEffect(() => {
    if (latest && historyCapable) probeHistory(latest);
  }, [latest, historyCapable, probeHistory]);

  // ── 焦点 ────────────────────────────────────────────────────────────
  const h1Ref = useRef<HTMLHeadingElement>(null);
  const historyRef = useRef<HTMLButtonElement>(null);
  const undoRef = useRef<HTMLButtonElement>(null);
  const openRefs = useRef(new Map<string, HTMLButtonElement>());
  const removeRefs = useRef(new Map<string, HTMLButtonElement>());
  const headRefs = useRef(new Map<GroupId, HTMLHeadingElement>());
  const focusNext = useRef<FocusTarget | null>(null);

  // 进来 → 标题;从历史页回来 → 「回看上局」(入口不在了就退回标题)。提示一次性,落完即清(决策 5)。
  useEffect(() => {
    if (focusHint === 'history' && historyRef.current) historyRef.current.focus();
    else h1Ref.current?.focus();
    if (focusHint !== null) useSavesView.setState({ focus: null });
    // 只在挂上时落一次:之后提示被清成 null 不该再把焦点拽回标题。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useLayoutEffect(() => {
    const t = focusNext.current;
    if (!t) return;
    focusNext.current = null;
    if (t.to === 'undo') undoRef.current?.focus();
    else if (t.to === 'remove') removeRefs.current.get(t.saveId)?.focus();
    else if (t.to === 'open') openRefs.current.get(t.saveId)?.focus();
    else headRefs.current.get(t.group)?.focus();
  });

  // ── 动作 ────────────────────────────────────────────────────────────
  const nameOf = (a: string) => archetypes.find((x) => x.archetype === a)?.displayName ?? a;

  // 点开一行:先续局;续上了(离开 idle)才关存档页、吃掉那条历史 —— 游戏里的「返回」照旧回大厅(审定 5)。
  // 续不上(404 / 网络)时 status 回到 idle,玩家仍在存档页,提示与「暂时无法找回」都在这一页上。
  const open = async (saveId: string) => {
    await resumeGame(saveId);
    if (useGameStore.getState().status !== 'idle') screens.leaveSavesForGame();
  };

  /** 移除一行。键盘 → 焦点到「撤销」;指针 → 同组下一行的「移除」,没有就上一行,组空了就组头(决策 10 / 审定 8)。 */
  const removeOne = (s: SaveSummary, group: GroupId, items: SaveSummary[], e: MouseEvent) => {
    if (byKeyboard(e)) focusNext.current = { to: 'undo' };
    else {
      const i = items.findIndex((x) => x.saveId === s.saveId);
      const neighbour = items[i + 1] ?? items[i - 1];
      focusNext.current = neighbour ? { to: 'remove', saveId: neighbour.saveId } : { to: 'head', group };
    }
    ctl.add([s], 'one');
  };

  /** 全部移除:当时「已结局」组里显示着的全部行(含暂时无法找回的)。键盘 → 「撤销」;指针 → 已结局组头。 */
  const removeEnded = (e: MouseEvent) => {
    focusNext.current = byKeyboard(e) ? { to: 'undo' } : { to: 'head', group: 'saves-ended' };
    ctl.add(ended, 'endedAll');
  };

  /** 撤销:整批放回原排序,焦点到放回的第一条(按画面顺序)。不写存储。 */
  const undo = () => {
    const back = new Set(ctl.undo().map((e) => e.saveId));
    const first = saves.find((s) => back.has(s.saveId));
    if (first) focusNext.current = { to: 'open', saveId: first.saveId };
  };

  const group = (id: GroupId, name: string, items: SaveSummary[], clearable: boolean) => (
    <section className={sv.svGroup} aria-labelledby={id}>
      <div className={sv.svGroupHead}>
        <h2
          id={id}
          tabIndex={-1}
          ref={(el) => {
            if (el) headRefs.current.set(id, el);
            else headRefs.current.delete(id);
          }}
        >
          {name}
          <b>{items.length}</b>
        </h2>
        {clearable && items.length > 0 && (
          <button type="button" className={sv.svClear} onClick={removeEnded}>
            全部移除
          </button>
        )}
      </div>
      {items.length > 0 && (
        <ul className={sv.svList}>
          {items.map((s) => {
            const off = s.availability === 'unavailable';
            return (
              <li key={s.saveId} className={off ? `${sv.svRow} ${sv.svRowOff}` : sv.svRow}>
                <button
                  type="button"
                  className={sv.svOpen}
                  ref={(el) => {
                    if (el) openRefs.current.set(s.saveId, el);
                    else openRefs.current.delete(s.saveId);
                  }}
                  onClick={() => void open(s.saveId)}
                >
                  <span className={sv.svTitle}>{s.title || UNTITLED_SAVE}</span>
                  <span className={sv.svMeta}>{describeSave(s, nameOf)}</span>
                  {off && <span className={sv.svOff}>暂时无法找回 · 点此重试</span>}
                </button>
                <button
                  type="button"
                  className={sv.svRemove}
                  aria-label={s.title ? `从这台设备移除《${s.title}》` : '从这台设备移除这一局'}
                  ref={(el) => {
                    if (el) removeRefs.current.set(s.saveId, el);
                    else removeRefs.current.delete(s.saveId);
                  }}
                  onClick={(e) => removeOne(s, id, items, e)}
                >
                  移除
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );

  return (
    <div className={`${lobbyStyles.lobby} ${ZONE_CLASS[zone]}`} data-zone={zone}>
      <main className={batch ? `${styles.screen} ${sv.svWithToast}` : styles.screen}>
        <button type="button" className={sv.svBack} onClick={screens.goBack}>
          <span aria-hidden="true">‹ </span>返回
        </button>
        <div>
          <h1 ref={h1Ref} tabIndex={-1} className={sv.svH1}>
            我的存档
          </h1>
          <p className={sv.svSub}>移除只是从这台设备上拿掉,移除后在这里就找不回来了。</p>
        </div>
        {saveNotice && <p className={sv.svNotice}>{saveNotice}</p>}
        {latest && historyVisible && (
          <button
            ref={historyRef}
            type="button"
            className={sv.svHistory}
            onClick={() => screens.openHistory(latest)}
          >
            <span>
              <span className={sv.svTitle}>回看上局</span>
              <span className={sv.svMeta}>看看最近这一局已经发生过什么</span>
            </span>
            <span className={sv.svChev} aria-hidden="true">
              ›
            </span>
          </button>
        )}
        {group('saves-ongoing', '进行中', ongoing, false)}
        {group('saves-ended', '已结局', ended, true)}

        {/* 撤销条(决策 3 / 8 / 10):播报区始终在 DOM 里(须先于内容存在),没有待删时是空的、不占位;
            「撤销」在播报区外面,免得每次改字都把它念一遍。悬停或焦点在条内时暂停。 */}
        <div
          className={batch ? `${sv.svToast} ${sv.svToastOn}` : sv.svToast}
          data-testid="removal-toast"
          onMouseEnter={() => ctl.pause('hover')}
          onMouseLeave={() => ctl.resume('hover')}
          onFocus={() => ctl.pause('focus')}
          onBlur={(e) => {
            if (!e.currentTarget.contains(e.relatedTarget as Node | null)) ctl.resume('focus');
          }}
        >
          <p role="status" className={sv.svToastMsg}>
            {batch ? removalMessage(batch) : ''}
          </p>
          {batch && (
            <button ref={undoRef} type="button" className={sv.svUndo} onClick={undo}>
              撤销
            </button>
          )}
          {batch && (
            <i
              key={batch.version}
              aria-hidden="true"
              className={sv.svToastLine}
              data-paused={paused ? 'true' : undefined}
            />
          )}
        </div>
      </main>
    </div>
  );
}
