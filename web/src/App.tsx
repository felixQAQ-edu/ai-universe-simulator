import { GameScreen } from './features/game';

// Phase 1 单模式 H5 闭环(规则怪谈):init → 回合循环 → 结局。
// 整局状态在 state/gameStore;网络/流 IO 全收在 api/ 适配层(ADR-003 边界)。
function App() {
  return <GameScreen />;
}

export default App;
