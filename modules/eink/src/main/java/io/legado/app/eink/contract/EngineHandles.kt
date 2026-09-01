package io.legado.app.eink.contract

/**
 * 引擎实体的不透明句柄。
 *
 * 端口方法之间需要传递「同一本书/书源/搜索结果」的引擎身份（实体对象
 * 可能被引擎侧就地变更，如目录刷新重定向替换 Book），但模块不应感知
 * 实体类型。宿主 bridge 实现类包装真实实体；模块侧只持有、回传，不解读。
 */
interface BookHandle

/** 书源句柄（包装 BookSource）。 */
interface SourceHandle {
    /** 书源地址（换源页用于过滤无效源）。 */
    val url: String
}

/** 搜索结果句柄（包装 SearchBook）。 */
interface SearchResultHandle

/** 换源/详情页搜索结果的展示与操作载体：展示字段 + 引擎身份。 */
interface SearchResultRef {
    val handle: SearchResultHandle
}
