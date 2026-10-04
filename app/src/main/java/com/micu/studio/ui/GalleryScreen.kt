@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.micu.studio.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micu.studio.AppState
import com.micu.studio.AssetCategory
import com.micu.studio.AssetItem
import com.micu.studio.AssetStore
import com.micu.studio.GenMode
import com.micu.studio.LogStore
import com.micu.studio.PendingImage
import java.io.File

/** 资产库：五分类（生成图/上传/人物资产/提示词/收藏）+ 小型文件管理（重命名/标签组/移送/收藏）+ 微信式多选 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(appState: AppState) {
    val context = LocalContext.current
    var category by remember { mutableStateOf(AssetCategory.GENERATED) }
    var items by remember { mutableStateOf(AssetStore.load(context, category)) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var multiSelect by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<AssetItem?>(null) }
    var tagDialogItem by remember { mutableStateOf<AssetItem?>(null) }
    var tagInput by remember { mutableStateOf("") }
    var menuItem by remember { mutableStateOf<AssetItem?>(null) }
    var showMoveModeDialog by remember { mutableStateOf(false) }
    var showNewOldDialog by remember { mutableStateOf(false) }
    var targetMode by remember { mutableStateOf<GenMode?>(null) }
    val tagGroups = remember { mutableStateListOf<String>().apply { addAll(AssetStore.loadTagGroups(context)) } }

    fun refresh() {
        items = AssetStore.load(context, category)
        selected = selected.filter { path -> items.any { it.path == path } }.toSet()
    }

    // 上传/添加图片
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                val bm = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                if (bm != null) {
                    val item = AssetStore.saveImage(context, bm, AssetCategory.UPLOAD)
                    LogStore.info("资产库: 新增上传 ${item.name}")
                    refresh()
                }
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("资产库", fontSize = 20.sp)
                        Text("长按图片可添加到/移送/收藏/打标签", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (multiSelect) {
                        Text("已选 ${selected.size}", modifier = Modifier.padding(end = 4.dp))
                        IconButton(onClick = { multiSelect = false; selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "退出多选")
                        }
                    } else {
                        IconButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Icon(Icons.Filled.Add, contentDescription = "添加图片") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                )
            )
        },
        bottomBar = {
            if (multiSelect && selected.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(onClick = { showMoveModeDialog = true },
                            label = { Text("移送至模式") }, leadingIcon = { Icon(Icons.Filled.Send, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = {
                            selected.forEach { p ->
                                AssetStore.addToCategory(context, p, AssetCategory.FAVORITE)
                            }
                            refresh()
                        }, label = { Text("收藏") }, leadingIcon = { Icon(Icons.Filled.Star, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = {
                            selected.forEach { p -> AssetStore.addToCategory(context, p, category) }
                            refresh()
                        }, label = { Text("添加到本分类") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) })
                        AssistChip(onClick = {
                            AssetStore.deleteAll(context, selected.toList())
                            refresh()
                        }, label = { Text("删除") }, leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(16.dp)) })
                    }
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad)) {
            // 分类切换
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AssetCategory.entries.forEach { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { category = c; refresh() },
                        label = { Text(c.label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (tagGroups.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tagGroups.take(8).forEach { tg ->
                        FilterChip(selected = false, onClick = { /* 按标签过滤：简化处理 */ }, label = { Text("#$tg") })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("空空如也，去创作页生成图片吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Column
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(items, key = { it.path }) { item ->
                    val isSelected = item.path in selected
                    Box(Modifier.padding(2.dp)) {
                        if (item.category == AssetCategory.PROMPT) {
                            // 提示词资产显示文字卡片
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(120.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = {
                                            if (multiSelect) {
                                                selected = if (item.path in selected) selected - item.path else selected + item.path
                                            }
                                        },
                                        onLongClick = { multiSelect = true; selected = selected + item.path }
                                    ),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(Modifier.padding(6.dp)) {
                                    Text(File(item.path).name, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        } else {
                            val bm = remember(item.path) { runCatching { BitmapFactory.decodeFile(item.path) }.getOrNull() }
                            if (bm != null) {
                                Image(
                                    bitmap = bm.asImageBitmap(),
                                    contentDescription = item.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                if (multiSelect) {
                                                    selected = if (item.path in selected) selected - item.path else selected + item.path
                                                } else {
                                                    menuItem = item
                                                }
                                            },
                                            onLongClick = {
                                                multiSelect = true
                                                selected = selected + item.path
                                            }
                                        )
                                )
                            }
                        }
                        if (isSelected) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E88E5)),
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
        }
    }

    // 重命名对话框
    renameTarget?.let { target ->
        var name by remember { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    if (AssetStore.rename(context, target.path, name)) {
                        refresh()
                    }
                    renameTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                Button(onClick = { renameTarget = null }) { Text("取消") }
            }
        )
    }

    // 添加标签对话框
    tagDialogItem?.let { target ->
        AlertDialog(
            onDismissRequest = { tagDialogItem = null },
            title = { Text("添加标签（${target.name}）") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        tagGroups.forEach { tg ->
                            AssistChip(onClick = { AssetStore.addTags(context, target.path, listOf(tg)); tagDialogItem = null; refresh() },
                                label = { Text(tg) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = tagInput, onValueChange = { tagInput = it }, label = { Text("新标签") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (tagInput.isNotBlank()) {
                        AssetStore.addTagGroup(context, tagInput)
                        AssetStore.addTags(context, target.path, listOf(tagInput))
                        tagGroups.clear(); tagGroups.addAll(AssetStore.loadTagGroups(context))
                        refresh()
                    }
                    tagDialogItem = null; tagInput = ""
                }) { Text("添加") }
            },
            dismissButton = {
                Button(onClick = { tagDialogItem = null }) { Text("取消") }
            }
        )
    }

    // 长按单项菜单：添加到资产库（选分类）/ 收藏 / 重命名 / 打标签 / 移送
    menuItem?.let { item ->
        AlertDialog(
            onDismissRequest = { menuItem = null },
            title = { Text(File(item.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AssetCategory.entries.filter { it != item.category && it != AssetCategory.PROMPT }.forEach { c ->
                        Text("添加到「${c.label}」", modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                AssetStore.addToCategory(context, item.path, c)
                                refresh(); menuItem = null
                            }
                            .padding(10.dp))
                    }
                    Text("收藏", modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            AssetStore.addToCategory(context, item.path, AssetCategory.FAVORITE)
                            refresh(); menuItem = null
                        }
                        .padding(10.dp))
                    Text("重命名", modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { renameTarget = item; menuItem = null }
                        .padding(10.dp))
                    Text("添加标签", modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { tagDialogItem = item; menuItem = null }
                        .padding(10.dp))
                    Text("移送至模式…", modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { showMoveModeDialog = true; menuItem = null }
                        .padding(10.dp))
                }
            },
            confirmButton = { Button(onClick = { menuItem = null }) { Text("关闭") } }
        )
    }

    // 移送模式选择
    if (showMoveModeDialog) {
        AlertDialog(
            onDismissRequest = { showMoveModeDialog = false },
            title = { Text("移送至哪个模式？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenMode.entries.forEach { m ->
                        Text(m.label, modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                targetMode = m
                                showMoveModeDialog = false
                                showNewOldDialog = true
                            }
                            .padding(10.dp))
                    }
                }
            },
            confirmButton = { Button(onClick = { showMoveModeDialog = false }) { Text("取消") } }
        )
    }

    // 新对话 or 旧对话
    if (showNewOldDialog && targetMode != null) {
        val mode = targetMode!!
        val oldSessions = appState.sessionsByMode(mode)
        AlertDialog(
            onDismissRequest = { showNewOldDialog = false },
            title = { Text("发送到「${mode.label}」") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("新对话", modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            val paths = if (selected.isNotEmpty()) selected.toList() else menuItem?.let { listOf(it.path) } ?: emptyList()
                            paths.forEach { appState.addPendingImage(it) }
                            appState.newSession(mode)
                            appState.pendingMode = mode
                            selected = emptySet(); menuItem = null
                            showNewOldDialog = false
                        }
                        .padding(10.dp))
                    oldSessions.forEach { s ->
                        Text(s.title, modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .clickable {
                                val paths = if (selected.isNotEmpty()) selected.toList() else menuItem?.let { listOf(it.path) } ?: emptyList()
                                paths.forEach { appState.addPendingImage(it) }
                                appState.openSession(s.id)
                                appState.pendingMode = mode
                                selected = emptySet(); menuItem = null
                                showNewOldDialog = false
                            }
                            .padding(10.dp))
                    }
                    if (oldSessions.isEmpty()) {
                        Text("还没有「${mode.label}」的历史对话，先开新对话吧", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { Button(onClick = { showNewOldDialog = false }) { Text("取消") } }
        )
    }
}
