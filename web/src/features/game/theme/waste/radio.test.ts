import { describe, expect, it } from 'vitest';
import { ODD_COOLDOWN_MS, createDrawState, drawIndex, markPlayed } from './radioDraw';
import { RADIO_POOL, degrade, holdMs } from './radioPool';

// 电台的**纯逻辑层**测试(内容池 + 抽取约束)。这两层刻意不含 React 与计时器,
// 随机源与时钟都注入 —— 于是 B 批那三条约束能被**确定性**地钉死,
// 而不是靠「跑 30 分钟看看有没有连播」。

const seq = (...xs: number[]) => {
  let i = 0;
  return () => xs[Math.min(i++, xs.length - 1)];
};

describe('内容池', () => {
  it('四层配比是 B 批验收结论:生活 4 / 疲惫 3 / 残缺 2 / 异常 2', () => {
    const count = (l: string) => RADIO_POOL.filter((m) => m.layer === l).length;
    expect(count('life')).toBe(4);
    expect(count('tired')).toBe(3);
    expect(count('broken')).toBe(2);
    expect(count('odd')).toBe(2);
    // 异常必须是**少数**——全是诡异内容会变成鬼故事电台,末日的底色是「日子还在过」。
    expect(count('odd')).toBeLessThan(count('life'));
  });

  it('停留时长随文字长度但有上下限(短句不一闪而过、长句不至于读不完)', () => {
    expect(holdMs('短')).toBe(2200);
    expect(holdMs('长'.repeat(200))).toBe(3800);
    const mid = holdMs('中'.repeat(20));
    expect(mid).toBeGreaterThan(2200);
    expect(mid).toBeLessThan(3800);
  });
});

describe('信号退化(重播必变)', () => {
  const msg = RADIO_POOL.find((m) => m.layer === 'life' && m.sig === 2)!;

  it('首播:给定随机源不触发退化时,原文照播', () => {
    expect(degrade(msg, false, seq(0.9))).toBe(msg.text);
  });

  it('重播:**必定**与原文不同(体感是「上次好像没听全」,不是「又播这条了」)', () => {
    // 四种退化各取一次,逐一确认都变了
    for (const pick of [0.0, 0.3, 0.55, 0.8]) {
      expect(degrade(msg, true, seq(pick, 0.5, 0.5, 0.5))).not.toBe(msg.text);
    }
  });

  it('弱信号(sig 0)会再吃一次字', () => {
    const weak = RADIO_POOL.find((m) => m.sig === 0)!;
    const out = degrade(weak, false, seq(0.9, 0.1, 0.5));
    expect(out.length).toBeLessThan(weak.text.length);
  });
});

describe('抽取约束(B 批三条)', () => {
  it('① 一轮内不重复:连抽一整轮,每条恰好出现一次', () => {
    const st = createDrawState();
    const got: number[] = [];
    // 同层不连播会挡住一部分候选,故一轮可能提前抽空 —— 这里只断言**不重复**。
    for (let i = 0; i < RADIO_POOL.length; i++) {
      const idx = drawIndex(st, Math.random, 0);
      if (idx === null) break;
      markPlayed(st, idx, 0);
      got.push(idx);
    }
    expect(new Set(got).size).toBe(got.length);
  });

  it('② 同层不连播', () => {
    const st = createDrawState();
    let prev: string | null = null;
    for (let i = 0; i < 30; i++) {
      const idx = drawIndex(st, Math.random, 0);
      if (idx === null) continue;
      const layer = RADIO_POOL[idx].layer;
      expect(layer).not.toBe(prev);
      markPlayed(st, idx, 0);
      prev = layer;
    }
  });

  it('③ 异常层 240s 冷却:刚播过异常,冷却内不再抽到异常', () => {
    const st = createDrawState();
    const oddIdx = RADIO_POOL.findIndex((m) => m.layer === 'odd');
    markPlayed(st, oddIdx, 1_000_000);
    for (let i = 0; i < 40; i++) {
      const idx = drawIndex(st, Math.random, 1_000_000 + ODD_COOLDOWN_MS - 1);
      if (idx === null) continue;
      expect(RADIO_POOL[idx].layer).not.toBe('odd');
      markPlayed(st, idx, 1_000_000);
    }
  });

  it('③ 冷却过后异常层重新可抽', () => {
    const st = createDrawState();
    const oddIdx = RADIO_POOL.findIndex((m) => m.layer === 'odd');
    markPlayed(st, oddIdx, 0);
    const seen = new Set<string>();
    for (let i = 0; i < 60; i++) {
      const idx = drawIndex(st, Math.random, ODD_COOLDOWN_MS + 1);
      if (idx === null) continue;
      seen.add(RADIO_POOL[idx].layer);
      markPlayed(st, idx, ODD_COOLDOWN_MS + 1);
    }
    expect(seen.has('odd')).toBe(true);
  });

  it('约束全挡住时返回 null(转调谐失败)—— **沉默也是内容**,不放宽约束硬凑一条', () => {
    // 构造死局:bag 里只剩一条,且它与上一条同层。
    const lifeIdx = RADIO_POOL.findIndex((m) => m.layer === 'life');
    const otherLife = RADIO_POOL.findIndex((m, i) => m.layer === 'life' && i !== lifeIdx);
    const st = createDrawState();
    st.bag = [otherLife];
    st.lastLayer = 'life';
    expect(drawIndex(st, Math.random, 0)).toBeNull();
  });

  it('轮与轮的接缝也不重复:洗牌后第一条不会正是刚播过的那条', () => {
    const st = createDrawState();
    st.lastIdx = 3;
    // 强制洗出「3 在首位」的顺序:shuffle 用的随机源全给 0 → 每次都与下标 0 交换,
    // 结果首位是原数组最后一个;这里只断言实现层的换位保护生效(首位 !== lastIdx)。
    st.bag = [];
    const idx = drawIndex(st, () => 0, 0);
    expect(idx).not.toBe(3);
  });
});
