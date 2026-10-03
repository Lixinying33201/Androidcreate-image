package com.micu.studio

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 资产库：五分类文件管理（生成图/上传/人物资产/提示词/收藏），支持重命名、标签组、对话归档、时间标注 */
object AssetStore {

    private const val INDEX_FILE = "assets_index.json"
    private const val TAG_GROUPS_FILE = "asset_tag_groups.json"

    private fun root(context: Context): File {
        val dir = File(context.filesDir, "assets")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun dirFor(context: Context, cat: AssetCategory): File {
        val d = File(root(context), cat.dir)
        if (!d.exists()) d.mkdirs()
        return d
    }

    /** 生成图文件名带时间：gen_20261004_153012_12345.jpg */
    fun timeStampedName(prefix: String, ext: String = "jpg"): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "${prefix}_${fmt.format(Date())}_${System.currentTimeMillis() % 100000}.$ext"
    }

    /** 保存一张图片到指定分类，返回 AssetItem；自动写入索引 */
    fun saveImage(
        context: Context,
        bm: Bitmap,
        category: AssetCategory,
        name: String? = null,
        sessionId: String? = null,
        tags: List<String> = emptyList()
    ): AssetItem {
        val file = File(dirFor(context, category), name ?: timeStampedName("img"))
        val bos = java.io.ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.JPEG, 92, bos)
        file.writeBytes(bos.toByteArray())
        val item = AssetItem(
            path = file.absolutePath,
            category = category,
            name = file.name,
            tags = tags.toMutableList(),
            sessionId = sessionId,
            ts = System.currentTimeMillis()
        )
        saveIndex(context, loadIndex(context) + item)
        return item
    }

    /** 添加到指定分类（复制文件，用于长按 → 资产库 → 选择保存路径 / 收藏） */
    fun addToCategory(context: Context, srcPath: String, category: AssetCategory, sessionId: String? = null): AssetItem? {
        val src = File(srcPath)
        if (!src.exists()) return null
        val dstName = timeStampedName("img")
        val dst = File(dirFor(context, category), dstName)
        runCatching { src.copyTo(dst, overwrite = true) }.onFailure { return null }
        val item = AssetItem(
            path = dst.absolutePath,
            category = category,
            name = dstName,
            tags = mutableListOf(),
            sessionId = sessionId,
            ts = System.currentTimeMillis()
        )
        saveIndex(context, loadIndex(context) + item)
        return item
    }

    fun rename(context: Context, path: String, newName: String): Boolean {
        val f = File(path)
        if (!f.exists() || newName.isBlank()) return false
        var safe = newName.trim()
        if (!safe.endsWith(".jpg") && !safe.endsWith(".jpeg") && !safe.endsWith(".png")) safe += ".jpg"
        val dst = File(f.parentFile, safe)
        if (dst.exists()) return false
        if (f.renameTo(dst)) {
            val updated = loadIndex(context).map {
                if (it.path == path) it.copy(path = dst.absolutePath, name = dst.name) else it
            }
            saveIndex(context, updated)
            return true
        }
        return false
    }

    fun addTags(context: Context, path: String, tags: List<String>) {
        val items = loadIndex(context).map {
            if (it.path == path) {
                val t = it.tags.toMutableList()
                tags.forEach { tg -> if (tg.isNotBlank() && tg !in t) t.add(tg.trim()) }
                it.copy(tags = t)
            } else it
        }
        saveIndex(context, items)
    }

    fun removeTag(context: Context, path: String, tag: String) {
        val items = loadIndex(context).map {
            if (it.path == path) it.copy(tags = it.tags.filter { tg -> tg != tag }.toMutableList()) else it
        }
        saveIndex(context, items)
    }

    /** 读取资产，可按分类/标签/会话过滤，时间倒序 */
    fun load(
        context: Context,
        category: AssetCategory? = null,
        tag: String? = null,
        sessionId: String? = null
    ): List<AssetItem> {
        return loadIndex(context)
            .filter { category == null || it.category == category }
            .filter { tag == null || it.tags.contains(tag) }
            .filter { sessionId == null || it.sessionId == sessionId }
            .sortedByDescending { it.ts }
    }

    fun delete(context: Context, path: String) {
        val f = File(path)
        if (f.exists()) f.delete()
        saveIndex(context, loadIndex(context).filter { it.path != path })
    }

    fun deleteAll(context: Context, paths: List<String>) {
        paths.forEach { delete(context, it) }
    }

    // ---- 标签组（长按添加标签组） ----

    fun loadTagGroups(context: Context): List<String> {
        return try {
            val json = JSONArray(context.openFileInput(TAG_GROUPS_FILE).bufferedReader().readText())
            (0 until json.length()).map { json.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveTagGroups(context: Context, groups: List<String>) {
        val arr = JSONArray()
        groups.distinct().forEach { arr.put(it) }
        context.openFileOutput(TAG_GROUPS_FILE, Context.MODE_PRIVATE)
            .write(arr.toString().toByteArray())
    }

    fun addTagGroup(context: Context, group: String) {
        if (group.isBlank()) return
        saveTagGroups(context, (loadTagGroups(context) + group.trim()).distinct())
    }

    // ---- 索引持久化 ----

    private fun indexFile(context: Context) = File(root(context), INDEX_FILE)

    private fun loadIndex(context: Context): List<AssetItem> {
        return try {
            val json = JSONArray(context.openFileInput(INDEX_FILE).bufferedReader().readText())
            (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                val tagsArr = JSONArray(o.optString("tags", "[]"))
                val tagList = (0 until tagsArr.length()).map { tagsArr.getString(it) }
                AssetItem(
                    path = o.getString("path"),
                    category = runCatching { AssetCategory.valueOf(o.getString("category")) }
                        .getOrDefault(AssetCategory.GENERATED),
                    name = o.optString("name", File(o.getString("path")).name),
                    tags = tagList.toMutableList(),
                    sessionId = if (o.has("sessionId") && !o.isNull("sessionId")) o.getString("sessionId") else null,
                    ts = o.optLong("ts", System.currentTimeMillis())
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(context: Context, items: List<AssetItem>) {
        val arr = JSONArray()
        items.forEach { it ->
            val tagsArr = JSONArray()
            it.tags.forEach { tagsArr.put(it) }
            val o = JSONObject()
                .put("path", it.path)
                .put("category", it.category.name)
                .put("name", it.name)
                .put("tags", tagsArr)
                .put("sessionId", it.sessionId ?: JSONObject.NULL)
                .put("ts", it.ts)
            arr.put(o)
        }
        context.openFileOutput(INDEX_FILE, Context.MODE_PRIVATE)
            .write(arr.toString().toByteArray())
    }
}
