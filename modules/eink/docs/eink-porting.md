# E-Ink 移植指南（已迁移）

本文档的最终维护版本已归档至宿主接入面目录：
`modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`
（2026-09-05 经 develop@01ee1e956 全量回放移植实测修订）。

## 端口表增量

归档版本之后的端口变化：

| 端口 | 必需性 | 职责 |
|---|---|---|
| ReaderSelectionEngine | 可选 | 阅读页长按选择的划线/想法落库、点按已有标记的浮条/浮窗（删除/写想法）与页面级书签 toggle。v2 方法清单：saveMarking（划线/想法同锚点 upsert）、deleteMarking、findMarking、togglePageBookmark；Commit = chapterIndex/start/end/selectedText/note/thought，附 ReaderMarkingDetail（v1 选区书签链路——解析预填、选区书签落库与书签弹层——已退役，页面级书签接管）。无端口：长按选择不启用（无浮条/无落库），点按标记浮条/浮窗不可达，下拉书签与顶栏书签钮隐藏（Task 9 落地其 UI 门控） |
