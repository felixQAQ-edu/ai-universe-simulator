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

// ADR-039 验证 D:不带 tag 的选项,DOM 与改动前逐字节相同。
// 下面这串是在动 DecisionCircle 之前(main 2d79c9e 的实现)录下的 innerHTML,之后不得改。
// 唯一的归一化:CSS Modules 的 class 哈希(`_name_hash` → `name`)。哈希随样式文件内容变,给样式文件加一条规则
// 就会换一个哈希,那不是 DOM 结构变化(同 ADR-018 §4.5.1 的对拍口径)。
describe('DecisionCircle · 不带 tag 的 DOM 基线(ADR-039)', () => {
  it('innerHTML 与改动前录下的字符串全等', () => {
    const { container } = render(
      <DecisionCircle
        actions={[
          action({ id: 'A', text: '硬闯禁区', hint: '越靠近,那低语越清晰' }),
          action({ id: 'B', text: '【怪点子】原路退回' }),
        ]}
        disabled={false}
        onChoose={() => {}}
      />,
    );
    expect(container.innerHTML.replace(/_([A-Za-z]+)_[0-9a-f]{6}/g, '$1')).toBe(
      '<div class="actions"><button type="button" class="action"><span class="actionMain">' +
        '<span class="actionId">A.</span><span>硬闯禁区</span></span>' +
        '<span class="actionHint">越靠近,那低语越清晰</span></button>' +
        '<button type="button" class="action"><span class="actionMain">' +
        '<span class="actionId">B.</span><span>【怪点子】原路退回</span></span></button></div>',
    );
  });
});
