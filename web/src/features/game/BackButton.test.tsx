import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BackButton } from './BackButton';
import { SceneBanner } from './SceneBanner';
import { SkinContext } from './theme/contract';
import { SkinRuntime } from './theme/lifecycle';
import { SKINS } from './theme/skins';
import type { SkinId } from './theme/registry';

// 导航层(线 C)。**守行为不守像素**(ADR-018 §5 Q8):
//   ① 导航不可缺席 —— 无皮肤也渲染、可点、回传;
//   ② 四世界形态各不相同 —— 断言「四个 class 两两不等」,不断言具体长什么样;
//   ③ 时间感参数只对配了它的世界生效(其余世界行为逐字不变)。

function withSkin(skinId: SkinId | null, ui: React.ReactNode) {
  const skin = skinId ? SKINS[skinId]! : null;
  return render(
    <SkinContext.Provider value={skin ? { skin, runtime: new SkinRuntime() } : null}>
      {ui}
    </SkinContext.Provider>,
  );
}

describe('BackButton · 导航不可缺席', () => {
  it('无皮肤(未登记世界 / 降级路径)照常渲染并可点', async () => {
    const onBack = vi.fn();
    withSkin(null, <BackButton onBack={onBack} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    await userEvent.click(btn);
    expect(onBack).toHaveBeenCalledTimes(1);
  });

  it('四个世界都渲染出返回键(没有哪个世界是出不去的)', () => {
    for (const id of ['rules_creepy', 'cultivation', 'apocalypse', 'cthulhu'] as SkinId[]) {
      const { unmount } = withSkin(id, <BackButton onBack={() => {}} />);
      expect(screen.getByRole('button', { name: '返回世界选择' })).toBeInTheDocument();
      unmount();
    }
  });
});

describe('BackButton · 四世界形态各不相同', () => {
  it('四套皮肤的 backClass 两两不等,且都非空', () => {
    const ids: SkinId[] = ['rules_creepy', 'cultivation', 'apocalypse', 'cthulhu'];
    const classes = ids.map((id) => SKINS[id]!.backClass);
    expect(classes.every((c) => c.length > 0)).toBe(true);
    expect(new Set(classes).size).toBe(ids.length);
  });

  it('皮肤 class 真的挂到了按钮上(不是只登记没接线)', () => {
    withSkin('cultivation', <BackButton onBack={() => {}} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    expect(btn.className).toContain(SKINS.cultivation!.backClass);
  });

  it('未配 backClass 时 class 属性不留空段(§4.5.1 对拍口径)', () => {
    withSkin(null, <BackButton onBack={() => {}} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    expect(btn.className).not.toMatch(/\s\s|^\s|\s$/);
  });
});

describe('BackButton · 按压时长(时间感参数,非世界开关)', () => {
  it('配了 backPressDurMs 的世界:按下写 --press-dur', async () => {
    withSkin('cthulhu', <BackButton onBack={() => {}} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    await userEvent.click(btn);
    expect(btn.style.getPropertyValue('--press-dur')).toMatch(/^0\.\d+s$/);
  });

  it('未配的世界:一个字都不写(行为与刀 1–3 逐字不变)', async () => {
    withSkin('rules_creepy', <BackButton onBack={() => {}} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    await userEvent.click(btn);
    expect(btn.style.getPropertyValue('--press-dur')).toBe('');
  });

  it('每次按下重新求值:两次取值不全相同(「时长不可信」不是常量)', async () => {
    withSkin('cthulhu', <BackButton onBack={() => {}} />);
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    const seen = new Set<string>();
    for (let i = 0; i < 12; i++) {
      await userEvent.click(btn);
      seen.add(btn.style.getPropertyValue('--press-dur'));
    }
    expect(seen.size).toBeGreaterThan(1);
  });
});

describe('SceneBanner 接线', () => {
  it('给了 onBack → 返回键与「第 N 回合」同排', () => {
    render(
      <SceneBanner
        sceneUrl={null}
        turn={3}
        dangerLevel="high"
        title="雨夜便利店"
        tone="潮湿"
        onBack={() => {}}
      />,
    );
    const btn = screen.getByRole('button', { name: '返回世界选择' });
    const phase = screen.getByText('第 3 回合 · 危险度 高');
    expect(btn.parentElement).toBe(phase.parentElement); // 同一行容器
  });

  it('不给 onBack → 不渲染(既有组件测试零回归)', () => {
    render(
      <SceneBanner sceneUrl={null} turn={1} dangerLevel="low" title="t" tone="x" />,
    );
    expect(screen.queryByRole('button', { name: '返回世界选择' })).toBeNull();
  });
});
