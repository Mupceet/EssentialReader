# eink-lib —— E-Ink Compose 模块独立演进线

`:modules:eink`（E-Ink 墨水屏 Compose 阅读模块）的独立仓库形态，归档于
EssentialReader 的 `eink/lib` 分支（孤儿分支：树内只有本模块工程，
演进历史与宿主 app 解耦）。功能演进在独立 git worktree 中进行。

## 集成方式（二选一）

**方式一 · git 子模块（源码嵌入）**：宿主以子模块引用本分支，走源码
嵌入路径——

```bash
git submodule add -b eink/lib https://github.com/Mupceet/EssentialReader.git modules/eink
```

宿主侧接线与手工复制模块树完全一致（settings 挂载 einkLibs、根目录
提供插件别名、AGP < 9 时步骤 3b），完整流程见
`modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md`
§2；md3/port/eink 分支即此形态。升级 = 在宿主内推进子模块指针
（`git submodule update --remote modules/eink`）后提交。

**方式二 · 二进制依赖**：在本工程执行发布，宿主直接依赖坐标——

```bash
./gradlew :modules:eink:publishReleasePublicationToMavenLocal   # 或远程仓库
```

宿主接入与注意事项（compose-runtime 自备、POM 网络怪癖、产物栈绑定）
见 EINK-PORTING.md §2A。

## 本地构建

```bash
./gradlew :modules:eink:compileDebugKotlin       # 编译
./gradlew :modules:eink:testDebugUnitTest        # 单测
```

需要 `local.properties` 指向 Android SDK（`sdk.dir=...`）。

## 文档分工

| 文档 | 内容 |
|---|---|
| `modules/eink/.../contract/README.md` | 宿主接入面地图（端口总表、跨界类型、装配时序、能力裁剪方言） |
| `modules/eink/.../contract/EINK-PORTING.md` | 移植手册（消费门槛、嵌入/发布步骤、宿主差异、验证清单） |
| `modules/eink/gradle/libs.versions.toml` 头部 | 依赖版本语义、消费门槛依据、升档协议的**权威注释** |
| `modules/eink/build.gradle.kts` 版本注释 | 发版沿革与历史坐标 |
