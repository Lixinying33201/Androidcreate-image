package com.micu.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.micu.studio.ui.CreateScreen
import com.micu.studio.ui.GalleryScreen
import com.micu.studio.ui.SettingsScreen
import com.micu.studio.ui.theme.ShengTuTaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ShengTuTaiTheme {
                AppRoot()
            }
        }
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val appState = remember { AppState(context.applicationContext) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    remember { LogStore.info("App 启动 v1.8 对话式重构，进入创作页可查看实时日志") }

    // 长按移送/资产库带入：pendingMode 非空时跳到创作页（素材由 CreateScreen 消费并清空）
    val pendingMode by appState.pendingMode
    LaunchedEffect(pendingMode) {
        if (pendingMode != null) {
            tab = 0
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Star, contentDescription = "创作") },
                    label = { Text("创作") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.List, contentDescription = "资产库") },
                    label = { Text("资产库") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = "设置") },
                    label = { Text("设置") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> CreateScreen(appState)
                1 -> GalleryScreen(appState)
                2 -> SettingsScreen(appState.config)
            }
        }
    }
}
