package io.legado.app.eink.contract

/**
 * 引擎实体的不透明句柄。
 *
 * 端口方法之间需要传递「同一本书/书源/搜索结果」的引擎身份（实体对象
 * 可能被引擎侧就地变更，如目录刷新重定向替换 Book），但模块不应感知
 * 实体类型。宿主 bridge 实现类包装真实实体；模块侧只持有、回传，不解读。
 *
 * 句柄流转：
 * ```text
 * 端口方法（findBook/currentReadingBook/…）
 *        │ 返回
 *        ▼
 * Handle（宿主包装引擎实体）──模块持有/展示──► 端口方法（回传）
 *                                          │ 宿主解包实体
 *                                          ▼
 *                            实体被预取更新/重定向替换时：
 *                            端口以「返回新 Handle + 新 UiModel」告知，
 *                            模块替换本地持有
 * ```
 *
 * 宿主实现义务：句柄在「实体被替换」时应保持同一包装实例还是换新
 * 实例，以各端口方法 KDoc 为准（默认：端口返回新句柄即视为新身份，
 * 模块以返回值替换本地持有）。
 */
interface BookHandle

/**
 * 书源句柄：宿主书源实体的包装。
 *
 * [url] 是书源地址标识——换源页用它过滤无效源（与书籍记录的
 * origin 比对：相同则该源就是当前源，不可重复应用）。
 */
interface SourceHandle {
    /** 书源地址（宿主书源体系的唯一标识）。 */
    val url: String
}

/**
 * 搜索结果句柄：宿主搜索结果实体的包装。换源搜索的结果经
 * [ChangeSourceResultUiModel.handle] 持有，应用换源时回传给
 * [ChangeSourceEngine.changeBookSource]。
 */
interface SearchResultHandle
