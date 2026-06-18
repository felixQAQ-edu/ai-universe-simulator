import { sampleWorld } from './data/sampleWorld';
import './App.css';

// Phase 0 脚手架占位页:用统一 schema 类型 + 硬编码示例渲染,仅证明工程能跑、类型可用。
// 真实的规则怪谈界面、决策圈交互、LLM 调用都属于 Phase 1,这里一律不实现。
// 注意:rules[].isTrue / hiddenLogic 是引擎视角字段,即使在调试视图也不渲染,养成不泄露的习惯。
function App() {
  const { world, character, state, availableActions, rules, endings } = sampleWorld;

  return (
    <main className="app">
      <header className="app__header">
        <p className="app__phase">AI Universe Simulator · Phase 0 脚手架</p>
        <h1 className="app__title">{world.title}</h1>
        <p className="app__tone">
          危险度 {world.dangerLevel} · {world.tone}
        </p>
      </header>

      <section className="card">
        <h2>背景</h2>
        <p>{world.background}</p>
      </section>

      <section className="card">
        <h2>角色</h2>
        <ul className="stats">
          {Object.entries(character.attributes).map(([key, value]) => (
            <li key={key}>
              <span className="stats__key">{key}</span>
              <span className="stats__val">{value}</span>
            </li>
          ))}
        </ul>
        <p className="muted">特质:{character.traits.join('、')}</p>
        <p className="muted">物品:{character.inventory.join('、')}</p>
      </section>

      <section className="card">
        <h2>已知规则(玩家视角)</h2>
        <ol>
          {rules.map((rule) => (
            <li key={rule.id}>{rule.content}</li>
          ))}
        </ol>
      </section>

      <section className="card">
        <h2>决策圈(第 {state.turn} 回合)</h2>
        <div className="actions">
          {availableActions.map((action) => (
            <button key={action.id} type="button" className="action" disabled>
              {action.text}
            </button>
          ))}
        </div>
        <p className="muted">按钮在 Phase 1 接入交互前为占位,故禁用。</p>
      </section>

      <footer className="app__footer">
        <p className="muted">
          schema v{sampleWorld.schemaVersion} · {endings.length} 种结局待解锁
        </p>
      </footer>
    </main>
  );
}

export default App;
