import { homographOf } from './homographs';

// 克苏鲁 · 文字异常**分片**(四层分工的第 ①层:分片 / 选点 / 调度 / 呈现)。
//
// 纯函数、零 React、零计时器、零随机 —— **同一段文本永远切成同一份结果**。
// 确定性不是洁癖,是硬需求:分片结果进 React 的 vdom,若每次渲染切得不一样,
// React 会重建这些节点,正在进行的异常会被冲掉、`data-orig` 也会丢。
//
// ── 与样板间的差别(勘察结论)────────────────────────────────────────────
// 样板间的异常作用在**人工标注的 span** 上(`data-alt` 的形近字是人挑的,`data-mode` 是人写的),
// 因为那里的正文是写死的两段。生产的正文是运行时生成的,**六型里五型必须自动选点**,
// 只有 swap 仍依赖人工词典(`homographs.ts`)—— 这正是 ADR-018 §5 Q3 立人工表的原因。
//
// ── 可访问性与版式(两条硬约束,都靠「一维、无损」这个形状守住)────────────
// 切出来是**一维片流**,不引入 `<p>` 之类块级结构,换行仍是文本里的 `\n`
// (正文容器本就 `white-space: pre-wrap`)—— 于是:
//   · 拼回去与原文**逐字相等**(有测试直接钉住)→ 复制、选择、读屏语义全部连续;
//   · 版式与通用 `Prose` 完全一致 —— 分片**只加 `<span>`,不插字符、不加空白、不加 aria**。

/** 一片的候选类型。`plain` = 不作候选(标点、西文、数字、换行、切不出角色的余料)。 */
export type AnomalyMode = 'plain' | 'swap' | 'swap2' | 'squeeze' | 'shift' | 'tail' | 'punct';

export interface Chunk {
  text: string;
  mode: AnomalyMode;
  /** `swap` 专有:人工表登记的替身字。其余类型恒 undefined。 */
  alt?: string;
}

/** 句末标点:`tail`(行尾多一字)必须挨着它,`punct`(余韵)也从这里挑。 */
const SENTENCE_END = /[。！？!?]/;
/** CJK 汉字(不含标点与全角符号)。 */
const CJK = /[一-鿿]/;

/** 片长循环表:**确定性**地把长汉字串切成 2/3/4 的混合(不是等长切,免得读起来像表格)。 */
const SIZES = [2, 3, 4, 3, 2, 4, 3, 2];

/** 小 hash:同一段文本恒得同一个起点,不同文本起点不同(切法不至于段段一个样)。 */
function seedOf(text: string): number {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return Math.abs(h);
}

/**
 * 把正文切成一维片流。规则:
 *
 * 1. 句末标点单独成片(`punct` 候选,余韵用);
 * 2. 非汉字连续段(西文 / 数字 / 空白 / 换行 / 顿号引号一类)整段作 `plain` —— **不碰它们**:
 *    这些字符的形近替换与字间距变化最容易读成「乱码」,而不是「不对劲」;
 * 3. 汉字串里,**先把人工表命中的字单独切出来**(`swap` 候选,带 `alt`),
 *    且要求与上一刀间隔 ≥2 字,免得整句被切成一堆单字;
 * 4. 余下的空档按 {@link SIZES} 循环切:2 字 → `swap2`(两字交换,叠字除外)、
 *    3 字 → `squeeze`(字间距收缩)与 `shift`(词组错位)轮流、4 字 → `shift`;
 * 5. **每句里紧挨句末标点的那一片改判 `tail`**(行尾多一字只在句尾才像话)。
 *
 * 切不出角色的余料一律 `plain`(比如只剩 1 个不在表内的汉字)—— 宁可少一个候选,不硬凑。
 */
export function segmentProse(text: string): Chunk[] {
  const out: Chunk[] = [];
  let buf = '';
  let cursor = 0; // SIZES 游标,整段连续推进(不在每句归零,免得每句开头都同一个切法)
  const seed = seedOf(text);

  const flush = (endsSentence: boolean) => {
    if (!buf) return;
    const chunks = cutRun(buf, seed + cursor);
    cursor += chunks.length;
    if (endsSentence) markTail(chunks);
    out.push(...chunks);
    buf = '';
  };

  for (const ch of text) {
    if (SENTENCE_END.test(ch)) {
      flush(true);
      out.push({ text: ch, mode: 'punct' });
    } else {
      buf += ch;
    }
  }
  flush(false);
  return out;
}

/**
 * 句末那一片改判 `tail` —— 只有句尾多出一个字才像「手误」,句中多字一眼就是坏了。
 *
 * **只看最后一片**:早先写成「往前找第一片够格的」,结果句尾若是个单字 swap 片,
 * 就会越过它把**句中**某片标成 tail —— 正是这条规矩要防的那种坏法(已有用例钉住)。
 * 最后一片不够格(单字 / 非纯汉字)→ 这句就没有 tail 候选,**不往前凑**。
 */
function markTail(chunks: Chunk[]): void {
  const i = chunks.length - 1;
  const c = chunks[i];
  if (!c || c.mode === 'plain') return;
  // 长度 ≥2 且整片都是汉字才配当 tail(单字后面再加一字读起来突兀)。
  if (c.text.length < 2 || ![...c.text].every((x) => CJK.test(x))) return;
  chunks[i] = { text: c.text, mode: 'tail' };
}

function cutRun(text: string, seed: number): Chunk[] {
  const out: Chunk[] = [];
  let i = 0;
  let sizeIdx = seed % SIZES.length;
  let sinceCut = 99; // 距上一次 swap 切口的字数(开头允许直接切)

  while (i < text.length) {
    const ch = text[i];

    // 非汉字:整段原样收走,不作候选。
    if (!CJK.test(ch)) {
      let j = i;
      while (j < text.length && !CJK.test(text[j])) j++;
      out.push({ text: text.slice(i, j), mode: 'plain' });
      i = j;
      sinceCut = 99;
      continue;
    }

    // 人工表命中 → 单字成片(swap 候选)。间隔 ≥2 字,免得切得太碎。
    const alt = homographOf(ch);
    if (alt && sinceCut >= 2) {
      out.push({ text: ch, mode: 'swap', alt });
      i += 1;
      sinceCut = 0;
      continue;
    }

    // 余下按 SIZES 循环切,但尽量不吞掉后面能当 swap 的字(把它留给 swap)。
    const size = SIZES[sizeIdx % SIZES.length];
    sizeIdx += 1;
    let end = i;
    let taken = 0;
    while (end < text.length && taken < size && CJK.test(text[end])) {
      if (taken >= 2 && homographOf(text[end])) break;
      end += 1;
      taken += 1;
    }
    const piece = text.slice(i, end);
    out.push({ text: piece, mode: roleFor(piece, sizeIdx) });
    sinceCut += piece.length;
    i = end;
  }

  return out;
}

/** 按片长派角色。切不出角色 → `plain`(不硬凑)。 */
function roleFor(piece: string, tick: number): AnomalyMode {
  if (piece.length >= 4) return 'shift';
  if (piece.length === 3) return tick % 2 === 0 ? 'squeeze' : 'shift';
  // 两字:反过来必须与原文不同(叠字「渐渐」反过来还是自己,换了等于没换)。
  if (piece.length === 2) return piece[0] === piece[1] ? 'plain' : 'swap2';
  return 'plain';
}

/** 把分片拼回文本(**测试与自查用**:必须与输入逐字相等 —— 可访问性与版式都靠这条)。 */
export function joinChunks(chunks: Chunk[]): string {
  return chunks.map((c) => c.text).join('');
}
