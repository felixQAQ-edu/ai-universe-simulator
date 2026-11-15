# XunSong · 《寻常》宋体子集

项目里第二份网页字体(ADR-020「《寻常》局内皮肤」一节决策 6)。只给《寻常》皮肤的两处用:世界标题、档名。
数字、轴名、正文都不用它。

| 项 | 值 |
|---|---|
| 字体 | Noto Serif SC Medium(字重 500),Version 2.003 |
| 来源 | `notofonts/noto-cjk` @ `9b0f1436e455d902de067a2501422e5dc71ad16b`,`Serif/SubsetOTF/SC/NotoSerifSC-Medium.otf`(11,734,356 B,git blob `490a57d8e5e94cb01402fed126dc009561baf585`) |
| 源文件 sha256 | `e4e9beb5aaeb951e101aa8f122e2a6801ea9e48ac9f9b6bdfeef2436b55bc8ee` |
| 授权 | SIL OFL 1.1,全文见同目录 `OFL.txt`(= 同一提交的 `Serif/LICENSE`,sha256 `6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2`)。字体 name 表的版权行是「© 2017-2024 Adobe」,**没有声明 Reserved Font Name**,子集保留原字体名不受 OFL 第 3 条限制 |
| 子集文件 | `xun-song.woff2`,10,536 B,sha256 `9750b07784547e8f75f85e538bb36e9053a79479936c9ba61e12f33840ecd347`(同一命令跑两次字节相同) |
| 字符集 | 32 个码位:「寻常」+ 四根轴的十二个档名(下面命令里 `--text` 的那一串) |

## 裁剪命令(fontTools 4.61.1 + brotli)

```bash
pyftsubset NotoSerifSC-Medium.otf --text='寻常康健耗损衰弱炽热转淡熄了岔口尚多路已收窄只剩一条轻身有所系深' --flavor=woff2 --output-file=xun-song.woff2
```

## 重裁时核过的(2026-11-15)

- 输出的 cmap 恰好是上面 32 个码位,与动效样张 `docs/xun-g2-samples/xun-motion.html` 字体链接 `text=` 的字符集相同。
- 档名不出现数字,不需要等宽,不带 `--layout-features`。

## 改文案必须重裁

字符集是写死的。改世界名(`ArchetypeRegistry` life_sim 条末的 `fixedWorldTitle`)或任何一个档名(同一条的 `withBands`),
**必须同步重裁**,并更新 `../subset.ts` 的 `XUN_SONG_CHARS` 与本文件。`xunSkin.test.tsx` 钉住「这两处可能出现的字都在子集里」
(那份字符串是从 Java 手抄的,服务端改了文案前端测试不会红,见 ADR-020 该节已知代价 1);
缺字时浏览器按字回落到系统宋体,不出豆腐块,但一个词会有两种字形。
