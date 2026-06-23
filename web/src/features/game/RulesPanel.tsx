import { useEffect, useRef, useState } from 'react';
import type { ClientRule } from '../../api';
import styles from './game.module.css';

// 规则面板:展示玩家可见的规则 content(规则怪谈的《须知》)。
// 注意:isTrue / hiddenLogic 是引擎视角字段,消毒投影里本就没有,这里也绝不渲染。
// discovered 的规则做持久高亮(玩家已在剧情中坐实它「确实生效」);
// 本回合「刚被坐实」的规则额外加一次性脉冲,把视线从剧情拉到对应规则,降低「没头绪」。
const FLASH_MS = 1800;

export function RulesPanel({
  rules,
  discoveredIds,
}: {
  rules: ClientRule[];
  discoveredIds: number[];
}) {
  // 上一次的已发现集(用于 diff 出「本回合新坐实」的规则);初值 = 首帧已发现,故开场不闪。
  const prevRef = useRef<Set<number>>(new Set(discoveredIds));
  const [flashing, setFlashing] = useState<Set<number>>(new Set());

  useEffect(() => {
    const prev = prevRef.current;
    const fresh = discoveredIds.filter((id) => !prev.has(id));
    prevRef.current = new Set(discoveredIds);
    if (fresh.length === 0) return;
    setFlashing((cur) => new Set([...cur, ...fresh]));
    const t = setTimeout(() => {
      setFlashing((cur) => {
        const next = new Set(cur);
        fresh.forEach((id) => next.delete(id));
        return next;
      });
    }, FLASH_MS);
    return () => clearTimeout(t);
  }, [discoveredIds]);

  if (rules.length === 0) return null;
  const discovered = new Set(discoveredIds);
  return (
    <section className={styles.rules}>
      <h2 className={styles.rulesTitle}>规则须知</h2>
      <ol className={styles.ruleList}>
        {rules.map((r) => {
          const isFound = discovered.has(r.id);
          const isFlashing = flashing.has(r.id);
          const cls = [
            styles.rule,
            isFound ? styles.ruleDiscovered : '',
            isFlashing ? styles.ruleJustDiscovered : '',
          ]
            .filter(Boolean)
            .join(' ');
          return (
            <li key={r.id} className={cls}>
              {r.content}
              {isFound && <span className={styles.ruleTag}>已验证</span>}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
