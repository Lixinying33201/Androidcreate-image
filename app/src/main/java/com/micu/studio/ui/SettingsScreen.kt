package com.micu.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micu.studio.APIClient
import com.micu.studio.AppConfig
import com.micu.studio.LogStore
import com.micu.studio.DEFAULT_CHAT_SYSTEM
import com.micu.studio.DEFAULT_ASSET_PROMPT

private val SIZES = listOf(
    "1024x1024", "1280x720", "720x1280", "1024x1536", "1536x1024",
    "2048x2048", "2048x1152", "1152x2048", "3840x2160", "2160x3840"
)
private val QUALITIES_25 = listOf("auto", "low", "medium", "high", "xhigh", "max")
private val QUALITIES_LEGACY = listOf("auto", "low", "medium", "high")
private val MODELS_25 = listOf("gpt-image-2.5-flare", "gpt-image-2.5-sunburst")
private val MODELS_LEGACY = listOf("gpt-image-1", "dall-e-3", "dall-e-2")
private val EXECUTOR_OPTIONS = listOf("multimodal", "auto", "direct")
/** 显示层中文映射：存储值保持英文，界面展示中文 */
private fun executorLabel(v: String) = when (v) {
    "multimodal" -> "多模态主控（大脑规划 + 自动评审续生）"
    "auto" -> "自动（智能选择）"
    "direct" -> "生图模型直出（提示词直送，不评审）"
    else -> v
}
private fun modelLabel(m: String) = when (m) {
    "gpt-image-2.5-flare" -> "GPT Image 2.5 闪焰（默认 · 文生图）"
    "gpt-image-2.5-sunburst" -> "GPT Image 2.5 日晖（质量 · 编辑/融合）"
    "gpt-image-1" -> "GPT Image 1"
    "dall-e-3" -> "DALL·E 3"
    "dall-e-2" -> "DALL·E 2"
    else -> m
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(config: AppConfig) {
    var testStatus by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    var sizeExpanded by remember { mutableStateOf(false) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var executorExpanded by remember { mutableStateOf(false) }
    var directModelExpanded by remember { mutableStateOf(false) }
    var promptExpanded by remember { mutableStateOf(false) }
    var showPromptEditor by remember { mutableStateOf(false) }

    fun runTest() {
        testing = true
        testStatus = "测试中…"
        Thread {
            try {
                val r = APIClient.listModels(config)
                testStatus = if (r.isNotEmpty()) "连接成功（${r.size} 个模型）" else "连接成功（模型列表为空）"
            } catch (e: Exception) {
                testStatus = "异常: ${e.message ?: "未知"}"
                LogStore.err("设置测试异常: ${e.message}")
            }
            testing = false
        }.start()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontSize = 20.sp) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionTitle("接口配置") }
            item {
                SettingTextField(config.chatBase, { config.chatBase = it }, "对话 API 地址（注意 http 明文会被安卓拦截，务必 https）")
            }
            item {
                SettingTextField(config.chatKey, { config.chatKey = it }, "对话 API Key")
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("对话模型：${config.chatModel}", modifier = Modifier.weight(1f))
                    Button(onClick = { runTest() }, enabled = !testing) { Text(if (testing) "测试中…" else "测试连接") }
                }
                if (testStatus.isNotEmpty()) Text(testStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
            }

            item { SectionTitle("生图参数（image2.5 默认）") }
            item {
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = config.imgModel, onValueChange = {}, readOnly = true,
                        label = { Text("生图模型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        (MODELS_25 + MODELS_LEGACY).forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { config.imgModel = m; modelExpanded = false })
                        }
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = qualityExpanded, onExpandedChange = { qualityExpanded = it }) {
                    OutlinedTextField(
                        value = config.quality, onValueChange = {}, readOnly = true,
                        label = { Text("质量（2.5 系列仅支持 low，旧模型可选更多）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = qualityExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = qualityExpanded, onDismissRequest = { qualityExpanded = false }) {
                        val qs = if (config.imgModel.contains("2.5")) QUALITIES_25 else QUALITIES_LEGACY
                        qs.forEach { q ->
                            DropdownMenuItem(text = { Text(q) }, onClick = { config.quality = q; qualityExpanded = false })
                        }
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = sizeExpanded, onExpandedChange = { sizeExpanded = it }) {
                    OutlinedTextField(
                        value = config.size, onValueChange = {}, readOnly = true,
                        label = { Text("默认尺寸") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sizeExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = sizeExpanded, onDismissRequest = { sizeExpanded = false }) {
                        SIZES.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { config.size = s; sizeExpanded = false })
                        }
                    }
                }
            }

            item { SectionTitle("对话与批量") }
            item {
                SettingNumberSlider("批量张数上限", config.batchCount, 1..4, 1) { config.batchCount = it }
            }
            item {
                SettingNumberSlider("单轮最多上传参考图", config.maxUpload, 1..6, 1) { config.maxUpload = it }
            }
            item {
                SettingNumberSlider("迭代窗口（秒）", config.iterWindowSec, 10..120, 10) { config.iterWindowSec = it }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("迭代自动评审续生", modifier = Modifier.weight(1f))
                    Switch(checked = config.iterAutoReview, onCheckedChange = { config.iterAutoReview = it })
                }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("多模态总结生成标题", modifier = Modifier.weight(1f))
                    Switch(checked = config.visionTitle, onCheckedChange = { config.visionTitle = it })
                }
            }
            item {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("迭代模式独立", modifier = Modifier.weight(1f))
                    Switch(checked = config.iterOn, onCheckedChange = { config.iterOn = it })
                }
            }

            item { SectionTitle("生图规范 Prompt（按官方规范引导多模态）") }
            item {
                ExposedDropdownMenuBox(expanded = promptExpanded, onExpandedChange = { promptExpanded = it }) {
                    OutlinedTextField(
                        value = if (config.assetPrompt == DEFAULT_ASSET_PROMPT) "默认官方规范（OpenAI GPT Image 2.5 Prompting Guide）" else "自定义规范（点开编辑）",
                        onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = promptExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = promptExpanded, onDismissRequest = { promptExpanded = false }) {
                        DropdownMenuItem(text = { Text("恢复默认规范") }, onClick = { config.assetPrompt = DEFAULT_ASSET_PROMPT; promptExpanded = false })
                        DropdownMenuItem(text = { Text("编辑自定义规范") }, onClick = { promptExpanded = false; showPromptEditor = true })
                    }
                }
            }

            item { SectionTitle("多模态主控（chatSystem）") }
            item {
                OutlinedTextField(
                    value = config.chatSystem, onValueChange = { config.chatSystem = it },
                    label = { Text("主控系统提示词") },
                    minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                TextButtonRestore("恢复默认主控", onClick = { config.chatSystem = DEFAULT_CHAT_SYSTEM })
            }

            item { SectionTitle("执行方式与直出模型") }
            item {
                ExposedDropdownMenuBox(expanded = executorExpanded, onExpandedChange = { executorExpanded = it }) {
                    OutlinedTextField(
                        value = executorLabel(config.executor), onValueChange = {}, readOnly = true,
                        label = { Text("执行方式") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = executorExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = executorExpanded, onDismissRequest = { executorExpanded = false }) {
                        EXECUTOR_OPTIONS.forEach { m ->
                            DropdownMenuItem(text = { Text(executorLabel(m)) }, onClick = { config.executor = m; executorExpanded = false })
                        }
                    }
                }
            }
            item {
                ExposedDropdownMenuBox(expanded = directModelExpanded, onExpandedChange = { directModelExpanded = it }) {
                    OutlinedTextField(
                        value = modelLabel(config.directModel), onValueChange = {}, readOnly = true,
                        label = { Text("直出模型（仅「直出」方式生效）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = directModelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(expanded = directModelExpanded, onDismissRequest = { directModelExpanded = false }) {
                        (MODELS_25 + MODELS_LEGACY).forEach { m ->
                            DropdownMenuItem(text = { Text(modelLabel(m)) }, onClick = { config.directModel = m; directModelExpanded = false })
                        }
                    }
                }
            }

            item { SectionTitle("实时日志（环形 600 条）") }
            item { LiveLogPanel() }
        }
    }

    if (showPromptEditor) {
        AssetPromptEditorDialog(config, onDismiss = { showPromptEditor = false })
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun SettingTextField(value: String, onSet: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = { onSet(it) },
        label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingNumberSlider(label: String, value: Int, range: IntRange, steps: Int, onChange: (Int) -> Unit) {
    var v by remember { mutableIntStateOf(value) }
    Column {
        Text("$label：$v")
        Slider(
            value = v.toFloat(),
            onValueChange = { v = it.toInt(); onChange(v) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = if (steps > 0) (range.last - range.first) / steps - 1 else 0
        )
    }
}

@Composable
private fun TextButtonRestore(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun LiveLogPanel() {
    val listState = rememberLazyListState()
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            tick++
        }
    }
    val entries = remember(tick) { LogStore.entries.toList() }
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.animateScrollToItem(entries.size - 1)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(10.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        if (entries.isEmpty()) {
            Text("暂无日志", modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries) { e ->
                    Text(
                        e.message,
                        fontSize = 11.sp,
                        color = when (e.level.name) {
                            "ERROR" -> androidx.compose.ui.graphics.Color(0xFFE57373)
                            "WARN" -> androidx.compose.ui.graphics.Color(0xFFFFB74D)
                            "OK" -> androidx.compose.ui.graphics.Color(0xFF81C784)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssetPromptEditorDialog(config: AppConfig, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(config.assetPrompt) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑生图规范 Prompt") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                minLines = 8, maxLines = 16,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { config.assetPrompt = text; LogStore.info("已更新自定义生图规范"); onDismiss() }) { Text("保存") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("取消") }
        }
    )
}
