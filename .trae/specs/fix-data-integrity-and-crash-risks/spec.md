# 修复关键缺陷 - 数据完整性与崩溃风险 Spec

## Why
过去24小时提交的代码审查发现了4个关键缺陷：数据库操作缺少事务保护可能导致数据永久丢失、非空断言可能导致应用崩溃、并发控制中存在竞态条件、EPUB文件解析缺少异常处理。这些缺陷均具备明确的可触发场景，且用户可直接感知其影响。

## What Changes
- 为所有 `delByBook` + `insert` 模式的数据库操作添加事务保护，确保数据原子性
- 消除 `ReadBook` 和 `ReadManga` 中的 `!!` 非空断言，改为安全的空值检查，防止 KotlinNullPointerException 崩溃
- 修复 `ConcurrentRateLimiter` 中的双重检查锁定（DCL）缺陷，使用线程安全的原子操作
- 增强 EPUB 文件解析的异常处理和资源管理

## Impact
- Affected specs: 无（新创建）
- Affected code:
  - `app/src/main/java/io/legado/app/api/controller/BookController.kt` - 目录刷新事务保护
  - `app/src/main/java/io/legado/app/model/ReadBook.kt` - 非空断言替换、目录更新事务保护
  - `app/src/main/java/io/legado/app/model/ReadManga.kt` - 非空断言替换、目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/book/read/ReadBookViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/main/MainViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/book/info/BookInfoViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/book/toc/TocViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/book/audio/AudioPlayViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/ui/book/manga/ReadMangaViewModel.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/service/ExportBookService.kt` - 目录更新事务保护
  - `app/src/main/java/io/legado/app/help/ConcurrentRateLimiter.kt` - 双重检查锁定修复
  - `modules/book/src/main/java/me/ag2s/epublib/epub/EpubReader.java` - 异常处理增强
  - `modules/book/src/main/java/me/ag2s/epublib/util/zip/ZipFileWrapper.java` - 资源管理增强

## MODIFIED Requirements

### Requirement: 目录更新操作的事务原子性
系统在执行 `delByBook` + `insert` 的目录更新操作时，SHALL 使用 Room 数据库事务（`runInTransaction`）确保删除和插入操作在同一个原子事务中完成，防止因中途崩溃导致章节数据永久丢失。

#### Scenario: 正常目录刷新
- **WHEN** 用户触发书籍目录刷新
- **THEN** 系统在单个事务中先删除旧章节再插入新章节，操作完成后更新书籍元数据

#### Scenario: 目录刷新过程中应用崩溃
- **WHEN** 目录刷新事务执行过程中应用被系统杀死
- **THEN** 数据库自动回滚事务，旧章节数据保持完整；下次打开应用时书籍仍有完整目录

### Requirement: 异步加载章节内容的安全性
系统在异步协程中加载章节内容时，SHALL 使用安全的方式访问 `ReadBook.book` 和 `ReadManga.book` 的可空引用，不得使用 `!!` 非空断言，防止因书籍状态变更导致 KotlinNullPointerException。

#### Scenario: 加载过程中切换书籍
- **WHEN** 用户打开书籍A，正在加载章节内容，然后快速切换到书籍B
- **THEN** 系统检测到 book 引用已变更或为空，安全地取消当前加载任务，不触发崩溃

#### Scenario: 正常加载章节内容
- **WHEN** 用户正常阅读书籍，章节内容异步加载
- **THEN** 系统使用安全的空值检查访问 book 引用，正常加载并显示内容

### Requirement: 并发速率限制的线程安全
`ConcurrentRateLimiter` 中的 `fetchStart()` 方法 SHALL 使用正确的双重检查锁定（DCL）模式或线程安全的原子操作，确保并发访问下的 `ConcurrentRecord` 创建和状态管理正确。

#### Scenario: 多线程并发访问同一书源
- **WHEN** 多个线程同时请求访问同一书源
- **THEN** 系统正确创建唯一的 `ConcurrentRecord` 实例，并正确管理并发速率限制

### Requirement: EPUB 文件解析的健壮性
系统在解析 EPUB 文件时 SHALL 正确处理异常情况，包括损坏的文件、无效的编码、缺失的资源等，确保不会因解析失败而崩溃，且所有文件资源（ZipFile、InputStream）均被正确关闭。

#### Scenario: 导入损坏的 EPUB 文件
- **WHEN** 用户尝试导入损坏的 EPUB 文件
- **THEN** 系统捕获解析异常，显示友好的错误提示，不崩溃，资源被正确释放

#### Scenario: 正常导入 EPUB 文件
- **WHEN** 用户导入正常的 EPUB 文件
- **THEN** 系统正确解析并导入书籍，解析完成后资源被正确关闭