// 大厅继续条下面那一句(ADR-042 审定 3):点「继续」才发现那一局暂时找不回时说一声,继续条随之换成下一条。
//
// 不放在大厅组件的 state 里:续局期间 status 离开 idle,大厅会卸载再挂上,组件 state 留不住。
// 也不借 gameStore.saveNotice:存档页同样显示 saveNotice,而「已经放进全部存档里」在存档页上说不通。
//
// 寿命 = 玩家下一次离开大厅为止:开局 / 续局(status 离开 idle 即清,见下方订阅)或点「全部存档」(大厅自己清)。
// 不靠组件卸载来清 —— StrictMode 的开发期双挂载会在重挂时把刚写进的句子清掉。

import { create } from 'zustand';
import { useGameStore } from './gameStore';

export const CONTINUE_LOST_NOTICE = '上一局暂时找不回,已经放进全部存档里。';

export const useContinueNotice = create<{ text: string | null }>(() => ({ text: null }));

useGameStore.subscribe((s, prev) => {
  if (prev.status === 'idle' && s.status !== 'idle') useContinueNotice.setState({ text: null });
});
