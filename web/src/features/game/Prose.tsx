import styles from './game.module.css';

// 散文区(纯展示)。逐字 reveal 的进度由上层算好后传进来 ——
// **ADR-018 刀 1**:`useTypewriter` 上提到 `PlayingScreen`,因为「是否正在打字」同时是
// 低频动效的**停表信号**(§4.4:正文是禁区,文本不稳定期不起新动效);
// 让打字进度只有一个真理源,免得氛围层再猜一遍。
//
// 光标:皮肤可经 `--caret-play: paused` 把闪烁冻成静态方块(规则怪谈「精确/机械/阶跃」的时间感,
// 同时了结 AGENTS.md §4「正文永不持续动画」在正文区的最后一处循环)。
export function Prose({ text, caret }: { text: string; caret: boolean }) {
  return (
    <div className={styles.prose}>
      {text}
      {caret && <span className={styles.caret}>▍</span>}
    </div>
  );
}
