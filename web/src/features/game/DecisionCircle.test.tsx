import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { AvailableAction } from '../../types/schema';
import { DecisionCircle } from './DecisionCircle';

// 决策圈:选项渲染 + 定性风险提示(ADR-011)展示层——hint 有则成独立小字,缺失时不渲染、不占位。

const action = (over: Partial<AvailableAction>): AvailableAction => ({
  id: 'A',
  text: '硬闯禁区',
  hint: '',
  ...over,
});

describe('DecisionCircle', () => {
  it('渲染每个选项的 id 与正文', () => {
    render(
      <DecisionCircle
        actions={[action({ id: 'A', text: '硬闯禁区' }), action({ id: 'B', text: '原路退回' })]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    expect(screen.getByText('硬闯禁区')).toBeInTheDocument();
    expect(screen.getByText('原路退回')).toBeInTheDocument();
  });

  it('hint 有值:渲染为选项文字下方的独立小字', () => {
    render(
      <DecisionCircle
        actions={[action({ id: 'A', text: '硬闯禁区', hint: '越靠近,那低语越清晰' })]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    expect(screen.getByText('越靠近,那低语越清晰')).toBeInTheDocument();
  });

  it('hint 缺失(空串):不渲染任何提示元素、不占位', () => {
    render(
      <DecisionCircle
        actions={[action({ id: 'A', text: '原路退回', hint: '' })]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    // 没有 hint 文本节点;button 内只有主行三 span(actionMain / actionId / actionText),无独立小字块。
    const button = screen.getByRole('button');
    expect(button.querySelectorAll('span')).toHaveLength(3);
    expect(button.textContent).toBe('A.原路退回');
  });

  it('点击选项回传其 id', () => {
    const onChoose = vi.fn();
    render(
      <DecisionCircle
        actions={[action({ id: 'C', text: '呼救', hint: '可能引来它注意' })]}
        disabled={false}
        onChoose={onChoose}
      />,
    );
    screen.getByRole('button').click();
    expect(onChoose).toHaveBeenCalledWith('C');
  });
});
