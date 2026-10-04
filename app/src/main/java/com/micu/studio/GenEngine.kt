package com.micu.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 生图执行引擎：对话式全模式统一入口。
 * TEXT 直出；BATCH 逐张编辑；EDIT/FUSION/ITER 走 多模态规划 → 生成/编辑 → 评审续生。
 * ITER 模式带迭代窗口（窗口内用户可追加要求/参考图），自动评审续生可由设置开关。
 */
object GenEngine {

    suspend fun run(
        context: Context,
        app: AppState,
        session: ChatSession,
        goal: String,
        images: List<String>,
        mode: GenMode,
        genEnabled: Boolean = true,
        onTask: (TaskState) -> Unit
    ) {
        val cfg = app.config
        var turn = 0
        var currentGoal = goal
        var currentImages = images
        var lastPlan: GenPlan? = null
        onTask(TaskState.Planning())
        try {
            when (mode) {
                GenMode.CHAT -> {
                    // 对话模式：多模态对话（带历史与参考图），对一次话最多生成 1 张，不迭代
                    onTask(TaskState.Planning())
                    val history = session.messages.filter { it.role == "user" || it.role == "assistant" }
                    val reply = withContext(Dispatchers.IO) { APIClient.chat(cfg, history, emptyList(), genEnabled) }
                    var imgs: List<Bitmap> = emptyList()
                    if (reply.genPrompt != null) {
                        onTask(TaskState.Generating())
                        imgs = withContext(Dispatchers.IO) {
                            APIClient.generate(cfg, reply.genPrompt, model = null, size = cfg.size, quality = cfg.quality, n = 1)
                        }
                    }
                    val gs = imgs.map { bm ->
                        val item = AssetStore.saveImage(context, bm, AssetCategory.GENERATED, sessionId = session.id)
                        GenImage(item.path, reply.genPrompt ?: "", cfg.imgModel, cfg.size, cfg.quality, System.currentTimeMillis())
                    }
                    session.messages.add(
                        ChatMessage(System.currentTimeMillis(), "assistant", reply.text.ifBlank { "好的" }, results = gs)
                    )
                    session.touch()
                    onTask(TaskState.Done(true, if (gs.isNotEmpty()) "已生成 1 张" else "已回复"))
                    LogStore.ok("对话模式完成: gen=${gs.isNotEmpty()} 回复=${reply.text.take(40)}")
                }

                GenMode.TEXT -> {
                    onTask(TaskState.Generating())
                    val imgs = withContext(Dispatchers.IO) {
                        APIClient.generate(cfg, currentGoal, model = null, size = cfg.size, quality = cfg.quality, n = 1)
                    }
                    appendResult(session, context, app, currentGoal, null, cfg.quality, cfg.size, 1, imgs, mode)
                    onTask(TaskState.Done(true, "已生成 ${imgs.size} 张"))
                }

                GenMode.BATCH -> {
                    var ok = 0
                    val total = currentImages.size.coerceAtLeast(1)
                    if (currentImages.isEmpty()) {
                        // 无参考图时退化为批量文生图 n 张
                        onTask(TaskState.Generating())
                        val imgs = withContext(Dispatchers.IO) {
                            APIClient.generate(cfg, currentGoal, model = null, size = cfg.size, quality = cfg.quality, n = cfg.batchCount.coerceIn(1, 4))
                        }
                        appendResult(session, context, app, currentGoal, null, cfg.quality, cfg.size, imgs.size, imgs, mode)
                        onTask(TaskState.Done(true, "批量完成 ${imgs.size} 张"))
                        return
                    }
                    currentImages.forEachIndexed { i, imgPath ->
                        onTask(TaskState.Generating(i + 1))
                        val bm = runCatching { BitmapFactory.decodeFile(imgPath) }.getOrNull() ?: return@forEachIndexed
                        val plan = lastPlan ?: runCatching {
                            withContext(Dispatchers.IO) { APIClient.plan(cfg, currentGoal, listOf(bm)) }
                        }.getOrNull()
                        if (plan != null) lastPlan = plan
                        val p = plan?.prompt?.ifBlank { currentGoal } ?: currentGoal
                        val imgs = withContext(Dispatchers.IO) {
                            APIClient.edit(cfg, p, listOf(bm), model = plan?.model, size = plan?.size ?: cfg.size, quality = plan?.quality ?: cfg.quality, n = 1)
                        }
                        if (imgs.isNotEmpty()) {
                            appendResult(session, context, app, p, plan?.model, plan?.quality ?: cfg.quality, plan?.size ?: cfg.size, 1, imgs, mode)
                            ok++
                        }
                    }
                    onTask(TaskState.Done(ok > 0, "批量完成：成功 $ok / $total"))
                }

                else -> {
                    // EDIT / FUSION / ITER
                    val bms = currentImages.mapNotNull { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                    while (true) {
                        turn++
                        onTask(TaskState.Planning(turn))
                        var plan = lastPlan
                        if (plan == null) {
                            plan = withContext(Dispatchers.IO) { APIClient.plan(cfg, currentGoal, bms) }
                            lastPlan = plan
                        }
                        // 提示词：首次取多模态规划结果；迭代中用户追加内容时拼到上一版
                        val effPrompt = when {
                            lastPromptOf(session.id).isEmpty() -> plan.prompt.ifBlank { currentGoal }
                            currentGoal != goal -> "${lastPromptOf(session.id)}\n调整：$currentGoal"
                            else -> lastPromptOf(session.id)
                        }
                        rememberPrompt(session.id, effPrompt)

                        onTask(TaskState.Generating(turn))
                        val imgs = withContext(Dispatchers.IO) {
                            if (bms.isEmpty())
                                APIClient.generate(cfg, effPrompt, model = plan.model, size = plan.size, quality = plan.quality, n = plan.n)
                            else
                                APIClient.edit(cfg, effPrompt, bms, model = plan.model, size = plan.size, quality = plan.quality, n = plan.n)
                        }
                        val img = imgs.firstOrNull() ?: throw IllegalStateException("未返回图片")
                        appendResult(session, context, app, effPrompt, plan.model, plan.quality, plan.size, imgs.size, imgs, mode)
                        onTask(TaskState.Done(true, "第 $turn 版已生成（${imgs.size} 张）"))

                        if (mode != GenMode.ITER || !cfg.iterAutoReview) return

                        onTask(TaskState.Reviewing(turn))
                        val review = withContext(Dispatchers.IO) { APIClient.review(cfg, currentGoal, effPrompt, img) }
                        if (review.satisfied) {
                            onTask(TaskState.Done(true, "评审通过：${review.comment}"))
                            return
                        }
                        if (review.nextPrompt.isBlank()) {
                            onTask(TaskState.Done(true, "评审未给出新提示词，已停止"))
                            return
                        }
                        // 迭代续生：不再等待固定窗口（原先默认 60 秒干等），评审不过立即用优化提示词续生；
                        // 用户在此期间发消息追加要求，下一轮循环开头会自动合并（currentGoal != goal 时拼"调整"）
                        val lastMsg = session.messages.lastOrNull()
                        if (lastMsg != null && lastMsg.role == "user") {
                            currentGoal = lastMsg.text
                        } else {
                            currentGoal = review.nextPrompt
                        }
                        lastPlan = null
                        clearPrompt(session.id)
                        onTask(TaskState.Done(false, "第 $turn 版未达标：${review.comment}，自动用优化提示词续生，可随时追加要求"))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogStore.err("生图失败: ${e.message}")
            onTask(TaskState.Done(false, "失败：${e.message}"))
        }
    }

    /** 把生成结果写入会话消息并归档到资产库（生成图分类） */
    private fun appendResult(
        session: ChatSession,
        context: Context,
        app: AppState,
        prompt: String,
        model: String?,
        quality: String?,
        size: String,
        n: Int,
        imgs: List<Bitmap>,
        mode: GenMode
    ) {
        val gs = imgs.map { bm ->
            val item = AssetStore.saveImage(context, bm, AssetCategory.GENERATED, sessionId = session.id)
            GenImage(item.path, prompt, model ?: "", size, quality, System.currentTimeMillis())
        }
        session.messages.add(
            ChatMessage(
                System.currentTimeMillis(),
                "assistant",
                "已生成 ${gs.size} 张（${mode.label}）",
                results = gs
            )
        )
        session.touch()
        LogStore.ok("${mode.label} 完成: ${gs.size} 张已入资产库")
    }
}

// 会话内提示词状态（按会话隔离，避免多会话串味）
private val sessionPrompts = mutableMapOf<String, String>()

private fun lastPromptOf(sessionId: String): String = sessionPrompts[sessionId] ?: ""
private fun rememberPrompt(sessionId: String, p: String) { sessionPrompts[sessionId] = p }
private fun clearPrompt(sessionId: String) { sessionPrompts.remove(sessionId) }
