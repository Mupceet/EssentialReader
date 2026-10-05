# hanvon/ —— Hanvon 设备专项适配（清理免疫）

本包承载针对 Hanvon 墨水屏设备的 OEM 专项代码，与阅读 UI 无耦合；当前仅
一个文件：

- `HanvonCleanerHook.kt`——LSPosed/Xposed 模块入口，免疫 Hanvon 系统
  「回桌面清后台」对墨本阅读的清理。机制调研、方案对比与实验证据见
  `D:\Projects\HanvanS10\05-调研与方案总结\`（独立项目原型的演进记录在
  同目录 `06-墨本阅读增强\`）。

## 模块如何生效（用户侧）

1. 设备已 root 且安装 Zygisk LSPosed；
2. 安装本应用后，在 LSPosed 管理器中启用「墨本阅读」模块；
3. 作用域已随模块声明（`xposedscope`）**默认勾选 设置（hanvon.aebr.hvsettings）
   与 桌面（hanvon.aebr.hvLauncher）**，确认两项在列即可；
4. 重启设备。

未启用 LSPosed 的设备不受任何影响（声明性元数据 + 若干 KB 的惰性类）。

## 工程侧要点（改代码前先读）

- Xposed API 用**官方 jar 入库**：`libs/xposed-api-82.jar`（源
  https://api.xposed.info/，25478 字节，SHA-256 f48c635f…8e25，API 2016 年
  定型后冻结、纯接口零传递依赖）。入库原因：Gradle 仓库无法由库模块向
  宿主传递，vendored 后任何宿主零额外配置。**必须 compileOnly**：
  运行期由 LSPosed 在宿主进程提供真实 bridge，打进 dex 会与之冲突。
- 入口类在 `src/main/assets/xposed_init` 以字符串声明，R8 无法感知——
  保活规则在 `consumer-rules.pro`（缺失则 release 构建模块静默失效）。
- 声明性元数据（xposedmodule 等）在 `src/main/AndroidManifest.xml`，
  经 manifest merge 进入宿主 APK；经 Maven 消费本 AAR 的其它宿主同样
  会带上该声明（声明本身无行为，需 LSPosed 显式启用才生效）。
