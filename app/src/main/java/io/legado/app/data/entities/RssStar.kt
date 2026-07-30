package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.parcelize.IgnoredOnParcel


/**
 * RSS收藏文章实体，存储用户收藏的RSS文章
 */
@Entity(
    tableName = "rssStars",
    primaryKeys = ["origin", "link"]
)
data class RssStar(
    // RSS源URL
    override var origin: String = "",
    // 分类
    var sort: String = "",
    // 标题
    var title: String = "",
    // 收藏时间
    var starTime: Long = 0,
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
    // 自定义变量
    override var variable: String? = null
) : BaseRssArticle {

    @delegate:Transient
    @delegate:Ignore
    @IgnoredOnParcel
    override val variableMap by lazy {
        GSON.fromJsonObject<HashMap<String, String>>(variable).getOrNull() ?: hashMapOf()
    }

    fun toRssArticle() = RssArticle(
        origin = origin,
        sort = sort,
        title = title,
        link = link,
        pubDate = pubDate,
        description = description,
        content = content,
        image = image,
        group = group,
        variable = variable
    )
}
