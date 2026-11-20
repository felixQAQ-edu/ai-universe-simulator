// 存档页(ADR-042 决策 2 / 5 / 6 / 8):选择屏的旁支屏,列出这台设备上的全部存档。
// 只读 store、不碰存储;移除与撤销窗口在刀 5 接进来。平台 IO 一律不在这里(ADR-003)。
import { useEffect, useRef } from 'react';
import { useGameStore } from '../../../state/gameStore';
import { useHistoryStore } from '../../../state/historyStore';
import { onSavePointersChanged, type SaveSummary } from '../../../state/savePointers';
import { screens, useSavesView } from '../../../state/screens';
import styles from '../game.module.css';
import lobbyStyles from '../lobby.module.css';
import { describeSave } from '../saveText';
import { ZONE_CLASS } from '../zoneClass';
import sv from './saves.module.css';

/** 世界名为空(迁移来的旧指针)时行上的占位(ADR-034 已知代价 4,原文照旧)。 */
export const UNTITLED_SAVE = '上一局(续上后显示世界名)';

export function SavesScreen() {
  const zone = useSavesView((s) => s.zone);
  const focusHint = useSavesView((s) => s.focus);
  const saves = useGameStore((s) => s.saves);
  const archetypes = useGameStore((s) => s.archetypes);
  const saveNotice = useGameStore((s) => s.saveNotice);
  const refreshSaves = useGameStore((s) => s.refreshSaves);
  const resumeGame = useGameStore((s) => s.resumeGame);

  // 跨页签:别的页签改了存档键时只刷新显示(ADR-034 已决 8;与大厅同一个订阅)。
  useEffect(() => onSavePointersChanged(refreshSaves), [refreshSaves]);

  // 「回看上局」只跟随画面上的最近一条(ADR-034 已决 9 / ADR-042 决策 6)。探测语义不变,
  // 大厅多半已经探过(historyStore 去重,这里再调一次不会重发)。
  const latest = saves.length > 0 ? saves[0].saveId : null;
  const historyCapable = useGameStore((s) => s.capabilities.history);
  const probeHistory = useHistoryStore((s) => s.probe);
  const historyVisible = useHistoryStore((s) => (latest ? s.visible[latest] === true : false));
  useEffect(() => {
    if (latest && historyCapable) probeHistory(latest);
  }, [latest, historyCapable, probeHistory]);

  // 焦点(决策 5):进来 → 标题;从历史页回来 → 「回看上局」(入口不在了就退回标题)。提示一次性,落完即清。
  const h1Ref = useRef<HTMLHeadingElement>(null);
  const historyRef = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (focusHint === 'history' && historyRef.current) historyRef.current.focus();
    else h1Ref.current?.focus();
    if (focusHint !== null) useSavesView.setState({ focus: null });
    // 只在挂上时落一次:之后提示被清成 null 不该再把焦点拽回标题。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const nameOf = (a: string) => archetypes.find((x) => x.archetype === a)?.displayName ?? a;

  // 点开一行:先续局;续上了(离开 idle)才关存档页、吃掉那条历史 —— 游戏里的「返回」照旧回大厅(审定 5)。
  // 续不上(404 / 网络)时 status 回到 idle,玩家仍在存档页,提示与「暂时无法找回」都在这一页上。
  const open = async (saveId: string) => {
    await resumeGame(saveId);
    if (useGameStore.getState().status !== 'idle') screens.leaveSavesForGame();
  };

  const ongoing = saves.filter((s) => !s.ended);
  const ended = saves.filter((s) => s.ended);

  return (
    <div className={`${lobbyStyles.lobby} ${ZONE_CLASS[zone]}`} data-zone={zone}>
      <main className={styles.screen}>
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
        <SaveGroup id="saves-ongoing" name="进行中" items={ongoing} nameOf={nameOf} onOpen={open} />
        <SaveGroup id="saves-ended" name="已结局" items={ended} nameOf={nameOf} onOpen={open} />
      </main>
    </div>
  );
}

function SaveGroup({
  id,
  name,
  items,
  nameOf,
  onOpen,
}: {
  id: string;
  name: string;
  items: SaveSummary[];
  nameOf: (archetype: string) => string;
  onOpen: (saveId: string) => void;
}) {
  return (
    <section className={sv.svGroup} aria-labelledby={id}>
      <div className={sv.svGroupHead}>
        <h2 id={id} tabIndex={-1}>
          {name}
          <b>{items.length}</b>
        </h2>
      </div>
      {items.length > 0 && (
        <ul className={sv.svList}>
          {items.map((s) => {
            const off = s.availability === 'unavailable';
            return (
              <li key={s.saveId} className={off ? `${sv.svRow} ${sv.svRowOff}` : sv.svRow}>
                <button type="button" className={sv.svOpen} onClick={() => void onOpen(s.saveId)}>
                  <span className={sv.svTitle}>{s.title || UNTITLED_SAVE}</span>
                  <span className={sv.svMeta}>{describeSave(s, nameOf)}</span>
                  {off && <span className={sv.svOff}>暂时无法找回 · 点此重试</span>}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
