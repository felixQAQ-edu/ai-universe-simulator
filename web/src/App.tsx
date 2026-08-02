import { GameScreen } from './features/game';
import { queryFlag } from './features/game/theme/debug';
import { FusionDragProto } from './proto/FusionDragProto';

// Phase 1 单模式 H5 闭环(规则怪谈):init → 回合循环 → 结局。
// 整局状态在 state/gameStore;网络/流 IO 全收在 api/ 适配层(ADR-003 边界)。
//
// 线 B1 原型旁路(`?proto=fusion`):融合入口拖拽手势的可行性原型。**整个游戏树都不挂载**——
// 原型页零网络调用、零 store 触碰,生产路径的唯一改动就是这一条分支;
// 判死则删 src/proto/ 与这三行,生产侧不留痕迹。
function App() {
  if (queryFlag('proto') === 'fusion') return <FusionDragProto />;
  return <GameScreen />;
}

export default App;
