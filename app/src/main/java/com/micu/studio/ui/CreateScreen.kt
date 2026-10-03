package com.micu.studio.ui

import android.content.ContentValues
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micu.studio.AppState
import com.micu.studio.AssetCategory
import com.micu.studio.AssetStore
import com.micu.studio.ChatMessage
import com.micu.studio.ChatSession
import com.micu.studio.GenEngine
import com.micu.studio.GenImage
import com.micu.studio.GenMode
import com.micu.studio.LogStore
import com.micu.studio.TaskState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 创作页：对话式全模式 + 侧边栏历史 + 长按移送/资产库 + 多选 + 迭代窗口 + 任务动画 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(appState: AppState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    var mode by remember { mutableStateOf(GenMode.TEXT) }
    var input by remember { mutableStateOf("") }
    var pickedImages by remember { mutableStateListOf<String>() }
    var multiSelect by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuImage by remember { mutableStateOf<GenImage?>(null) }
    var assetDialogFor by remember { mutableStateOf<GenImage?>(null) }
    var assetBatchDialog by remember { mutableStateOf(false) }
    var moveModeDialog by remember { mutableStateOf(false) }
    var moveNewOldDialog by remember { mutableStateOf(false) }
    var moveTargetMode by remember { mutableStateOf<GenMode?>(null) }
    var movePaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<GenImage?>(null) }
    var iterCountdown by remember { mutableIntStateOf(-1) }

    val session = appState.currentSession()

    // 移送带入：pendingMode 非空时（MainActivity 已切到本页）建会话并携带素材
    val pendingMode by appState.pendingMode
    LaunchedEffect(pendingMode) {
        if (pendingMode != null) {
            mode = pendingMode!!
            val s = appState.newSession(pendingMode!!)
            appState.pendingMode = null
            if (appState.pendingImages.isNotEmpty()) {
                pickedImages.addAll(appState.pendingImages.map { it.path })
                appState.pendingImages.clear()
            }
            LogStore.info("移送进入 ${s.mode.label} 会话")
        }
    }
    // 待发送素材（无 pendingMode 时，如资产库移送旧对话）
    LaunchedEffect(appState.pendingImages.size) {
        if (appState.pendingImages.isNotEmpty() && pendingMode == null) {
            val paths = appState.pendingImages.map { it.path }.filter { it !in pickedImages }
            pickedImages.addAll(paths)
            appState.pendingImages.clear()
        }
    }

    // 任务动画状态
    val taskState = appState.taskState
    val busy = appState.busy
    val listState = rememberLazyListState()
    val messages = session?.messages ?: emptyList()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    // 迭代窗口倒计时：任务处于“未达标等待追加”时显示
    LaunchedEffect(taskState) {
        if (taskState is TaskState.Done && !taskState.ok) {
            iterCountdown = appState.config.iterWindowSec
            while (iterCountdown > 0) {
                delay(1000)
                iterCountdown--
            }
        }
    }

    fun send() {
        val goal = input.trim()
        if (goal.isEmpty() && pickedImages.isEmpty()) return
        if (busy) return
        val s = appState.currentSession() ?: appState.newSession(mode)
        val uploads = pickedImages.toList()
        s.messages.add(ChatMessage(System.currentTimeMillis(), "user", goal, uploads = uploads))
        s.touch()
        input = ""
        pickedImages.clear()
        appState.setBusy(true)
        appState.setTask(TaskState.Planning())
        scope.launch {
            GenEngine.run(context, appState, s, goal.ifEmpty { "根据参考图生成" }, uploads, mode) { st ->
                appState.setTask(st)
            }
            appState.setBusy(false)
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(6)
    ) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use {
                    val bm = BitmapFactory.decodeStream(it)
                    if (bm != null) {
                        // 上传参考图直接存入资产库「上传」分类，可在资产库重命名
                        val item = AssetStore.saveImage(context, bm, AssetCategory.UPLOAD)
                        if (item.path !in pickedImages) pickedImages.add(item.path)
                    }
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxSize()) {
                    Text("对话历史（按模式分组）", fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                    GenMode.entries.forEach { m ->
                        Text(
                            m.label,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                        appState.sessionsByMode(m).forEach { s ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { appState.openSession(s.id); mode = s.mode; scope.launch { drawerState.close() } },
                                        onLongClick = { appState.deleteSession(s.id) }
                                    )
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                    Text("${s.messages.size} 条 · ${fmtTime(s.updatedAt)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Text("长按会话可删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(session?.title ?: mode.label, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (mode == GenMode.ITER) {
                                Text(if (iterCountdown > 0) "迭代窗口 ${iterCountdown}s，可追加要求" else "迭代模式 · 自动评审续生", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "会话历史")
                        }
                    },
                    actions = {
                        if (multiSelect) {
                            Text("已选 ${selectedPaths.size}", modifier = Modifier.padding(end = 8.dp))
                            IconButton(onClick = { multiSelect = false; selectedPaths = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "退出多选")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                )
            },
            bottomBar = {
                Column {
                    // 任务进度动画
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            when (taskState) {
                                is TaskState.Planning -> "多模态规划参数与提示词…"
                                is TaskState.Generating -> "生成中…"
                                is TaskState.Reviewing -> "AI 评审中…"
                                is TaskState.Done -> if (taskState.ok) "完成" else taskState.message
                                else -> ""
                            },
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                        )
                    }
                    // 多选批量操作栏
                    if (multiSelect && selectedPaths.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            AssistChip(onClick = { assetBatchDialog = true }, label = { Text("添加到资产库") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = {
                                val n = selectedPaths.size
                                selectedPaths.forEach { p -> AssetStore.addToCategory(context, p, AssetCategory.FAVORITE) }
                                LogStore.ok("已收藏 $n 张")
                                selectedPaths = emptySet()
                            }, label = { Text("收藏") }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) })
                            AssistChip(onClick = { movePaths = selectedPaths.toList(); moveModeDialog = true }, label = { Text("移送至模式") }, leadingIcon = { Icon(Icons.Filled.Send, null, Modifier.size(16.dp)) })
                        }
                    }
                    // 输入区
                    InputBar(
                        pickedImages = pickedImages,
                        busy = busy,
                        onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemove = { p -> pickedImages.remove(p) },
                        input = input,
                        onInput = { input = it },
                        onSend = { send() }
                    )
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                // 模式切换
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GenMode.entries.forEach { m ->
                        FilterChip(
                            selected = mode == m,
                            onClick = {
                                mode = m
                                // 切模式自动切到对应会话（不存在则新建）
                                val cur = appState.currentSession()
                                if (cur == null || cur.mode != m) {
                                    val exist = appState.sessionsByMode(m).firstOrNull()
                                    if (exist != null) appState.openSession(exist.id) else appState.newSession(m)
                                }
                            },
                            label = { Text(m.label) }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                if (messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "发一句话或选参考图开始吧\n长按生成图可 添加到资产库 / 移送至模式 / 收藏",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp)) {
                        items(messages, key = { it.id }) { msg ->
                            MessageRow(
                                msg = msg,
                                multiSelect = multiSelect,
                                selected = msg.results.any { it.path in selectedPaths },
                                onImageLongPress = { img ->
                                    if (multiSelect) {
                                        selectedPaths = if (img.path in selectedPaths) selectedPaths - img.path else selectedPaths + img.path
                                    } else {
                                        selectedPaths = setOf(img.path)
                                        menuImage = img
                                    }
                                },
                                onImageClick = { img ->
                                    if (multiSelect) {
                                        selectedPaths = if (img.path in selectedPaths) selectedPaths - img.path else selectedPaths + img.path
                                    } else {
                                        selectedPaths = setOf(img.path)
                                        menuImage = img
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 单项操作菜单（长按生成图）
    menuImage?.let { img ->
        AlertDialog(
            onDismissRequest = { menuImage = null },
            title = { Text(fmtTime(img.ts)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogRow("添加到资产库（选分类）", Icons.Filled.Add) { assetDialogFor = img; menuImage = null }
                    DialogRow("收藏", Icons.Filled.Star) { AssetStore.addToCategory(context, img.path, AssetCategory.FAVORITE); menuImage = null; LogStore.ok("已收藏") }
                    DialogRow("移送至模式…", Icons.Filled.Send) { movePaths = listOf(img.path); moveModeDialog = true; menuImage = null }
                    DialogRow("多选（微信式批量操作）", Icons.Filled.Check) { multiSelect = true; selectedPaths = setOf(img.path); menuImage = null }
                    DialogRow("保存到相册", Icons.Filled.Share) { if (saveToAlbum(context, img.path)) LogStore.ok("已保存到相册") else LogStore.err("保存失败"); menuImage = null }
                    DialogRow("删除", Icons.Filled.Delete) { confirmDelete = img; menuImage = null }
                }
            },
            confirmButton = { Button(onClick = { menuImage = null }) { Text("关闭") } }
        )
    }

    // 添加到资产库：选分类（单项）
    assetDialogFor?.let { img ->
        AlertDialog(
            onDismissRequest = { assetDialogFor = null },
            title = { Text("添加到资产库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AssetCategory.UPLOAD, AssetCategory.PERSON, AssetCategory.FAVORITE).forEach { c ->
                        DialogRow(c.label, Icons.Filled.Add) {
                            AssetStore.addToCategory(context, img.path, c)
                            LogStore.ok("已添加到${c.label}")
                            assetDialogFor = null
                        }
                    }
                    Text("当前图片已在「生成图」分类", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { assetDialogFor = null }) { Text("关闭") } }
        )
    }

    // 添加到资产库：多选批量
    if (assetBatchDialog) {
        AlertDialog(
            onDismissRequest = { assetBatchDialog = false },
            title = { Text("批量添加到资产库（${selectedPaths.size} 张）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AssetCategory.UPLOAD, AssetCategory.PERSON, AssetCategory.FAVORITE).forEach { c ->
                        DialogRow(c.label, Icons.Filled.Add) {
                            val n = selectedPaths.size
                            selectedPaths.forEach { p -> AssetStore.addToCategory(context, p, c) }
                            LogStore.ok("已添加 $n 张到${c.label}")
                            assetBatchDialog = false
                            selectedPaths = emptySet()
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { assetBatchDialog = false }) { Text("取消") } }
        )
    }

    // 移送：选模式
    if (moveModeDialog) {
        AlertDialog(
            onDismissRequest = { moveModeDialog = false },
            title = { Text("移送至哪个模式？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenMode.entries.forEach { m ->
                        DialogRow(m.label, Icons.Filled.Send) {
                            moveTargetMode = m
                            moveModeDialog = false
                            moveNewOldDialog = true
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { moveModeDialog = false }) { Text("取消") } }
        )
    }

    // 移送：新对话 or 旧对话
    if (moveNewOldDialog && moveTargetMode != null) {
        val m = moveTargetMode!!
        val old = appState.sessionsByMode(m)
        val paths = movePaths
        AlertDialog(
            onDismissRequest = { moveNewOldDialog = false },
            title = { Text("发送 ${paths.size} 张图到「${m.label}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DialogRow("新对话", Icons.Filled.Add) {
                        paths.forEach { appState.addPendingImage(it) }
                        appState.setPendingMode(m)
                        moveNewOldDialog = false
                        selectedPaths = emptySet()
                        movePaths = emptyList()
                    }
                    old.forEach { s ->
                        DialogRow(s.title, Icons.Filled.Send) {
                            paths.forEach { appState.addPendingImage(it) }
                            appState.openSession(s.id)
                            moveNewOldDialog = false
                            selectedPaths = emptySet()
                            movePaths = emptyList()
                            // 待发送素材由 LaunchedEffect 拾取
                        }
                    }
                    if (old.isEmpty()) Text("暂无该模式历史对话", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { moveNewOldDialog = false }) { Text("取消") } }
        )
    }

    // 删除确认
    confirmDelete?.let { img ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除这张图？") },
            text = { Text("将从资产库删除，且不可恢复。") },
            confirmButton = {
                Button(onClick = {
                    AssetStore.delete(context, img.path)
                    confirmDelete = null
                    LogStore.ok("已删除")
                }) { Text("删除") }
            },
            dismissButton = { Button(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun DialogRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun InputBar(
    pickedImages: List<String>,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: (String) -> Unit,
    input: String,
    onInput: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (pickedImages.isNotEmpty()) {
                // 参考图预览：每张一个框 + 独立“+”框，满了右滑
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pickedImages.forEach { p ->
                        val bm = remember(p) { runCatching { BitmapFactory.decodeFile(p) }.getOrNull() }
                        if (bm != null) {
                            Box {
                                Image(
                                    bitmap = bm.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                IconButton(
                                    onClick = { onRemove(p) },
                                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                                ) { Icon(Icons.Filled.Close, null, Modifier.size(12.dp)) }
                            }
                        }
                    }
                    // 独立“+”框：始终存在，图片多时自动在末尾（配合右滑）
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .combinedClickable(onClick = onPick, onLongClick = {}),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = { Text("描述想生成的画面（迭代模式可在窗口内追加要求）") },
                    minLines = 1, maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onPick, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = "添加参考图", tint = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = onSend, enabled = !busy, modifier = Modifier.height(52.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Send, null)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(
    msg: ChatMessage,
    multiSelect: Boolean,
    selected: Boolean,
    onImageLongPress: (GenImage) -> Unit,
    onImageClick: (GenImage) -> Unit
) {
    if (msg.role == "user") {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.End
        ) {
            if (msg.uploads.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
                    msg.uploads.take(4).forEach { p ->
                        val bm = remember(p) { runCatching { BitmapFactory.decodeFile(p) }.getOrNull() }
                        if (bm != null) {
                            Image(bm.asImageBitmap(), null, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                        }
                    }
                }
            }
            if (msg.text.isNotBlank()) {
                Surface(shape = RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(msg.text, Modifier.padding(10.dp), fontSize = 14.sp)
                }
            }
            Text(fmtTime(msg.ts), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
            Surface(shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Text(msg.text, Modifier.padding(10.dp), fontSize = 13.sp)
            }
            msg.results.forEach { img ->
                val bm = remember(img.path) { runCatching { BitmapFactory.decodeFile(img.path) }.getOrNull() }
                if (bm != null) {
                    Box(Modifier.padding(top = 4.dp)) {
                        Image(
                            bitmap = bm.asImageBitmap(),
                            contentDescription = img.prompt,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onImageClick(img) },
                                    onLongClick = { onImageLongPress(img) }
                                )
                        )
                        if (selected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(Color(0xFF1E88E5)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Check, null, tint = Color.White, Modifier.size(15.dp)) }
                        }
                        Text(
                            fmtTime(img.ts),
                            fontSize = 9.sp,
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0x66000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}

/** 保存到系统相册 */
private fun saveToAlbum(context: android.content.Context, path: String): Boolean = runCatching {
    val bm = BitmapFactory.decodeFile(path) ?: return@runCatching false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, File(path).name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ShengTuTai")
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
    resolver.openOutputStream(uri)?.use { bm.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, it) } ?: return@runCatching false
    true
}.getOrDefault(false)

private fun fmtTime(ts: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ts))
