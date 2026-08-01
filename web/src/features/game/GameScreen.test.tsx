import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import type { ClientWorld } from '../../api';
import { useGameStore } from '../../state/gameStore';
import { GameScreen } from './GameScreen';

// 导航层的**屏级**行为(线 C)。直打生产 store(reset 不碰网络),验的是三条产品裁定
// 在真实接线上成立,而不是组件孤立地能点。

const WORLD: ClientWorld = {
  schemaVersion: '0.4',
  mode: 'single',
  archetypes: ['rules_creepy'],
  world: { title: '雨夜便利店', background: '', dangerLevel: 'high', tone: '潮湿' },
  character: { attributes: { hp: 80, san: 60 }, traits: [], inventory: [] },
  rules: [],
  state: { turn: 2, status: 'ongoing', timeline: '', logSummary: '', log: [] },
  endings: [],
};

function enterGame(status: 'awaiting' | 'generating') {
  useGameStore.setState({
    status,
    world: WORLD,
    saveId: 's-1',
    resumableSaveId: 's-1',
    turn: 2,
    narrative: '雨还在下。',
    availableActions: [{ id: 'A', text: '观察', hint: '' }],
    attributeAxes: [{ key: 'hp', displayName: '体力' }],
    attributeValues: { hp: 80 },
  });
}

afterEach(() => {
  useGameStore.getState().reset();
  useGameStore.setState({ resumableSaveId: null });
});

describe('等待期导航(world-gen 首局线上实测 ~120s)', () => {
  it('生成中有「取消」→ 回选择屏', async () => {
    useGameStore.setState({ status: 'initializing' });
    render(<GameScreen />);
    expect(screen.getByText('世界正在生成……')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '取消' }));
    expect(useGameStore.getState().status).toBe('idle');
  });
});

describe('游戏内返回(退出不弃局)', () => {
  it('点返回 → 回选择屏,且存档指针留着(「继续上局」还在)', async () => {
    enterGame('awaiting');
    render(<GameScreen />);

    await userEvent.click(screen.getByRole('button', { name: '返回世界选择' }));

    const s = useGameStore.getState();
    expect(s.status).toBe('idle');
    expect(s.world).toBeNull();
    expect(s.resumableSaveId).toBe('s-1'); // 不弃局:回去就能续
  });

  it('**生成中照样可点**(不禁用):一回合 15s+,那恰是玩家最想退出的时刻', async () => {
    enterGame('generating');
    render(<GameScreen />);

    const btn = screen.getByRole('button', { name: '返回世界选择' });
    expect(btn).not.toBeDisabled(); // 决策圈此刻是禁用的,导航不是
    await userEvent.click(btn);

    expect(useGameStore.getState().status).toBe('idle');
    expect(useGameStore.getState().resumableSaveId).toBe('s-1');
  });

  it('不做二次确认:一次点击直接回选择屏(无损操作不制造摩擦)', async () => {
    enterGame('awaiting');
    render(<GameScreen />);
    await userEvent.click(screen.getByRole('button', { name: '返回世界选择' }));
    expect(useGameStore.getState().status).toBe('idle');
  });
});
