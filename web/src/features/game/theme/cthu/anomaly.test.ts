import { describe, expect, it } from 'vitest';
import { createPickState, markPicked, pickAnomaly, pickPunct, tailExtra } from './anomalyPick';
import { joinChunks, segmentProse, type Chunk } from './anomalySegment';
import { HOMOGRAPHS, HOMOGRAPH_COUNT, homographOf } from './homographs';

// 克苏鲁文字异常的**前两层**(分片 / 选点)—— 纯逻辑,随机源注入,故约束是**确定性钉死**的,
// 不靠「跑十分钟看看会不会连播」(同刀 3 电台抽取器的做法)。

const SAMPLE =
  '港口的雾比昨夜更沉。渔市收摊后,石板路上留下一滩滩发亮的黏液,顺着坡道一路延伸到旧灯塔。\n' +
  '你的怀表停在了三点零七分,而表针仍在轻轻颤动,像是在指向海的方向。';

describe('形近字人工允许表(ADR-018 §5 Q3)', () => {
  it('规模落在立字的 100–200 对区间', () => {
    expect(HOMOGRAPH_COUNT).toBeGreaterThanOrEqual(100);
    expect(HOMOGRAPH_COUNT).toBeLessThanOrEqual(200);
  });

  it('没有自映射(换了等于没换)', () => {
    for (const [orig, alt] of HOMOGRAPHS) expect(alt).not.toBe(orig);
  });

  it('每条都是单个汉字 → 单个汉字(不夹标点、不夹多字)', () => {
    for (const [orig, alt] of HOMOGRAPHS) {
      expect([...orig]).toHaveLength(1);
      expect([...alt]).toHaveLength(1);
      expect(orig).toMatch(/[一-鿿]/);
      expect(alt).toMatch(/[一-鿿]/);
    }
  });

  it('查不到的字返回 null —— 不推断、不兜底(绝不降质替换成任意字)', () => {
    expect(homographOf('沉')).toBe('沆');
    expect(homographOf('龘')).toBeNull();
    expect(homographOf('a')).toBeNull();
  });
});

describe('分片(第 ①层)', () => {
  it('★ 拼回去与原文逐字相等 —— 复制/选择/读屏语义与版式都靠这一条', () => {
    expect(joinChunks(segmentProse(SAMPLE))).toBe(SAMPLE);
  });

  it('★ 换行、空白、西文、数字一字不动地保留在 plain 片里', () => {
    const text = '雾很沉\n\n  海面 3 海里外 OK。';
    expect(joinChunks(segmentProse(text))).toBe(text);
    // 换行没被吞掉 —— 正文容器是 pre-wrap,吞了换行就是改版式。
    expect(segmentProse(text).some((c) => c.text.includes('\n'))).toBe(true);
  });

  it('确定性:同一段文本切两次结果完全一致(否则 React 会重建节点、冲掉进行中的异常)', () => {
    expect(segmentProse(SAMPLE)).toEqual(segmentProse(SAMPLE));
  });

  it('六型中的五型都能自动选出来(不再依赖人工标注)', () => {
    const modes = new Set(segmentProse(SAMPLE).map((c) => c.mode));
    for (const m of ['swap', 'swap2', 'squeeze', 'shift', 'tail', 'punct']) {
      expect(modes).toContain(m);
    }
  });

  it('swap 片必是单字且带人工表登记的替身', () => {
    for (const c of segmentProse(SAMPLE).filter((c) => c.mode === 'swap')) {
      expect([...c.text]).toHaveLength(1);
      expect(c.alt).toBe(homographOf(c.text));
    }
  });

  it('swap2 片必是两个不同的汉字(叠字反过来还是自己,不作候选)', () => {
    for (const c of segmentProse(SAMPLE).filter((c) => c.mode === 'swap2')) {
      expect([...c.text]).toHaveLength(2);
      expect(c.text[0]).not.toBe(c.text[1]);
    }
    // 叠字直接验:「渐渐」不该成为 swap2
    const dup = segmentProse('渐渐地雾散了。').filter((c) => c.mode === 'swap2');
    expect(dup.every((c) => c.text !== '渐渐')).toBe(true);
  });

  it('tail 只落在句末标点前,且是纯汉字片(句中多一个字一眼就是坏了)', () => {
    const chunks = segmentProse(SAMPLE);
    const tails = chunks.flatMap((c, i) => (c.mode === 'tail' ? [i] : []));
    expect(tails.length).toBeGreaterThan(0);
    for (const i of tails) {
      expect(chunks[i + 1]?.mode).toBe('punct');
      expect(chunks[i].text).toMatch(/^[一-鿿]{2,}$/);
    }
  });

  it('标点自己成片(余韵要能单独改它)', () => {
    for (const c of segmentProse(SAMPLE).filter((c) => c.mode === 'punct')) {
      expect(c.text).toMatch(/^[。！？!?]$/);
    }
  });

  it('没有汉字的文本 → 全 plain,不产生任何候选(不硬凑)', () => {
    const chunks = segmentProse('ETA 03:07 — OK');
    expect(chunks.every((c) => c.mode === 'plain')).toBe(true);
  });

  it('空文本 → 空片流(开场未开始打字时不崩)', () => {
    expect(segmentProse('')).toEqual([]);
  });
});

