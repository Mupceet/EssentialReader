package io.legado.app.api.controller

import io.legado.app.api.ReturnData
import io.legado.app.data.appDb
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * TXT目录规则控制器，提供Web API管理TXT书籍的目录识别规则
 * 支持获取所有规则、保存规则、删除规则
 */
object TxtTocRuleController {

    /** 获取所有TXT目录规则 */
    val allRules: ReturnData
        get() {
            val rules = appDb.txtTocRuleDao.all
            val returnData = ReturnData()
            returnData.setData(GSON.toJson(rules))
            return returnData
        }

    /** 保存或更新TXT目录规则 */
    fun saveRule(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<TxtTocRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            if (rule.name.isBlank()) {
                returnData.setErrorMsg("规则名称不能为空")
            } else if (rule.rule.isBlank()) {
                returnData.setErrorMsg("目录规则不能为空")
            } else {
                if (rule.serialNumber < 0) {
                    rule.serialNumber = appDb.txtTocRuleDao.maxOrder + 1
                }
                appDb.txtTocRuleDao.insert(rule)
            }
        }
        return returnData
    }

    /** 删除TXT目录规则 */
    fun delete(postData: String?): ReturnData {
        val returnData = ReturnData()
        postData ?: return returnData.setErrorMsg("数据不能为空")
        val rule = GSON.fromJsonObject<TxtTocRule>(postData).getOrNull()
        if (rule == null) {
            returnData.setErrorMsg("格式不对")
        } else {
            appDb.txtTocRuleDao.delete(rule)
        }
        return returnData
    }
}