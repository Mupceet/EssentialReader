# E-Ink Compose library consumer ProGuard rules

# ── Hanvon 清理免疫模块（hanvon/HanvonCleanerHook） ──────────────────────────
# 入口类只被 assets/xposed_init 以字符串引用，R8 的引用图不可见；且 de.robv
# 的 Xposed API 是 compileOnly（R8 输入里不存在），实测 R8 会把入口类的
# implements 子句整个剥掉——LSPosed 装载后 instanceof 判定失败、模块静默
# 失效。因此两条规则缺一不可：
#
# 1. 整包保活（含 $Companion）：类名与 xposed_init 一致、成员完整
#    （回调由运行时反射调度）。
-keep class io.legado.app.eink.hanvon.** { *; }

# 2. 保活对 Xposed bridge 接口的实现关系：R8 对“缺失类”接口默认不保留
#    implements 子句，此规则强制其不参与裁剪。
-keep interface de.robv.android.xposed.** { *; }

# Xposed API 桩为 compileOnly（运行期由 LSPosed 提供），R8 解析引用图时
# 找不到这些类属预期，勿告警。
-dontwarn de.robv.android.xposed.**
