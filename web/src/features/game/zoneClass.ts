// 大厅区 → 区类(ADR-041 决策 4 的页面令牌)。选择屏与存档页共用(ADR-042 决策 5:存档页取进来前所在的区)。
import type { LobbyZone } from '../../state/lobbyPrefs';
import lobbyStyles from './lobby.module.css';

export const ZONE_CLASS: Record<LobbyZone, string> = {
  fushi: lobbyStyles.zoneFushi,
  shen: lobbyStyles.zoneShen,
  rong: lobbyStyles.zoneRong,
};
