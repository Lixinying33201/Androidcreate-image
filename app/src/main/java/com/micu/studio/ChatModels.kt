package com.micu.studio

import java.util.UUID

/** 资产库分类：生成图 / 上传 / 人物资产 / 提示词 / 收藏 */
enum class AssetCategory(val dir: String, val label: String) {
    GENERATED("generated", "生成图"),
    UPLOAD("upload", "上传"),
    PERSON("person", "人物资产"),
    PROMPT("prompt", "提示词"),
    FAVORITE("favorite", "收藏")
}

/** 资产条目（图片/提示词），支持标签、重命名、按会话归档 */
data class AssetItem(
    val path: String,
    val category: AssetCategory,
    var name: String,
    val tags: MutableList<String> = mutableListOf(),
    val sessionId: String? = null,
    val ts: Long
)

/** 单张生成结果（带时间标注） */
data class GenImage(
    val path: String,
    val prompt: String,
    val model: String,
    val size: String,
    val quality: String?,
    val ts: Long
)

/** 一条对话消息 */
data class ChatMessage(
    val id: Long,
    val role: String,                 // user / assistant / system
    val text: String,
    val uploads: List<String> = emptyList(),    // 用户上传的参考图路径
    val results: List<GenImage> = emptyList(),  // 生成的图（含时间标注）
    val ts: Long = System.currentTimeMillis()
)

/** 一个会话：按模式分类，标题可由多模态总结生成 */
class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val mode: GenMode,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
) {
    fun touch() { updatedAt = System.currentTimeMillis() }
}

/** 待发送素材（长按移送 / 资产库选择后带入） */
data class PendingImage(val path: String, val ts: Long)
