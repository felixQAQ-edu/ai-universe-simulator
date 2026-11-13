# QiriKai · 《七日开张》楷体子集

项目里第一份网页字体(ADR-038 Slice G-2 决策 5)。只给《七日开张》皮肤的四处用:世界标题、时辰牌、轴名、数值数字。

| 项 | 值 |
|---|---|
| 字体 | 霞鹜文楷 LXGW WenKai Medium v1.530 |
| 来源 | `lxgw/LxgwWenKai` @ `d20c88d9ed1570a54ef09b46f37724712bbe0203`,`fonts/TTF/LXGWWenKai-Medium.ttf` |
| 源文件 sha256 | `f4b8a34d1f838fe411540cb7c97b1c384ac9738a876c4104500bc42508b2312a` |
| 授权 | SIL OFL 1.1,全文见同目录 `OFL.txt`(sha256 `1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0`,取自同一提交);其附加许可允许为网页字体裁子集并保留字体名 |
| 子集文件 | `qiri-kai.woff2`,6,692 B,sha256 `cc0b6d3c5b2129e149f39b41dcb7902e5703563bcdd7fb16041829b890d2fc9c` |
| 字符集 | 36 个码位:35 个字 + 空格(下面命令里 `--text` 的那一串) |

## 裁剪命令(fontTools 4.61.1 + brotli)

```bash
pyftsubset LXGWWenKai-Medium.ttf --text='0123456789·一二三四五六七日早午晚市夜集开张收摊本熟客底细第 ' --flavor=woff2 --output-file=qiri-kai.woff2
```

## 重裁时核过的(2026-11-13)

- 输出的 cmap 恰好是上面 36 个码位;与样张子集 `docs/qiri-g2-samples/qiri-kai-medium-subset.woff2` 的字符集相同(字节不同,以本文件为准)。
- 0–9 的 advance width 全部是 600(unitsPerEm 1000):数字天然等宽。源字体 GSUB 没有 `tnum`,所以不带 `--layout-features`。

## 改文案必须重裁

字符集是写死的。改时辰标签文案(`ClientProjection.java`)、轴名(`ArchetypeRegistry`)或世界标题,**必须同步重裁**,
并更新 `../subset.ts` 的 `QIRI_KAI_CHARS` 与本文件。`qiriSkin.test.tsx` 钉住「这四处可能出现的字都在子集里」;
缺字时浏览器按字回落到系统楷体,不出豆腐块,但一个词会有两种字形。
