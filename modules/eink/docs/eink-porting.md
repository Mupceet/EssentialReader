# E-Ink 移植指南（已迁移）

本文档的最终维护版本已归档至宿主接入面目录：
`modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`
（2026-09-05 经 develop@01ee1e956 全量回放移植实测修订）。

## 端口表增量

归档版本之后的端口变化：

| 端口 | 必需性 | 职责 |
|---|---|---|
| ReaderSelectionEngine | 可选 | 阅读页长按选择的书签/笔记落库。v2 方法清单：saveMarking（划线/想法同锚点 upsert）、deleteMarking、findMarking、togglePageBookmark，Commit 增 note（默认空串）/thought（默认 false）与 ReaderMarkingDetail；v2 过渡期同时保留 v1 方法（resolveSelection/saveBookmark），Task 5 收敛移除。未注册时选择菜单降级为仅复制 |
