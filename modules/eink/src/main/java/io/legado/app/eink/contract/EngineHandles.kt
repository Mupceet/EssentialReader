package io.legado.app.eink.contract

/**
 * 引擎实体的不透明句柄。
 *
 * 用途：端口方法之间需要传递「同一本书/书源/搜索结果」的引擎身份。
 * 引擎侧实体可能被就地变更（如目录刷新的重定向替换、详情预取的
 * 就地更新），模块不感知实体类型——宿主 bridge 实现类包装真实实体，
 * 模块只持有、回传，不解读。
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
 * origin 比对）。
 */
interface SourceHandle {
    val url: String
}

/** 搜索结果句柄：宿主搜索结果实体的包装。 */
interface SearchResultHandle

/**
 * 搜索结果的展示与操作载体：展示字段 + 引擎身份。
 *
 * 宿主把搜索结果映射为实现了本接口的展示模型（字段由各使用场景的
 * UiModel 定义），[handle] 在应用后续操作（如换源）时回传给端口。
 */
interface SearchResultRef {
    val handle: SearchResultHandle
}
