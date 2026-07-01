import type { Archetype, DangerLevel } from '../../types/schema';
import { dangerLabel, sceneImageUrl } from './scene';
import styles from './game.module.css';

// #8 顶部 establishing-shot 氛围带(静态第 1 档,纯前端)。据 world.archetypes[0] 选一张
// 静态底图铺在顶部,图底渐变融进正文背景(顺带盖住占位图右下角水印);其上叠现成状态字段
// (回合 / 危险度中文 / 标题 / tone 副标题)。无新增时间/天气/地点字段(本轮无数据源)。
//
// 守界:纯展示 + 现成 store 字段,无平台 IO、不引动画库(纯 CSS 渐变)。图缺失时优雅降级
// (不显图、布局靠固定高度不塌)。

export function SceneBanner({
  archetype,
  turn,
  dangerLevel,
  title,
  tone,
}: {
  archetype: Archetype | undefined;
  turn: number;
  dangerLevel: DangerLevel;
  title: string;
  tone: string;
}) {
  const imageUrl = sceneImageUrl(archetype);

  return (
    <header className={styles.banner}>
      {imageUrl && (
        <div
          className={styles.bannerImg}
          style={{ backgroundImage: `url(${imageUrl})` }}
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
