import { GameScreen } from './features/game';

// Phase 1 单模式 H5 闭环(规则怪谈):init → 回合循环 → 结局。
// 整局状态在 state/gameStore;网络/流 IO 全收在 api/ 适配层(ADR-003 边界)。
//
// 线 B1 的原型旁路(`?proto=fusion`)已随 B2 落地删除(ADR-018 §6 立的到期日):
// 手势判据搬进了 features/game/fusion/,调参面板与加长列表是原型道具、不进生产 ——
// 留着就会变成同一套判据的第二份拷贝,两份各自漂移正是 §4.1 要防的事。
function App() {
  return <GameScreen />;
}

export default App;
