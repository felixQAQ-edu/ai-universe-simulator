// 选择屏的两个旁支屏 —— 存档页与历史页 —— 的进出(ADR-042 决策 5)。
//
// 规则:**进入时压一条浏览器历史、所有退出都走 back()、只有 popstate 真正关页。**
// 于是存档页 / 历史页上按手机或浏览器返回键,等于点了页面里的「返回」,而不是离开整站。
// 游戏屏、大厅、初始化、错误屏不压历史,行为与之前一字不变。
//
// 两个例外,都为了让「我们压过几条」与浏览器历史对得上:
// · 深度为 0(没有压过,或压失败)时,goBack 直接关页,不调 back() —— 否则会退出整站;
// · 从存档页点开一局(leaveSavesForGame):先关存档页,再 back() 吃掉那一条,并记下「这一次 pop 是自己发的」,
//   popstate 到达时不再关任何东西(游戏里的「返回」照旧回大厅,审定 5)。
//
// 状态独立于 gameStore(同 historyStore 的做法):回合流程一行不改。平台 IO 只经 api/ 注入(ADR-003)。

import { create } from 'zustand';
import { navHistory as defaultNav, type NavHistory } from '../api';
import { useHistoryStore } from './historyStore';
import type { LobbyZone } from './lobbyPrefs';

export interface SavesViewState {
  /** 存档页开着。 */
  open: boolean;
  /** 点「全部存档」那一刻大厅正在显示的区(存档页取这一区的页面令牌;存档页不切区)。 */
  zone: LobbyZone;
}

export const useSavesView = create<SavesViewState>(() => ({ open: false, zone: 'fushi' }));

/** 历史页的最小接口(便于测试注入;生产 = historyStore)。 */
interface HistoryControl {
  isOpen(): boolean;
  open(saveId: string): void;
  close(): void;
}

const defaultHistory: HistoryControl = {
  isOpen: () => useHistoryStore.getState().viewing !== null,
  open: (id) => useHistoryStore.getState().open(id),
  close: () => useHistoryStore.getState().close(),
};

export function createScreens(nav: NavHistory = defaultNav, history: HistoryControl = defaultHistory) {
  /** 本站压进去、还没退出来的条数。 */
  let depth = 0;
  /** 自己发的 back() 还有几次没到。 */
  let selfPops = 0;

  /** 关最上面那一屏:历史页在开着就关历史页,否则关存档页。 */
  const closeTop = () => {
    if (history.isOpen()) history.close();
    else if (useSavesView.getState().open) useSavesView.setState({ open: false });
  };

  const onPop = () => {
    if (depth > 0) depth--;
    if (selfPops > 0) {
      selfPops--;
      return;
    }
    closeTop();
  };

  return {
    /** 应用启动时装一次:清掉刷新留下的标记、订阅 popstate。返回卸载函数。 */
    install(): () => void {
      nav.clearStaleMarker();
      return nav.onPop(onPop);
    },
    openSaves(zone: LobbyZone) {
      if (useSavesView.getState().open) return;
      nav.push('saves');
      depth++;
      useSavesView.setState({ open: true, zone });
    },
    openHistory(saveId: string) {
      nav.push('history');
      depth++;
      history.open(saveId);
    },
    /** 页面里的「返回」:有压过的条目就退一步(由 popstate 关页),否则直接关。 */
    goBack() {
      if (depth > 0) nav.back();
      else closeTop();
    },
    /** 从存档页点开一局:关存档页、吃掉那一条历史,popstate 到达时不再处理。 */
    leaveSavesForGame() {
      if (!useSavesView.getState().open) return;
      useSavesView.setState({ open: false });
      if (depth > 0) {
        selfPops++;
        nav.back();
      }
    },
  };
}

export type Screens = ReturnType<typeof createScreens>;
export const screens = createScreens();
