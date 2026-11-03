# 设备相关偏好键审计（deviceLocalPreferenceKeys 维护清单）

- 机制：`deviceLocalPreferenceKeys`（BackupConfig.kt）随备份**照常导出**；同机恢复照常应用；**跨设备恢复跳过**（保留本机值）。`alwaysIgnoredPreferenceKeys` 是另一层（双向忽略），勿混收。
- 键面盘点（2026-11-03，基于 eink/md3-merge）：PreferKey 483 键（上游 480 + 墨本 4：eInkMode / einkBookshelfFilterId / einkBookshelfGroupsEnabled / ~~showReviewBubbles~~——后者本轮已迁 eink_preferences 自有键）；DataStore 字面量键 3 个；EinkLegacyPrefsStore 7 键；LocalPreferences(local_ui_status) 39 键不参与备份（天然设备本地）。

## A. 当前集合（21 键，逐键复核均合理）

eInkMode、labEInkDisplay、bookshelfGridCoverWidth、bookshelfLayoutModePortrait、bookshelfListCoverWidth、fontScale、volumeKeyPage、brightness、nightBrightness、threadCount、cacheBookThreadCount、bitmapCacheSize、webServiceWakeLock、readAloudWakeLock、audioPlayWakeLock、appFontPath、fontFolder、importBookPath、systemTypefaces、prevKeys、nextKeys。

## B. 建议新增（跨设备恢复后悬空/有害，待拍板）

| 键 | 语义 | 理由 |
|---|---|---|
| bgImage / bgImageN / largeContainerBackgroundImageLight/Dark / itemBackgroundImageLight/Dark | 应用与容器背景图**私有目录绝对路径**（6 键） | 图片文件不随备份走（Backup 只打包 bubblePackages 树），异机必悬空，主题渲染回落/报错 |
| paddingDisplayCutouts | 避让屏幕开孔 | 纯设备几何；无孔设备被灌「避让」产生异常留白 |
| volumeKeyPageOnPlay | 朗读时音量键翻页 | volumeKeyPage 已收，此为其播放期分支，不收会出现半套配置 |
| readBrightnessMode | 阅读亮度模式/档位 | 亮度值已设备本地；前光机模式灌入纯背光机不适 |
| read_aloud_capsule_offset_x/y（字面量键） | 朗读胶囊位置 | 悬浮窗位置随屏幅，dp 不归一屏幅 |

姊妹缺口：themeConfig.json 内嵌 backgroundImageLight/Dark 路径同样跨设备悬空且**无清洗器**（FontReferenceSanitizer 只管字体）——若 B 组采纳需同步补。

## D. 边界待讨论（默认不收）

ttsEngine（引擎因机而异 vs 选择是偏好）；输入硬件族（mangaVolumeKeyPage/reverseVolumeKeyPage/mouseWheelPage/keyPageOnLongPress/disableReturnKey）；性能档位族（antiAlias/optimizeRender/noAnimScrollPage/imageRetainNum/ttsPreSynthesisConcurrency）；漫画 eink 滤镜（enableMangaEInk/mangaEInkThreshold/enableMangaGray）；模糊族（enableBlur/enableProgressiveBlur/readMenuBlur*）；引擎能力族（composeEngine/isPredictiveBackEnabled/readerSharedElementEnabled）；pageTouchSlop；brightnessAuto/brightnessVwPos；ignoreBatteryPermission；sliderVibrator/selectVibrator；einkBookshelfFilterId/einkBookshelfGroupsEnabled（UI 状态/品味，倾向不收；2026-11-03 起两者已从 settings DataStore 迁入 eink_preferences 自有键，备份/恢复语义不变——仍跨设备恢复）。

明确**不收**：paragraphBubblePackage（存目录名且气泡包树随备份迁移）；webDavPassword（解密失败已有兜底）；backupPath/defaultBookTreeUri 等已在 alwaysIgnored。

## 维护准则（上游合并/本项目新增键时按序自问）

1. 值是路径/URI/内容指针？→ 文件随不随备份走（字体不走、bubblePackages 走）→ 不走则收。
2. 值来自设备探测/硬件清单（传感器/字体盘点/键位/开孔/EPDC 能力）？→ 收。
3. 合理值由屏幅/SoC/内存决定（px/缓存/并发/动画负载）？→ 收或列 D。
4. 纯用户选择/品味/账号 → 不收。
5. 拿不准 → 先列 D 观察一个备份周期；同机恢复永远无害，误收代价只是跨设备少同步一个键，宁缺勿滥。
