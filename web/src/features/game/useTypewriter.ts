import { useEffect, useState } from 'react';

// 客户端逐字 reveal(ADR-007:开场叙事后端不流式,vibe 由前端动画补)。
// 纯展示动画,不碰平台 IO —— 定时器不算网络/平台 API,留在 features/ 合规。

interface TypewriterResult {
  shown: string;
  done: boolean;
}

/**
 * 把 full 文本按 charsPerTick/intervalMs 逐字吐出。text/enabled 变化即重启(用
 * React 官方的「渲染期据 prop 变化调整 state」模式重置,避免 effect 里同步 setState)。
 * enabled=false 时直接全显(回合实时流本就逐字到达,无需再假装)。
 */
export function useTypewriter(
  full: string,
  enabled = true,
  charsPerTick = 2,
  intervalMs = 28,
): TypewriterResult {
  const [count, setCount] = useState(enabled ? 0 : full.length);
  const [prevFull, setPrevFull] = useState(full);
  const [prevEnabled, setPrevEnabled] = useState(enabled);

  // 渲染期重置:full/enabled 变了就立刻把进度归零(或全显),React 会即刻重渲染。
  if (full !== prevFull || enabled !== prevEnabled) {
    setPrevFull(full);
    setPrevEnabled(enabled);
    setCount(enabled ? 0 : full.length);
  }

  useEffect(() => {
    if (!enabled || !full) return;
    const timer = setInterval(() => {
      setCount((c) => Math.min(c + charsPerTick, full.length));
    }, intervalMs);
    return () => clearInterval(timer);
  }, [full, enabled, charsPerTick, intervalMs]);

  const safeCount = enabled ? count : full.length;
  return {
    shown: full.slice(0, safeCount),
    done: safeCount >= full.length,
  };
}
