import { render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import App from '../App';
import { FusionDragProto } from './FusionDragProto';
import { PROTO_WORLDS } from './fusionDrag';
import { resetDebugCache } from '../features/game/theme/debug';

// 原型的**旁路纪律**:只有 `?proto=fusion` 能进,进了就整个游戏树都不挂载。
// 手势体感由真机回答(jsdom 没有手指、没有布局、getBoundingClientRect 恒为 0),
// 这里只钉住「谁在什么时候被渲染」。

afterEach(() => {
  window.history.replaceState({}, '', '/');
  resetDebugCache();
});

describe('原型旁路', () => {
  it('?proto=fusion → 挂原型页(不进选择屏 / 不碰 store)', () => {
    window.history.replaceState({}, '', '/?proto=fusion');
    render(<App />);
    expect(screen.getByRole('heading', { name: '融合入口 · 拖拽手势原型' })).toBeInTheDocument();
    expect(screen.queryByText('选择你的世界')).not.toBeInTheDocument();
  });

  it('其它取值不当作开(不做「只要带 proto 参数就开」的宽松解析)', () => {
    window.history.replaceState({}, '', '/?proto=1');
    render(<App />);
    expect(screen.queryByRole('heading', { name: '融合入口 · 拖拽手势原型' })).not.toBeInTheDocument();
  });
});

describe('原型页', () => {
  it('渲染全部六张卡;未激活世界不可抓起(它在真机上充当「无效目标」)', () => {
    render(<FusionDragProto />);
    for (const w of PROTO_WORLDS) {
      expect(screen.getByRole('heading', { name: w.displayName })).toBeInTheDocument();
    }
    const draggable = document.querySelectorAll('[data-proto-card]');
    expect(draggable).toHaveLength(PROTO_WORLDS.length);
    expect(screen.getAllByText('敬请期待')).toHaveLength(PROTO_WORLDS.filter((w) => !w.active).length);
  });

  it('起手读数是「待命」,且尚无任何提交结果', () => {
    render(<FusionDragProto />);
    expect(screen.getByText(/待命/)).toBeInTheDocument();
    expect(screen.queryByText(/揉入/)).not.toBeInTheDocument();
  });
});
