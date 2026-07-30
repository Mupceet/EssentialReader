package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel

/**
 * RSS文章实体，存储RSS订阅源获取的文章内容
 */
@Entity(
    tableName = "rssArticles",
    primaryKeys = ["origin", "link"]
)
data class RssArticle(
    // RSS源URL
    override var origin: String = "",
    // 分类
    var sort: String = "",
    // 标题
    var title: String = "",
    // 排序号
    var order: Long = 0,
    // 文章链接
    override var link: String = "",
    // 发布日期
    var pubDate: String? = null,
    // 描述
    var description: String? = null,
    // 正文内容
    var content: String? = null,
    // 图片
    var image: String? = null,
    // 分组
    @ColumnInfo(defaultValue = "默认分组")
    var group: String = "默认分组",
    // 是否已读
    var read: Boolean = false,
    // 自定义变量
    override var variable: String? = null
) : BaseRssArticle {

    override fun hashCode() = link.hashCode()

    override fun equals(other: Any?): Boolean {
        other ?: return false
        return if (other is RssArticle) origin == other.origin && link == other.link else false
    }

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    override val variableMap: HashMap<String, String> by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    fun toStar() = RssStar(
        origin = origin,
        sort = sort,
        title = title,
        starTime = System.currentTimeMillis(),
        link = link,
        pubDate = pubDate,
        description = description,
        content = content,
        image = image,
        group = group,
        variable = variable
    )
}
