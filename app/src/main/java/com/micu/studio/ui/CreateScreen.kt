package com.micu.studio.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.micu.studio.APIClient
import com.micu.studio.AppConfig
import com.micu.studio.GenMode
import com.micu.studio.ImageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIZES = listOf(
    "1024x1024", "1280x720", "720x1280", "1024x1536", "1536x1024",
    "2048x2048", "2048x1152", "1152x2048", "3840x2160", "2160x3840"
)
private val QUALITIES = listOf("auto", "low", "medium", "high", "xhigh", "max")

/** 单模式参数：model=auto（多模态自动选）/multimodal（多模态主控）/具体生图模型（直出） */
private data class ModeParams(
    val model: String = "auto",
    val size: String = "1024x1024",
    val quality: String = "high",
    val n: Int = 1
)

private data class GenRecord(
    val id: Long,
    val image: Bitmap,
    val prompt: String,
    val modeLabel: String,
    val turn: Int,
    val mode: GenMode
)

@Composable
fun CreateScreen(config: AppConfig) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by rememberSaveable { mutableStateOf(GenMode.TEXT) }
    var prompt by rememberSaveable { mutableStateOf("") }
    var pickedImages by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var results by remember { mutableStateOf<List<GenRecord>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var stopFlag by remember { mutableStateOf(false) }
    var seq by remember { mutableStateOf(0L) }
    // 迭代模式状态
    var iterOn by remember { mutableStateOf(config.iterOn) }
    var iterGoal by remember { mutableStateOf("") }
    var lastReview by remember { mutableStateOf<com.micu.studio.ReviewResult?>(null) }
    var iterPaused by remember { mutableStateOf(false) }
    var turnCount by remember { mutableStateOf(0) }
    var planCache by remember { mutableStateOf<com.micu.studio.GenPlan?>(null) }
    // 迭代链：上一版提示词，用户调整意见按“追加”方式继续，避免替换导致画面信息丢失
    var lastPrompt by remember { mutableStateOf("") }
    // 各模式独立参数（模型/尺寸/质量/张数）
    var modeParams by remember { mutableStateOf(mutableMapOf<GenMode, ModeParams>()) }
    // 可用生图模型（动态拉取过滤废弃；失败回落白名单）
    var imgModels by remember { mutableStateOf(emptyList<String>()) }
    var imgModelLoading by remember { mutableStateOf(false) }

    fun ensureImageModels() {
        if (imgModels.isNotEmpty() || imgModelLoading) return
        imgModelLoading = true
        scope.launch {
            try {
                imgModels = withContext(Dispatchers.IO) { APIClient.availableImageModels(config) }
            } catch (_: Exception) {
            }
            imgModelLoading = false
        }
    }

    fun paramsFor(m: GenMode): ModeParams {
        val existed = modeParams[m]
        if (existed != null) return existed
        val fresh = ModeParams(
            model = when {
                config.executor == "direct" && config.directModel != "auto" -> config.directModel
                config.executor == "direct" -> config.imgModel // auto 跟随上方生图模型，保持直出语义
                else -> "auto"
            },
            size = config.size,
            quality = config.quality,
            n = config.count
        )
        modeParams = modeParams.toMutableMap().apply { put(m, fresh) }
        return fresh
    }

    fun updateParams(m: GenMode, transform: (ModeParams) -> ModeParams) {
        val cur = paramsFor(m)
        modeParams = modeParams.toMutableMap().apply { put(m, transform(cur)) }
    }

    fun modelLabel(model: String): String = when {
        model == "auto" -> "auto（多模态自动选）"
        model == "multimodal" -> "多模态主控"
        else -> model
    }

    fun directModelOrNull(mp: ModeParams): String? =
        if (mp.model in APIClient.SUPPORTED_MODELS) mp.model else null

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        if (uris.isNotEmpty()) {
            val bms = uris.mapNotNull { uri ->
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            pickedImages = bms
            // 参考图变更：多模态规划与迭代提示词链均失效，需重新规划
            planCache = null
            lastPrompt = ""
        }
    }

    fun pushResult(image: Bitmap, p: String, label: String, turn: Int, m: GenMode) {
        seq += 1
        results = listOf(GenRecord(seq, image, p, label, turn, m)) + results
    }

    suspend fun runOnce(p: String) {
        if (busy) return
        val mp = paramsFor(mode)
        busy = true
        status = "生成中…"
        try {
            val imgs = withContext(Dispatchers.IO) {
                if (mode == GenMode.TEXT)
                    APIClient.generate(config, p, model = directModelOrNull(mp), size = mp.size, quality = mp.quality, n = mp.n)
                else
                    APIClient.edit(config, p, pickedImages, model = directModelOrNull(mp), size = mp.size, quality = mp.quality, n = mp.n)
            }
            if (stopFlag) {
                status = "已停止"
                return
            }
            if (imgs.isNotEmpty()) {
                pushResult(imgs.first(), p, mode.label, 0, mode)
                status = "已生成"
            } else {
                status = "失败：未返回图片"
            }
        } catch (e: Exception) {
            status = if (stopFlag) "已停止" else "失败：${e.message}"
        }
        busy = false
    }

    suspend fun iterationRound() {
        if (busy) return
        busy = true
        try {
            val mp = paramsFor(mode)
            // 多模态规划：自动选模型/尺寸/质量/张数，并基于目标与全部参考图生成可执行英文提示词
            // 注意：规划结果先落在局部变量再回写 planCache，避免生成中状态被清空后读 planCache!! 崩溃
            var plan = planCache
            if (plan == null) {
                status = "多模态规划参数与模型…"
                plan = withContext(Dispatchers.IO) { APIClient.plan(config, iterGoal, pickedImages) }
                planCache = plan
            }
            // 当前提示词：首次用多模态规划产出的英文提示词；迭代中用户调整意见“追加”到上一版，保留画面信息
            val typed = prompt.trim()
            val current = when {
                typed.isNotEmpty() && lastPrompt.isNotEmpty() -> "$lastPrompt\n调整：$typed"
                typed.isNotEmpty() -> plan.prompt.ifBlank { iterGoal }
                lastPrompt.isNotEmpty() -> lastPrompt
                else -> plan.prompt.ifBlank { iterGoal }
            }
            lastPrompt = current

            // 参数来源：指定具体生图模型 → 全部用面板参数；auto/多模态主控 → 用户改过面板参数则用面板，否则用多模态规划
            val explicitModel = mp.model in APIClient.SUPPORTED_MODELS
            val userChanged = mp.size != config.size || mp.quality != config.quality || mp.n != config.count
            val effModel = if (explicitModel) mp.model else plan.model
            val useUserParams = explicitModel || userChanged
            val effSize = if (useUserParams) mp.size else plan.size
            val effQuality = if (useUserParams) mp.quality else plan.quality
            val effN = if (useUserParams) mp.n else plan.n

            turnCount += 1
            status = "第 $turnCount 版生成中…"
            val imgs = withContext(Dispatchers.IO) {
                if (pickedImages.isEmpty())
                    APIClient.generate(config, current, model = effModel, size = effSize, quality = effQuality, n = effN)
                else
                    APIClient.edit(config, current, pickedImages, model = effModel, size = effSize, quality = effQuality, n = effN)
            }
            val img = imgs.firstOrNull() ?: throw IllegalStateException("无返回图片")
            // 停止时丢弃本轮结果，不进入结果列表
            if (stopFlag) {
                status = "已停止"
                return
            }
            pushResult(img, current, "迭代", turnCount, mode)

            // 文生图且未开迭代开关：单次生成，不评审
            if (mode == GenMode.TEXT && !iterOn) {
                status = "已生成（未开评审）"
                return
            }

            if (stopFlag) {
                status = "已停止"
                return
            }
            status = "AI 评审中…"
            val review = withContext(Dispatchers.IO) {
                APIClient.review(config, iterGoal, current, img)
            }
            lastReview = review
            if (review.satisfied) {
                status = "评审通过：${review.comment}"
                return
            }
            if (review.nextPrompt.isBlank()) {
                status = "评审未给出新提示词，已停止。"
                return
            }
            // 未达标：暂停等待用户调整 prompt（预填 AI 修正建议，用户可改）后继续
            // 轮数上限仅为参考：maxTurns=0 不限；达到上限也是暂停而非硬停，出图流程不受中断
            iterPaused = true
            prompt = review.nextPrompt
            status = if (config.maxTurns > 0 && turnCount >= config.maxTurns) {
                "已达最大轮数 $turnCount，暂停（可修改提示词后继续）"
            } else {
                "第 $turnCount 版未达标：${review.comment}"
            }
        } catch (e: Exception) {
            status = if (stopFlag) "已停止" else "失败：${e.message}"
            iterPaused = false
        } finally {
            busy = false
        }
    }

    suspend fun runBatch(p: String) {
        if (busy) return
        val mp = paramsFor(mode)
        busy = true
        var ok = 0
        var fail = 0
        // 批量参数：指定具体生图模型 → 直出批量；auto/多模态主控 → 先由多模态规划模型/尺寸/质量与英文提示词
        val explicitModel = mp.model in APIClient.SUPPORTED_MODELS
        var effModel = if (explicitModel) mp.model else config.imgModel
        var effSize = mp.size
        var effQuality = mp.quality
        var effPrompt = p
        if (!explicitModel) {
            status = "多模态规划批量参数…"
            try {
                val plan = withContext(Dispatchers.IO) { APIClient.plan(config, p, pickedImages) }
                effModel = plan.model
                effSize = plan.size
                effQuality = plan.quality ?: mp.quality
                effPrompt = plan.prompt?.ifBlank { p } ?: p
            } catch (_: Exception) { /* 规划失败回落默认参数 */ }
        }
        pickedImages.forEachIndexed { i, img ->
            if (stopFlag) return@forEachIndexed
            status = "批量 ${i + 1} / ${pickedImages.size}…"
            try {
                // 批量逐张编辑，每张只出一版主图（n=1），避免数量爆炸
                val imgs = withContext(Dispatchers.IO) {
                    APIClient.edit(config, effPrompt, listOf(img), model = effModel, size = effSize, quality = effQuality, n = 1)
                }
                // 停止时丢弃当前这张，不进入结果列表
                if (stopFlag) {
                    status = "已停止"
                    return@forEachIndexed
                }
                if (imgs.isNotEmpty()) {
                    pushResult(imgs.first(), effPrompt, "批量编辑", i + 1, mode)
                    ok++
                } else fail++
            } catch (_: Exception) { fail++ }
        }
        busy = false
        status = if (stopFlag) "已停止：成功 $ok，失败 $fail" else "批量完成：成功 $ok，失败 $fail"
    }

    fun run() {
        if (busy) {
            stopFlag = true
            return
        }
        // 迭代暂停中：用户调整 prompt 后继续下一轮
        if (iterPaused) {
            iterPaused = false
            if (prompt.isBlank() && !lastReview?.nextPrompt.isNullOrBlank()) prompt = lastReview!!.nextPrompt
            scope.launch { iterationRound() }
            return
        }
        val p = prompt.trim()
        if (p.isEmpty()) return
        if (mode != GenMode.TEXT && pickedImages.isEmpty()) {
            status = "先选参考图"
            return
        }
        stopFlag = false
        val mp = paramsFor(mode)
        when {
            mode == GenMode.BATCH -> scope.launch { runBatch(p) }
            // 用户指定具体生图模型：直出（不经过多模态）；文生图开启迭代模式时仍走多模态评审迭代
            mp.model in APIClient.SUPPORTED_MODELS && !(mode == GenMode.TEXT && iterOn) ->
                scope.launch { runOnce(p) }
            // auto / 多模态主控：由多模态规划参数与提示词（文生图未开评审时单轮结束）
            else -> {
                iterGoal = p
                lastReview = null
                turnCount = 0
                planCache = null
                lastPrompt = ""
                scope.launch { iterationRound() }
            }
        }
        prompt = ""
    }

    Column(Modifier.fillMaxSize()) {
        // 模式
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GenMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    enabled = !busy,
                    onClick = {
                        mode = m
                        pickedImages = emptyList()
                        iterPaused = false
                        lastReview = null
                        planCache = null
                        lastPrompt = ""
                    },
                    label = { Text(m.label) }
                )
            }
        }

        // 参数面板：每个模式独立设定模型/尺寸/质量/张数
        val curParams = paramsFor(mode)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ParamDropdown(
                label = "模型",
                current = modelLabel(curParams.model),
                options = listOf(
                    "auto" to "auto（多模态自动选）",
                    "multimodal" to "多模态主控"
                ) + (if (imgModels.isEmpty()) APIClient.SUPPORTED_MODELS else imgModels).map { it to it },
                enabled = !busy,
                onExpand = { ensureImageModels() },
                onSelect = { v -> updateParams(mode) { p -> p.copy(model = v) } }
            )
            ParamDropdown(
                label = "尺寸",
                current = curParams.size,
                options = SIZES.map { it to it },
                enabled = !busy,
                onSelect = { v -> updateParams(mode) { it.copy(size = v) } }
            )
            ParamDropdown(
                label = "质量",
                current = curParams.quality,
                options = QUALITIES.map { it to it },
                enabled = !busy,
                onSelect = { v -> updateParams(mode) { it.copy(quality = v) } }
            )
            ParamDropdown(
                label = "张数",
                current = curParams.n.toString(),
                options = (1..4).map { it.toString() to it.toString() },
                enabled = !busy,
                onSelect = { v -> updateParams(mode) { it.copy(n = v.toInt()) } }
            )
        }

        // 能力边界说明：多模态模型不直接画图，只负责分析/规划/评审，出图由生图模型执行
        Text(
            "多模态不直接画图：auto / 多模态主控 模式下，多模态模型负责分析参考图、规划参数与评审出图，实际画图由生图模型执行；指定具体生图模型则直接用你的描述出图。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
        )

        // 结果列表
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (results.isEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Create, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("输入一句描述，开始创作", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (mode == GenMode.TEXT) "文生图：直接描述画面" else "选参考图，告诉我想怎么改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            items(results, key = { it.id }) { r ->
                ResultCard(r, busy) { act ->
                    when (act) {
                        "store" -> ImageStore.saveToLibrary(context, r.image, r.prompt)
                        "save" -> ImageStore.saveToAlbum(context, r.image)
                        // 生成中禁用再生成：防止新协程顶掉进行中任务
                        "redo" -> if (!busy) scope.launch {
                            mode = r.mode
                            lastPrompt = ""
                            planCache = null
                            if (r.mode == GenMode.TEXT) runOnce(r.prompt)
                            else status = "请先选择参考图，再点发送重新生成"
                        }
                    }
                }
            }
            if (busy || iterPaused) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (iterPaused && !busy) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "可修改下方提示词后点继续，或切换模式放弃",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        // 参考图（图生图/融合/批量/迭代模式均可上传）
        if (mode != GenMode.TEXT || iterOn) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pickedImages.take(6).forEach { bm ->
                    Image(
                        bitmap = bm.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }
                FilterChip(
                    selected = false,
                    enabled = !busy,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    label = {
                        Text(
                            when {
                                mode == GenMode.FUSION -> "选多图融合"
                                mode == GenMode.TEXT -> "选参考图（迭代）"
                                else -> "选图"
                            }
                        )
                    }
                )
            }
        }

        // 迭代开关
        if (mode == GenMode.TEXT) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "迭代模式（上传参考图 → AI 总结特征 → 对话调整 → 自动评审）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = iterOn, enabled = !busy, onCheckedChange = { iterOn = it; config.iterOn = it })
            }
        }

        // 输入区
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text(
                    when {
                        iterPaused -> "输入你的调整意见，或保留 AI 建议…"
                        mode == GenMode.TEXT -> "描述你想画的内容…"
                        else -> "描述想怎么改…"
                    }
                ) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            FilledIconButton(
                onClick = { run() },
                enabled = prompt.isNotBlank() || busy || iterPaused,
                modifier = Modifier.size(50.dp)
            ) {
                Icon(
                    when {
                        busy -> Icons.Filled.Close
                        iterPaused -> Icons.Filled.Refresh
                        else -> Icons.Filled.Send
                    },
                    contentDescription = when {
                        busy -> "停止"
                        iterPaused -> "继续迭代"
                        else -> "发送"
                    }
                )
            }
        }
    }
}

@Composable
private fun ResultCard(record: GenRecord, busy: Boolean, onAction: (String) -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Image(
                bitmap = record.image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    record.modeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
                if (record.turn > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "第 ${record.turn} 版",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                record.prompt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip("存图库", Icons.Filled.Check) { onAction("store") }
                ActionChip("存相册", Icons.Filled.Create) { onAction("save") }
                ActionChip("再生成", Icons.Filled.Refresh, enabled = !busy) { onAction("redo") }
            }
        }
    }
}

@Composable
private fun ParamDropdown(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onExpand: () -> Unit = {},
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = false,
            enabled = enabled,
            onClick = { expanded = true; onExpand() },
            label = { Text("$label：$current", maxLines = 1) }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // 限宽防止菜单横向扩散遮挡同行参数与操作按钮
            Box(Modifier.widthIn(max = 300.dp)) {
                options.forEach { (v, d) ->
                    DropdownMenuItem(text = { Text(d, maxLines = 1) }, onClick = {
                        onSelect(v)
                        expanded = false
                    })
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.45f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
    }
}
