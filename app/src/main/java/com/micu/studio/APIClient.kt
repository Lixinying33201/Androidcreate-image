package com.micu.studio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class APIException(message: String) : Exception(message)

/** 多模态模型规划出的生图执行方案：模型/尺寸/质量/张数/提示词 */
data class GenPlan(
    val model: String,
    val size: String,
    val quality: String?,
    val n: Int,
    val prompt: String
)

object APIClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .build()

    // ---- 模型能力规范（与 micu-image-mcp 一致）----
    val SUPPORTED_MODELS = listOf(
        "gpt-image-2.5-flare",
        "gpt-image-2.5-sunburst",
        "gpt-image-2",
        "gpt-image-2-openai"
    )
    private val DEFAULT_TEXT_MODEL = "gpt-image-2.5-flare"
    private val DEFAULT_EDIT_MODEL = "gpt-image-2.5-sunburst"
    private val QUALITY_MAX_ALL = listOf("auto", "low", "medium", "high", "xhigh", "max")
    private val QUALITY_MAX_LEGACY = listOf("auto", "low", "medium", "high")

    private fun is25Model(m: String): Boolean = m.contains("2.5")

    /** 解析 "WxH" 尺寸；非法返回 null */
    private fun parseSize(size: String): Pair<Int, Int>? {
        val idx = size.indexOf('x')
        if (idx <= 0) return null
        return try {
            size.substring(0, idx).trim().toInt() to size.substring(idx + 1).trim().toInt()
        } catch (_: Exception) {
            null
        }
    }

    /** 是否为 2K/4K（任一边 >= 2048），此类请求规范强制 n=1 */
    fun isHighResSize(size: String): Boolean {
        val (w, h) = parseSize(size) ?: return false
        return w >= 2048 || h >= 2048
    }

    /** 按模式解析合法模型：未知模型抛错；gpt-image-2 遇 2K/4K 自动切换 gpt-image-2-openai */
    private fun resolveModel(cfg: AppConfig, isEdit: Boolean, size: String, m: String? = null): String {
        var model = m?.trim()?.ifEmpty { null }
            ?: cfg.imgModel.trim().ifEmpty { if (isEdit) DEFAULT_EDIT_MODEL else DEFAULT_TEXT_MODEL }
        if (model !in SUPPORTED_MODELS) {
            val m = "不支持的模型：$model（可选：${SUPPORTED_MODELS.joinToString(" / ")}）"
            LogStore.err(m)
            throw APIException(m)
        }
        if (model == "gpt-image-2" && isHighResSize(size)) model = "gpt-image-2-openai"
        return model
    }

    /** 质量钳制：2.5 系列服务端仅支持 low；非 2.5 模型最高只支持 high */
    private fun resolveQuality(model: String, quality: String): String? {
        if (quality !in QUALITY_MAX_ALL) return null
        if (is25Model(model)) return "low"
        if (quality in listOf("xhigh", "max")) return "high"
        return quality
    }

    /** 张数钳制：2K/4K 强制 1，其余限 1..4 */
    private fun resolveCount(size: String, n: Int): Int =
        if (isHighResSize(size)) 1 else n.coerceIn(1, 4)

    /** 尺寸合法性校验（与规范一致：最长边<=3840、比例<=3:1、像素 655360..8294400、旧模型需 16 倍数） */
    private fun validateSize(size: String, model: String) {
        val (w, h) = parseSize(size) ?: throw APIException("尺寸格式非法：$size（应为 宽x高，如 1024x1024）")
        if (w <= 0 || h <= 0) throw APIException("尺寸非法：$size")
        if (w > 3840 || h > 3840) throw APIException("尺寸超出上限：最长边不能超过 3840")
        if (maxOf(w, h) > minOf(w, h) * 3) throw APIException("尺寸比例超限：长宽比不能超过 3:1")
        val px = w.toLong() * h
        if (px < 655360 || px > 8_294_400) throw APIException("尺寸像素超出范围：需在 655,360 ~ 8,294,400 之间")
        if (!is25Model(model) && (w % 16 != 0 || h % 16 != 0)) throw APIException("模型 $model 要求宽高为 16 的倍数")
    }

    private fun trimBase(s: String): String = s.trim().trimEnd('/')

    /** 等比缩放到最长边不超过 max（控制多模态请求体积） */
    private fun scaleToMax(bm: Bitmap, max: Int): Bitmap {
        val w = bm.width
        val h = bm.height
        val longEdge = maxOf(w, h)
        if (longEdge <= max) return bm
        val scale = max.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            bm,
            (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun errorMessage(text: String): String {
        return try {
            val j = JSONObject(text)
            val e = j.getJSONObject("error")
            e.getString("message")
        } catch (_: Exception) {
            text.take(200)
        }
    }

    private fun parseImages(json: String): List<Bitmap> {
        val arr = JSONObject(json).getJSONArray("data")
        val out = ArrayList<Bitmap>()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.has("b64_json")) {
                val bytes = Base64.decode(item.getString("b64_json"), Base64.DEFAULT)
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bm != null) out.add(bm)
            }
        }
        return out
    }

    /** 文生图。model/size/quality/n 缺省时取设置值；可由多模态规划显式指定 */
    fun generate(
        cfg: AppConfig, prompt: String,
        model: String? = null, size: String? = null, quality: String? = null, n: Int? = null
    ): List<Bitmap> {
        val effSize = size?.trim()?.ifEmpty { null } ?: cfg.size
        val effModel = resolveModel(cfg, isEdit = false, effSize, model)
        validateSize(effSize, effModel)
        val effN = resolveCount(effSize, n ?: cfg.count)
        val effQuality = quality?.trim()?.ifEmpty { null } ?: cfg.quality
        val finalQuality = resolveQuality(effModel, effQuality)

        LogStore.info("文生图: 模型=$effModel 尺寸=$effSize n=$effN 提示词=${prompt.take(60)}")
        val url = "${trimBase(cfg.imgBase)}/v1/images/generations"
        val body = JSONObject()
            .put("model", effModel)
            .put("prompt", prompt)
            .put("size", effSize)
            .put("n", effN)
            .put("response_format", "b64_json")
        if (finalQuality != null) body.put("quality", finalQuality)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.imgKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                // 服务商可能限制某模型仅支持 low 质量（如部分 2.5 服务端），自动降级 low 重试一次
                val wantLow = (m.contains("low") && (m.contains("only") || m.contains("support"))) || m.contains("quality")
                if (wantLow && finalQuality != null && finalQuality != "low") {
                    LogStore.warn("服务端要求 low 质量，自动降级重试: $m")
                    return generate(cfg, prompt, effModel, effSize, "low", effN)
                }
                LogStore.err("文生图失败(${effModel}): $m")
                throw APIException(m)
            }
            val imgs = parseImages(text)
            LogStore.ok("文生图完成: ${imgs.size}张")
            return imgs
        }
    }

    /** 图生图 / 多图融合 / 批量编辑。model/size/quality/n 缺省时取设置值；可由多模态规划显式指定 */
    fun edit(
        cfg: AppConfig, prompt: String, images: List<Bitmap>,
        model: String? = null, size: String? = null, quality: String? = null, n: Int? = null
    ): List<Bitmap> {
        val effSize = size?.trim()?.ifEmpty { null } ?: cfg.size
        val effModel = resolveModel(cfg, isEdit = true, effSize, model)
        validateSize(effSize, effModel)
        val effN = resolveCount(effSize, n ?: cfg.count)
        val effQuality = quality?.trim()?.ifEmpty { null } ?: cfg.quality
        val finalQuality = resolveQuality(effModel, effQuality)

        LogStore.info("图生图/编辑: 模型=$effModel 尺寸=$effSize n=$effN 参考图=${images.size}张 提示词=${prompt.take(60)}")
        val url = "${trimBase(cfg.imgBase)}/v1/images/edits"
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        builder.addFormDataPart("prompt", prompt)
        builder.addFormDataPart("model", effModel)
        builder.addFormDataPart("size", effSize)
        builder.addFormDataPart("n", effN.toString())
        if (finalQuality != null) builder.addFormDataPart("quality", finalQuality)
        images.forEachIndexed { i, bm ->
            val bos = ByteArrayOutputStream()
            bm.compress(Bitmap.CompressFormat.JPEG, 92, bos)
            builder.addFormDataPart(
                "image", "img$i.jpg",
                bos.toByteArray().toRequestBody("image/jpeg".toMediaType())
            )
        }

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.imgKey}")
            .post(builder.build())
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                // 服务商可能限制某模型仅支持 low 质量，自动降级 low 重试一次
                val wantLow = (m.contains("low") && (m.contains("only") || m.contains("support"))) || m.contains("quality")
                if (wantLow && finalQuality != null && finalQuality != "low") {
                    LogStore.warn("服务端要求 low 质量，自动降级重试: $m")
                    return edit(cfg, prompt, images, effModel, effSize, "low", effN)
                }
                LogStore.err("图生图/编辑失败(${effModel}): $m")
                throw APIException(m)
            }
            val imgs = parseImages(text)
            LogStore.ok("图生图/编辑完成: ${imgs.size}张")
            return imgs
        }
    }

    /** 生图模型列表缓存：拉取成功且白名单内有可用项才缓存，避免每次进设置页/创作页重复请求 */
    @Volatile
    private var imgModelCache: List<String>? = null

    /** 当前可用的生图模型列表：自动拉取 /v1/models 过滤废弃模型，再与白名单取交集；
     *  拉取失败或列表为空时回落白名单（保证 UI 不空），生图模型也可能被弃用，故不写死 */
    fun availableImageModels(cfg: AppConfig): List<String> {
        imgModelCache?.let { cached ->
            LogStore.debug("可用生图模型走缓存: ${cached.size}个")
            return cached
        }
        val usable = try {
            val all = listImageModels(cfg)
            SUPPORTED_MODELS.filter { it in all }
        } catch (e: Exception) {
            LogStore.warn("生图模型列表拉取失败: ${e.message}")
            emptyList()
        }
        val result = if (usable.isEmpty()) {
            LogStore.warn("可用生图模型为空，回落白名单 ${SUPPORTED_MODELS.size} 个")
            SUPPORTED_MODELS
        } else {
            LogStore.ok("获取可用生图模型: ${usable.joinToString("/")}")
            usable
        }
        imgModelCache = result
        return result
    }

    /** 拉取指定服务端点的可用模型列表（OpenAI 兼容 /v1/models，过滤废弃模型） */
    private fun listModelsAt(base: String, key: String): List<String> {
        val url = "${trimBase(base)}/v1/models"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                LogStore.warn("模型列表拉取失败: $m")
                throw APIException(m)
            }
            val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                // 过滤废弃/停用模型：deprecated=true 或 role=deprecated，避免选中后调用失败
                if (obj.has("deprecated") && obj.optBoolean("deprecated", false)) continue
                if (obj.optString("role", "") == "deprecated") continue
                out.add(id)
            }
            return out
        }
    }

    /** 自动获取对话 API 的可用模型列表（多模态控制模型，过滤废弃模型） */
    fun listModels(cfg: AppConfig): List<String> = listModelsAt(cfg.chatBase, cfg.chatKey)

    /** 自动获取生图 API 的可用生图模型列表（生图模型也可能被弃用，须从生图端点动态拉取） */
    fun listImageModels(cfg: AppConfig): List<String> = listModelsAt(cfg.imgBase, cfg.imgKey)

    /** 对话模式：多模态对话。携带会话历史与可选参考图，返回 AI 文本回复；
     *  用户请求生图时（genEnabled=true），回复文本前带 [GEN]{"prompt":"..."} 标记 */
    fun chat(cfg: AppConfig, history: List<ChatMessage>, images: List<Bitmap>, genEnabled: Boolean): ChatReply {
        LogStore.info("对话模式: 历史=${history.size}条 参考图=${images.size}张 gen=${genEnabled}")
        val url = "${trimBase(cfg.chatBase)}/v1/chat/completions"
        val sys = "你是用户的多模态对话助手。规则：\n" +
            "1. 用户只是聊天（闲聊/答疑/讨论）时，直接自然回复，不要加任何标记；\n" +
            "2. 用户请求生成/绘制/创作图片时，回复必须以 [GEN]{\"prompt\":\"可直接执行的英文生图提示词\"} 开头，紧接一段对用户的简短中文说明；\n" +
            "3. 有参考图时，先简短理解参考图内容再回复，生图时结合参考图写提示词；\n" +
            "4. 每次回复最多输出一个 [GEN] 标记；prompt 须细节丰富，遵循官方规范：\n${cfg.assetPrompt}"

        val arr = org.json.JSONArray()
        arr.put(JSONObject().put("role", "system").put("content", sys))
        history.takeLast(10).forEach { m ->
            val content = org.json.JSONArray().put(
                JSONObject().put("type", "text").put("text", m.text.ifBlank { "（图片消息）" })
            )
            if (m.role == "user") {
                m.uploads.take(3).forEach { p ->
                    runCatching {
                        val bm = BitmapFactory.decodeFile(p)
                        if (bm != null) {
                            val scaled = scaleToMax(bm, 1024)
                            val bos = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                            content.put(JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                        }
                    }
                }
            }
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        if (images.isNotEmpty()) {
            val content = org.json.JSONArray().put(
                JSONObject().put("type", "text").put("text", "[本轮上传 ${images.size} 张参考图]")
            )
            images.take(4).forEach { bm ->
                val scaled = scaleToMax(bm, 1024)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                content.put(JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
            arr.put(JSONObject().put("role", "user").put("content", content))
        }

        val body = JSONObject()
            .put("model", cfg.chatModel)
            .put("temperature", cfg.chatTemp)
            .put("max_tokens", 1200)
            .put("messages", arr)

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.chatKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                LogStore.err("对话失败: $m")
                throw APIException(m)
            }
            val raw = parseText(text)
            var genPrompt: String? = null
            var replyText = raw
            if (genEnabled && raw.contains("[GEN]")) {
                val idx = raw.indexOf("[GEN]")
                val rest = raw.substring(idx + 5)
                val start = rest.indexOf('{')
                val end = rest.lastIndexOf('}')
                if (start >= 0 && end > start) {
                    runCatching {
                        val j = JSONObject(rest.substring(start, end + 1))
                        genPrompt = j.optString("prompt", "").trim().ifEmpty { null }
                    }
                }
                replyText = (raw.substring(0, idx) + if (start >= 0) rest.substring(end + 1) else rest).trim()
                    .ifEmpty { "已按你的要求生成图片" }
            }
            LogStore.ok("对话完成: gen=${genPrompt != null} 回复=${replyText.take(40)}")
            return ChatReply(replyText, genPrompt)
        }
    }

    /** 多模态规划：自动选择生图模型/尺寸/质量/张数并产出英文提示词（生图模型只是执行工具） */
    fun plan(cfg: AppConfig, goal: String, images: List<Bitmap> = emptyList()): GenPlan {
        LogStore.info("多模态规划: 目标=${goal.take(60)} 参考图=${images.size}张")
        val url = "${trimBase(cfg.chatBase)}/v1/chat/completions"
        val hasImage = images.isNotEmpty()
        // 生图模型可用列表动态获取（过滤废弃），避免规划到已弃用模型
        val availModels = availableImageModels(cfg)
        val sys = cfg.chatSystem + "\n\n当前任务：你是创作规划器，为生图模型制定执行方案。" +
                "根据用户目标" + (if (hasImage) "与 ${images.size} 张参考图（须逐张理解内容与作用：多图融合时明确主体来自哪张、风格/背景/配色来自哪张）" else "") + "输出唯一 JSON，格式：" +
                "{\"model\":\"...\",\"size\":\"...\",\"quality\":\"...\",\"n\":1,\"prompt\":\"英文详细提示词\"}。" +
                "约束：model 只能从 [${availModels.joinToString(", ")}] 选择，" +
                (if (hasImage) "有参考图优先选 gpt-image-2.5-sunburst（编辑/融合），" else "无参考图默认 gpt-image-2.5-flare（文生图），") +
                "size 从 [1024x1024, 1280x720, 720x1280, 1024x1536, 1536x1024, 2048x2048, 2048x1152, 1152x2048, 3840x2160, 2160x3840] 按画面需要选择；" +
                "quality 从 auto/low/medium/high/xhigh/max 中选择（旧模型最高 high）；n 为 1~4 的整数，2K/4K 高清必须为 1；" +
                "prompt 为细节丰富、可直接交给生图模型执行的英文提示词，书写必须遵循以下官方规范：\n${cfg.assetPrompt}\n" +
                "只输出 JSON，不要任何额外文字。"

        val content = org.json.JSONArray()
        content.put(JSONObject().put("type", "text").put("text", "用户目标：$goal"))
        if (hasImage) {
            // 参考图全量交给多模态（最多 8 张，缩到最长边 1024 控制体积），保证多图融合/编辑意图完整可见
            images.take(8).forEach { bm ->
                val scaled = scaleToMax(bm, 1024)
                val bos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                content.put(JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
        }

        val body = JSONObject()
            .put("model", cfg.chatModel)
            .put("temperature", cfg.chatTemp)
            .put("max_tokens", 1600)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", content)))

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.chatKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                LogStore.err("多模态规划失败: $m")
                throw APIException(m)
            }
            val plan = parsePlan(cfg, parseText(text), goal, hasImage)
            LogStore.ok("规划完成: ${plan.model} ${plan.size} ${plan.quality ?: "-"} n=${plan.n}")
            return plan
        }
    }

    /** 解析多模态规划结果，任何字段非法都回落默认，保证调用生图接口前参数合法 */
    private fun parsePlan(cfg: AppConfig, raw: String, goal: String, hasImage: Boolean): GenPlan {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val nl = s.indexOf('\n')
            if (nl >= 0) s = s.substring(nl + 1)
            val end = s.lastIndexOf("```")
            if (end >= 0) s = s.substring(0, end)
            s = s.trim()
        }
        var model = ""
        var size = ""
        var quality: String? = null
        var n = 1
        var prompt = ""
        try {
            val start = s.indexOf('{')
            val end = s.lastIndexOf('}')
            if (start >= 0 && end > start) {
                val r = JSONObject(s.substring(start, end + 1))
                model = r.optString("model", "")
                size = r.optString("size", "")
                quality = r.optString("quality", "").ifEmpty { null }
                n = r.optInt("n", 1)
                prompt = r.optString("prompt", "")
            }
        } catch (_: Exception) {}

        val defaultModel = if (hasImage) DEFAULT_EDIT_MODEL else DEFAULT_TEXT_MODEL
        val finalModel = if (model in SUPPORTED_MODELS) model else defaultModel
        var finalSize = size.trim().ifEmpty { cfg.size }
        runCatching { validateSize(finalSize, finalModel) }
            .onFailure { finalSize = cfg.size }
        if (parseSize(finalSize) == null) finalSize = cfg.size
        val finalN = resolveCount(finalSize, n)
        val finalQuality: String? = resolveQuality(finalModel, quality ?: cfg.quality)
        val finalPrompt = prompt.trim().ifEmpty { goal }
        return GenPlan(finalModel, finalSize, finalQuality, finalN, finalPrompt)
    }

    private fun parseText(json: String): String {
        return try {
            val j = JSONObject(json)
            val choices = j.getJSONArray("choices")
            val msg = choices.getJSONObject(0).getJSONObject("message")
            val content = msg.opt("content")
            when (content) {
                is String -> content.trim()
                is org.json.JSONArray -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val it = content.getJSONObject(i)
                        if (it.optString("type") == "text") sb.append(it.optString("text"))
                    }
                    sb.toString().trim()
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    fun review(cfg: AppConfig, goal: String, prompt: String, image: Bitmap): ReviewResult {
        LogStore.info("AI 评审: 目标=${goal.take(40)} 提示词=${prompt.take(40)}")
        val url = "${trimBase(cfg.chatBase)}/v1/chat/completions"
        // 注入用户配置的主控系统提示词，多模态模型负责决策，生图模型只是执行工具
        val sys = cfg.chatSystem + "\n\n当前任务：评审这张由生图模型生成的图片是否达成用户目标，按系统规则输出 JSON。\n生成提示词书写规范参考：\n" + cfg.assetPrompt

        val bos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)

        val content = org.json.JSONArray()
        val textPart = JSONObject()
            .put("type", "text")
            .put("text", "用户目标：$goal" + if (prompt.isNotEmpty()) "\n上一轮提示词：$prompt" else "")
        content.put(textPart)
        val imgPart = JSONObject()
            .put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
        content.put(imgPart)

        val body = JSONObject()
            .put("model", cfg.chatModel)
            .put("temperature", cfg.chatTemp)
            .put("max_tokens", 1200)
            .put("messages", org.json.JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", content)))

        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${cfg.chatKey}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val m = errorMessage(text)
                LogStore.err("AI 评审失败: $m")
                throw APIException(m)
            }
            val r = parseReview(text)
            LogStore.ok("评审完成: 通过=${r.satisfied} ${r.comment.take(40)}")
            return r
        }
    }

    private fun parseReview(json: String): ReviewResult {
        return try {
            val j = JSONObject(json)
            val choices = j.getJSONArray("choices")
            val msg = choices.getJSONObject(0).getJSONObject("message")
            val content = msg.opt("content")
            val text: String = when (content) {
                is String -> content
                is org.json.JSONArray -> {
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val it = content.getJSONObject(i)
                        if (it.optString("type") == "text") sb.append(it.optString("text"))
                    }
                    sb.toString()
                }
                else -> ""
            }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) {
                ReviewResult(false, "评审返回格式异常，已停止迭代", "")
            } else {
                val r = JSONObject(text.substring(start, end + 1))
                ReviewResult(
                    satisfied = r.optBoolean("satisfied", false),
                    comment = r.optString("comment", ""),
                    nextPrompt = r.optString("next_prompt", "")
                )
            }
        } catch (_: Exception) {
            ReviewResult(false, "评审解析失败，已停止迭代", "")
        }
    }
}
