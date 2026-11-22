// ADR-044 刀 2 · 没有小标题时,四个显示点(存档页条目、移除按钮可访问名、撤销条、大厅「继续」行)的 DOM 与改动前逐字节相同。
//
// 基线 `__fixtures__/adr044-no-subtitle-dom.html` 录于动生产代码之前:在 ADR-044 刀 1 `a20d478` 的前端原样上,
// 用本文件同一套场景、同一种拼接(两组列表 + 撤销条 + 继续块,`\n=====\n` 分隔、末尾一个换行)写出;之后只与它比,不随改动重录。
// 时间戳用本地时间构造,录制与比对不受时区影响。(前端 tsconfig 不带 node 类型,录制用的写文件分支没有留在这里。)
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { useGameStore } from '../../../state/gameStore';
import { createSavePointers, type SaveSummary } from '../../../state/savePointers';
import { useSavesView } from '../../../state/screens';
import { ArchetypeSelect } from '../ArchetypeSelect';
import { SavesScreen } from './SavesScreen';

// 读用 Vite 原生 ?raw(同 savePointers.test 先例)。
const fixture = Object.values(
  import.meta.glob('./__fixtures__/adr044-no-subtitle-dom.html', { query: '?raw', import: 'default', eager: true }),
)[0] as string | undefined;

const at = (m: number, d: number, h: number, min: number) => new Date(2026, m - 1, d, h, min).getTime();
// 三种没有小标题的形态:固定名世界的旧档、迁移来的空名指针、其余世界(已结局 + 暂时无法找回)。
const SAVES: SaveSummary[] = [
  { saveId: 'a', title: '派单', archetypes: ['cyberpunk'], turn: 3, ended: false, lastPlayedAt: at(11, 15, 9, 13), availability: 'available' },
  { saveId: 'b', title: '', archetypes: [], turn: 0, ended: false, lastPlayedAt: at(9, 13, 22, 26), availability: 'available' },
  { saveId: 'c', title: '雨夜便利店', archetypes: ['rules_creepy'], turn: 9, ended: true, lastPlayedAt: at(7, 14, 12, 40), availability: 'unavailable' },
];

beforeEach(() => {
  localStorage.clear();
  useGameStore.setState({
    archetypes: [
      { archetype: 'rules_creepy', displayName: '规则怪谈', tagline: null, vibeTag: null, experienceMode: 'shenjie', active: true },
      { archetype: 'cyberpunk', displayName: '赛博朋克', tagline: null, vibeTag: null, experienceMode: 'shenjie', active: true },
    ],
    fusions: [],
    capabilities: { history: false },
    saves: [],
    saveNotice: null,
  });
  const p = createSavePointers();
  for (const s of SAVES) p.write(s);
  useGameStore.getState().refreshSaves();
});
afterEach(() => {
  vi.useRealTimers();
  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
  useGameStore.setState({ saves: [] });
});

it('没有小标题:存档页两组列表、撤销条、大厅「继续」块与改动前逐字节相同', () => {
  vi.useFakeTimers();
  useSavesView.setState({ open: true, zone: 'fushi', focus: null });
  const page = render(<SavesScreen />);
  const lists = Array.from(page.container.querySelectorAll('ul')).map((u) => u.outerHTML);
  fireEvent.click(screen.getByRole('button', { name: '从这台设备移除《派单》' }), { detail: 1 });
  const toast = screen.getByTestId('removal-toast').outerHTML;
  act(() => vi.advanceTimersByTime(10));
  page.unmount();

  useSavesView.setState({ open: false, zone: 'fushi', focus: null });
  const lobby = render(<ArchetypeSelect />);
  const cont = lobby.container.querySelector('button')!.closest('div')!.outerHTML;

  const actual = [...lists, toast, cont].join('\n=====\n') + '\n';
  expect(lists).toHaveLength(2);
  expect(fixture, '基线缺失').toBeTypeOf('string');
  expect(actual).toBe(fixture);
});
