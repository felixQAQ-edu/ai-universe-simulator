import type { DangerLevel } from '../../types/schema';
import { dangerLabel } from './scene';
import { useSkin } from './theme/contract';
import styles from './game.module.css';

// #8 顶部 establishing-shot 氛围带。图底渐变融进正文背景(顺带盖住占位图右下角水印),
// 其上叠现成状态字段(回合 / 危险度中文 / 标题 / tone 副标题)。
//
// **ADR-018 刀 1**:不再自己解析 archetype —— 封面由 `PlayingScreen` 的**唯一一次**世界判定
// (`resolveWorldTheme`)算出后经 `sceneUrl` 传入(§4.1「世界判定只发生一次,子组件消费结果」)。
// 皮肤从 context 消费(消费判定结果,不是重新判定):规则怪谈把这张图当**监控画面**,
// 挂上「呼吸」= 本屏唯一的持续环境动效。
//
// 守界:纯展示 + 现成 store 字段,无平台 IO。图缺失时优雅降级(不显图、布局靠固定高度不塌)。

export function SceneBanner({
  sceneUrl,
  turn,
  dangerLevel,
  title,
  tone,
}: {
  /** 顶部底图路径;null = 未配图,不渲染图层(降级不塌)。 */
  sceneUrl: string | null;
  turn: number;
  dangerLevel: DangerLevel;
  title: string;
  tone: string;
}) {
  const skin = useSkin()?.skin;

  return (
    <header className={styles.banner}>
      {sceneUrl && (
        <div
          // 空段滤掉:未登记世界的 class 属性与刀 1 之前逐字节一致。
          className={[styles.bannerImg, skin?.bannerImgClass].filter(Boolean).join(' ')}
          style={{ backgroundImage: `url(${sceneUrl})` }}
          aria-hidden="true"
        />
      )}
      {/* 图底渐变:融进正文背景 + 盖住底部(含右下角水印)+ 托住其上文字的可读性。 */}
      <div className={styles.bannerScrim} aria-hidden="true" />
      <div className={styles.bannerText}>
        <p className={styles.phase}>
          第 {turn} 回合 · 危险度 {dangerLabel(dangerLevel)}
        </p>
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.tone}>{tone}</p>
      </div>
    </header>
  );
}
