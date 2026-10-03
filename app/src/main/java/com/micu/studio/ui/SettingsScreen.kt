package com.micu.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.micu.studio.APIClient
import com.micu.studio.AppConfig
import com.micu.studio.DEFAULT_CHAT_SYSTEM
import com.micu.studio.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SIZES = listOf(
    "1024x1024", "1280x720", "720x1280", "1024x1536", "1536x1024",
    "2048x2048", "2048x1152", "1152x2048", "3840x2160", "2160x3840"
)
// 质量能力按模型区分：2.5 系列支持 auto..max，旧模型最高 high
private val QUALITIES_25 = listOf("auto", "low", "medium", "high", "xhigh", "max")
private val QUALITIES_LEGACY = listOf("auto", "low", "medium", "high")

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(config: AppConfig) {
    val scope = rememberCoroutineScope()
    var testStatus by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var imgModels by remember { mutableStateOf(emptyList<String>()) }
    var imgModelLoading by remember { mutableStateOf(false) }
    var imgModelStatus by remember { mutableStateOf("") }
    var sizeExpanded by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var chatModelExpanded by remember { mutableStateOf(false) }
    var chatModels by remember { mutableStateOf(emptyList<String>()) }
    var chatModelLoading by remember { mutableStateOf(false) }
    var chatModelStatus by remember { mutableStateOf("") }
    var executorExpanded by remember { mutableStateOf(false) }
    var directModelExpanded by remember { mutableStateOf(false) }

    // 本地 Compose state，避免直接绑定 SharedPreferences 导致 UI 不重组
    var imgBase by remember { mutableStateOf(config.imgBase) }
    var imgKey by remember { mutableStateOf(config.imgKey) }
    var imgModel by remember { mutableStateOf(config.imgModel) }
    var quality by remember { mutableStateOf(config.quality) }
    var size by remember { mutableStateOf(config.size) }
    var count by remember { mutableStateOf(config.count) }
    var chatBase by remember { mutableStateOf(config.chatBase) }
    var chatKey by remember { mutableStateOf(config.chatKey) }
    var chatModel by remember { mutableStateOf(config.chatModel) }
    var chatSystem by remember { mutableStateOf(config.chatSystem) }
    var executor by remember { mutableStateOf(config.executor) }
    var directModel by remember { mutableStateOf(config.directModel) }
    var maxTurns by remember { mutableStateOf(config.maxTurns) }
    var iterOn by remember { mutableStateOf(config.iterOn) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("生图服务（米醋 Micu，OpenAI 兼容）", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = imgBase, onValueChange = { imgBase = it; config.imgBase = it },
            label = { Text("API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = imgKey, onValueChange = { imgKey = it; config.imgKey = it },
            label = { Text("API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        // 模型下拉（展开时自动拉取可用生图模型并过滤废弃，失败回落白名单）
        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { expanded ->
            modelExpanded = expanded
            if (expanded && imgModels.isEmpty() && !imgModelLoading) {
                scope.launch {
                    imgModelLoading = true
                    imgModelStatus = "正在获取可用生图模型…"
                    try {
                        imgModels = withContext(Dispatchers.IO) { APIClient.availableImageModels(config) }
                        if (imgModels.isEmpty()) {
                            imgModelStatus = "未获取到可用生图模型"
                        } else {
                            // 当前已保存的生图模型被废弃时自动切换，避免选中后调用失败
                            if (imgModel !in imgModels) {
                                val old = imgModel
                                imgModel = imgModels.first()
                                config.imgModel = imgModel
                                imgModelStatus = "模型 $old 已废弃，自动切换为 $imgModel"
                            } else {
                                imgModelStatus = "已过滤废弃，可用 ${imgModels.size} 个"
                            }
                        }
                    } catch (e: Exception) {
                        imgModelStatus = "获取失败：${e.message}"
                    }
                    imgModelLoading = false
                }
            }
        }) {
            OutlinedTextField(
                value = imgModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("生图模型") },
                trailingIcon = {
                    if (imgModelLoading) Text("加载中", style = MaterialTheme.typography.labelSmall)
                    else ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded)
                },
                supportingText = { Text(imgModelStatus) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                val list = if (imgModels.isEmpty()) APIClient.SUPPORTED_MODELS else imgModels
                if (imgModels.isEmpty() && imgModelLoading) {
                    DropdownMenuItem(text = { Text("加载中…") }, onClick = {})
                } else {
                    list.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = {
                            imgModel = m; config.imgModel = m; modelExpanded = false
                            // 旧模型不支持 xhigh/max，切换后自动钳制质量
                            if (!m.contains("2.5")) {
                                if (quality !in QUALITIES_LEGACY) { quality = "high"; config.quality = "high" }
                            }
                        })
                    }
                }
            }
        }

        // 质量下拉（按模型能力过滤）
        ExposedDropdownMenuBox(expanded = qualityExpanded, onExpandedChange = { qualityExpanded = it }) {
            OutlinedTextField(
                value = quality,
                onValueChange = {},
                readOnly = true,
                label = { Text("质量") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                val is25 = imgModel.contains("2.5")
                val list = if (is25) QUALITIES_25 else QUALITIES_LEGACY
                list.forEach { q ->
                    DropdownMenuItem(text = { Text(q) }, onClick = { quality = q; config.quality = q; qualityExpanded = false })
                }
            }
        }

        // 尺寸下拉
        ExposedDropdownMenuBox(expanded = sizeExpanded, onExpandedChange = { sizeExpanded = it }) {
            OutlinedTextField(
                value = size,
                onValueChange = {},
                readOnly = true,
                label = { Text("画布尺寸") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = sizeExpanded, onDismissRequest = { sizeExpanded = false }) {
                SIZES.forEach { s ->
                    DropdownMenuItem(text = { Text(s) }, onClick = { size = s; config.size = s; sizeExpanded = false })
                }
            }
        }

        // 张数滑条（2K/4K 按规范强制 1 张）
        val highRes = APIClient.isHighResSize(size)
        Text(
            if (highRes) "张数 n：1（2K/4K 规范强制单张）" else "张数 n：$count",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = if (highRes) 1f else count.toFloat(),
            onValueChange = { count = it.toInt().coerceIn(1, 4); config.count = count },
            valueRange = 1f..4f,
            steps = 2,
            enabled = !highRes
        )

        Spacer(Modifier.height(8.dp))
        Text("执行方式与迭代评审（多模态对话）", style = MaterialTheme.typography.titleSmall)

        // 执行方式：多模态主控（auto 自动选参数/模型）或 生图模型直出（用户指定模型直接生图）
        ExposedDropdownMenuBox(expanded = executorExpanded, onExpandedChange = { executorExpanded = it }) {
            OutlinedTextField(
                value = Executor.values().firstOrNull { it.name == executor }?.label ?: executor,
                onValueChange = {},
                readOnly = true,
                label = { Text("执行方式") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = executorExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = executorExpanded, onDismissRequest = { executorExpanded = false }) {
                Executor.values().forEach { e ->
                    DropdownMenuItem(text = { Text(e.label) }, onClick = {
                        executor = e.name; config.executor = e.name; executorExpanded = false
                    })
                }
            }
        }
        Text(
            if (executor == "direct") "直出模式：按用户 prompt 与下方参数直接生图，不经过多模态规划/评审。"
            else "多模态主控：auto 时自动选择最合适的模型、尺寸、质量并生成提示词。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 直出模式指定生图模型：auto=用上方生图模型；指定后该模式下固定用该模型
        ExposedDropdownMenuBox(expanded = directModelExpanded, onExpandedChange = { expanded ->
            directModelExpanded = expanded
            if (expanded && imgModels.isEmpty() && !imgModelLoading) {
                scope.launch {
                    imgModelLoading = true
                    imgModelStatus = "正在获取可用生图模型…"
                    try {
                        imgModels = withContext(Dispatchers.IO) { APIClient.availableImageModels(config) }
                        if (imgModels.isEmpty()) {
                            imgModelStatus = "未获取到可用生图模型"
                        } else {
                            // 当前已保存的生图模型被废弃时自动切换，避免选中后调用失败
                            if (imgModel !in imgModels) {
                                val old = imgModel
                                imgModel = imgModels.first()
                                config.imgModel = imgModel
                                imgModelStatus = "模型 $old 已废弃，自动切换为 $imgModel"
                            } else {
                                imgModelStatus = "已过滤废弃，可用 ${imgModels.size} 个"
                            }
                        }
                    } catch (e: Exception) {
                        imgModelStatus = "获取失败：${e.message}"
                    }
                    imgModelLoading = false
                }
            }
        }) {
            OutlinedTextField(
                value = if (directModel == "auto") "auto（跟随上方生图模型）" else directModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("直出指定生图模型") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = directModelExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = directModelExpanded, onDismissRequest = { directModelExpanded = false }) {
                DropdownMenuItem(text = { Text("auto（跟随上方生图模型）") }, onClick = {
                    directModel = "auto"; config.directModel = "auto"; directModelExpanded = false
                })
                val list = if (imgModels.isEmpty()) APIClient.SUPPORTED_MODELS else imgModels
                list.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = {
                        directModel = m; config.directModel = m; directModelExpanded = false
                    })
                }
            }
        }

        OutlinedTextField(
            value = chatBase, onValueChange = { chatBase = it; config.chatBase = it },
            label = { Text("对话 API 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = chatKey, onValueChange = { chatKey = it; config.chatKey = it },
            label = { Text("对话 API Key") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        // 多模态控制模型：自动获取列表，选中即成为控制生图模型的主控
        ExposedDropdownMenuBox(expanded = chatModelExpanded, onExpandedChange = { expanded ->
            chatModelExpanded = expanded
            // 展开时自动拉取模型列表（OpenAI 兼容 /v1/models）
            if (expanded && chatModels.isEmpty() && !chatModelLoading) {
                scope.launch {
                    chatModelLoading = true
                    chatModelStatus = "正在获取模型列表…"
                    try {
                        withContext(Dispatchers.IO) {
                            chatModels = APIClient.listModels(config)
                        }
                        if (chatModels.isEmpty()) {
                            chatModelStatus = "未获取到可用模型"
                        } else {
                            // 若已保存的模型不在可用列表（已废弃/停用），自动切换到第一个可用模型
                            if (chatModel !in chatModels) {
                                chatModel = chatModels.first()
                                config.chatModel = chatModel
                                chatModelStatus = "当前模型不可用，已自动切换为 $chatModel"
                            } else {
                                chatModelStatus = "已获取 ${chatModels.size} 个可用模型"
                            }
                        }
                    } catch (e: Exception) {
                        chatModelStatus = "获取失败：${e.message}"
                    }
                    chatModelLoading = false
                }
            }
        }) {
            OutlinedTextField(
                value = chatModel,
                onValueChange = {},
                readOnly = true,
                label = { Text("多模态控制模型（主控）") },
                trailingIcon = {
                    if (chatModelLoading) {
                        Text("加载中", style = MaterialTheme.typography.labelSmall)
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = chatModelExpanded)
                    }
                },
                supportingText = { Text(chatModelStatus) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(expanded = chatModelExpanded, onDismissRequest = { chatModelExpanded = false }) {
                if (chatModels.isEmpty()) {
                    DropdownMenuItem(text = { Text(if (chatModelLoading) "加载中…" else "无模型，点此重试") }, onClick = {
                        chatModelExpanded = false
                        if (!chatModelLoading) {
                            scope.launch {
                                chatModelLoading = true
                                try {
                                    withContext(Dispatchers.IO) { chatModels = APIClient.listModels(config) }
                                    if (chatModels.isEmpty()) {
                                        chatModelStatus = "未获取到可用模型"
                                    } else {
                                        if (chatModel !in chatModels) {
                                            chatModel = chatModels.first()
                                            config.chatModel = chatModel
                                            chatModelStatus = "当前模型不可用，已自动切换为 $chatModel"
                                        } else {
                                            chatModelStatus = "已获取 ${chatModels.size} 个可用模型"
                                        }
                                    }
                                } catch (e: Exception) {
                                    chatModelStatus = "获取失败：${e.message}"
                                }
                                chatModelLoading = false
                            }
                        }
                    })
                } else {
                    chatModels.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = {
                            chatModel = m; config.chatModel = m; chatModelExpanded = false
                        })
                    }
                }
            }
        }

        // 多模态主控模型系统提示词注入（必须配置，使多模态模型能驱动生图模型）
        Text("主控系统提示词（多模态模型据此控制生图模型）", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = chatSystem,
            onValueChange = { chatSystem = it; config.chatSystem = it },
            label = { Text("系统提示词注入") },
            minLines = 5, maxLines = 10,
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = {
                chatSystem = DEFAULT_CHAT_SYSTEM
                config.chatSystem = DEFAULT_CHAT_SYSTEM
            }) { Text("恢复默认提示词") }
        }

        // 最大轮数（0=不硬停，由评审满意与否决定是否继续）
        Text(
            if (maxTurns == 0) "最大迭代轮数：不限（评审满意即停，不再硬停）"
            else "最大迭代轮数：$maxTurns（达到后暂停，可续跑；评审满意仍会提前停）",
            style = MaterialTheme.typography.bodyMedium
        )
        Slider(
            value = maxTurns.toFloat(),
            onValueChange = { maxTurns = it.toInt().coerceIn(0, 8); config.maxTurns = maxTurns },
            valueRange = 0f..8f,
            steps = 7
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("启用迭代评审", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = iterOn, onCheckedChange = { iterOn = it; config.iterOn = it })
        }

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                testing = true
                testStatus = ""
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            APIClient.generate(config, "a minimal glass apple on pastel background")
                        }
                        testStatus = "连接正常"
                    } catch (e: Exception) {
                        testStatus = "失败：${e.message}"
                    }
                    testing = false
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (testing) "测试中…" else "测试生图服务连接")
        }
        if (testStatus.isNotEmpty()) {
            Text(testStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
    }
}