describe('选点(第 ②层)', () => {
  const chunks = segmentProse(SAMPLE);
  /** 固定随机源:总取候选池里的第一个(约束是否生效因此可直接观察)。 */
  const first = () => 0;

  it('挑出来的一定是五型之一,不会挑到标点或 plain', () => {
    const pick = pickAnomaly(chunks, createPickState(), first)!;
    expect(['swap', 'swap2', 'tail', 'squeeze', 'shift']).toContain(pick.mode);
  });

  it('① 同一目标不连续', () => {
    const state = createPickState();
    const a = pickAnomaly(chunks, state, first)!;
    markPicked(state, a);
    const b = pickAnomaly(chunks, state, first)!;
    expect(b.index).not.toBe(a.index);
  });

  it('② 同一类型不连续(六型轮换,免得被认成「那个抖字特效」)', () => {
    const state = createPickState();
    const a = pickAnomaly(chunks, state, first)!;
    markPicked(state, a);
    const b = pickAnomaly(chunks, state, first)!;
    expect(b.mode).not.toBe(a.mode);
  });

  it('③ 候选全被挡住 → null(整次跳过,不放宽约束硬凑)', () => {
    const only: Chunk[] = [{ text: '沉', mode: 'swap', alt: '沆' }];
    const state = createPickState();
    const a = pickAnomaly(only, state, first)!;
    markPicked(state, a);
    expect(pickAnomaly(only, state, first)).toBeNull();
  });

  it('空片流 / 无候选 → null,不抛', () => {
    expect(pickAnomaly([], createPickState(), first)).toBeNull();
    expect(pickAnomaly([{ text: 'OK', mode: 'plain' }], createPickState(), first)).toBeNull();
  });

  it('markPicked 与 pickAnomaly 分开:挑到但没做成(换行守卫取消)不推进约束', () => {
    const state = createPickState();
    pickAnomaly(chunks, state, first);
    expect(state.lastIndex).toBe(-1); // 只挑不记 —— 状态没动
  });

  it('随机源上界不越界(rand 返回 0.999… 时不取到 undefined)', () => {
    const pick = pickAnomaly(chunks, createPickState(), () => 0.9999999)!;
    expect(pick.chunk).toBeDefined();
  });

  it('标点余韵挑的是句末标点;没有句末标点 → null', () => {
    const i = pickPunct(chunks, first)!;
    expect(chunks[i].mode).toBe('punct');
    expect(pickPunct(segmentProse('没有句末标点的一行'), first)).toBeNull();
  });

  it('行尾多的字是语义勉强成立的虚字,不是恐怖名词', () => {
    const seen = new Set([tailExtra(() => 0.9), tailExtra(() => 0.4), tailExtra(() => 0.01)]);
    for (const s of seen) expect(['了', '去', '去了']).toContain(s);
  });
});
