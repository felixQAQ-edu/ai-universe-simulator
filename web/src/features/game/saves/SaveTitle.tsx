// 存档行与大厅「继续」行的世界名那一行(ADR-044 待审 3 / 4)。纯展示,无平台 IO。
//
// 没有小标题(旧档 / 漏写 / 其余世界 / 迁移来的空名指针):与 ADR-044 之前逐字节相同 —— 一个 svTitle span,世界名或占位文案。
// 有小标题:「派单 · 小标题」,小标题(连同分隔)单独一个元素;整行单行、溢出省略(svTitleOneLine 兜底,只挂在有小标题的行上,
// 其余世界的长名照旧换行)。存档区不设 `--title-font`,小标题跟着页面字体走,不会吃到《寻常》《七日开张》的子集字体。
import type { SaveSummary } from '../../../state/savePointers';
import { shownSubtitle, SUBTITLE_SEPARATOR, UNTITLED_SAVE } from '../saveText';
import sv from './saves.module.css';

export function SaveTitle({ save }: { save: Pick<SaveSummary, 'title' | 'subtitle'> }) {
  const sub = shownSubtitle(save);
  if (!sub) return <span className={sv.svTitle}>{save.title || UNTITLED_SAVE}</span>;
  return (
    <span className={`${sv.svTitle} ${sv.svTitleOneLine}`}>
      {save.title}
      <span className={sv.svSubtitle} data-testid="save-subtitle">
        {SUBTITLE_SEPARATOR}
        {sub}
      </span>
    </span>
  );
}
