---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 26671e2fba3e30f6feb202b95dba33ec_a964dab1be0611f1a05452540064ee0f
    ReservedCode1: F1BYnjRNx/cnMbAWQCKzvvIzjPclx2FhQHFVjG9ul6QJBOZU0F4NPGq+xNdFYI0B1Es96zHhR0YLCaf+eWpbItbWB/9eFYzLzXhBA0kNLYxsflIYkYcSRSoUESsLPFgDZzMLe6WG+Y4yOwGkwaByiEZ8vFXlZmBKQtclhGIg+TvoqmMDQ+51IhJUgu0=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 26671e2fba3e30f6feb202b95dba33ec_a964dab1be0611f1a05452540064ee0f
    ReservedCode2: F1BYnjRNx/cnMbAWQCKzvvIzjPclx2FhQHFVjG9ul6QJBOZU0F4NPGq+xNdFYI0B1Es96zHhR0YLCaf+eWpbItbWB/9eFYzLzXhBA0kNLYxsflIYkYcSRSoUESsLPFgDZzMLe6WG+Y4yOwGkwaByiEZ8vFXlZmBKQtclhGIg+TvoqmMDQ+51IhJUgu0=
---

# 生图台 Android 原生版（Kotlin + Jetpack Compose）

把「生图台」做成 **Android 原生 App**，与 iOS 版同一套功能，Material 3 + 橙暖色主题。

## 功能
- 文生图 / 图生图 / 多图融合 / 批量编辑 四种模式
- 迭代模式：AI 多模态评审 + 提示词优化，自动迭代到满意
- 图库：应用私有目录持久化存储生成图（含提示词索引）
- 一键保存到系统相册（Android 10+ 免权限）
- 设置页：API 地址 / Key / 模型 / 质量 / 尺寸 / 张数 / 评审参数全可配
- 测试连接按钮

## 目录结构
```
生图台Android/
├── settings.gradle.kts
├── build.gradle.kts          # AGP 8.5.2 / Kotlin 2.0.21
├── gradle.properties
└── app/
    ├── build.gradle.kts      # compileSdk 35 / minSdk 26
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/micu/studio/
        │   ├── MainActivity.kt       # 入口 + 底部三 Tab
        │   ├── Models.kt             # 配置持久化 / 数据模型
        │   ├── APIClient.kt          # 米醋 Micu OpenAI 兼容接口（OkHttp）
        │   ├── ImageStore.kt         # 本地图库 + 系统相册
        │   └── ui/
        │       ├── CreateScreen.kt   # 创作页
        │       ├── GalleryScreen.kt  # 图库页
        │       ├── SettingsScreen.kt # 设置页
        │       └── theme/Theme.kt    # 主题
        └── res/                      # 字符串 / 主题 / 图标
```

## 如何构建（需要 Android Studio）
1. 用 Android Studio（Ladybug 或更新）打开 `生图台Android/` 目录
2. 等待 Gradle 同步完成（首次会自动下载 AGP / Compose / OkHttp 依赖）
3. 点 Run，选真机或模拟器（minSdk 26 = Android 8.0+）

## 签名说明
- Debug 构建 Android Studio 会自动用 debug 签名，直接装机。
- 发布到应用市场需自建 keystore 签名（`android studio -> Build -> Generate Signed App Bundle/APK`）。
- 云端 Linux 环境无 JDK / Android SDK，无法在此直接产出 APK；源码完整，本地一键构建。

## API 配置
默认米醋 Micu（`micuapi.ai`，OpenAI 兼容，模型 `gpt-image-2.5-flare` 系列），App 内「设置」页填 API Key 即可；图生图 / 多模态评审同样可配。

## 与 iOS 版差异
- Android 版用系统照片选择器（Photo Picker）选参考图，无需存储权限；iOS 版用 PhotosPicker。
- Android 版无 PWA / 部署环节，直接安装 APK 即可。
*（内容由AI生成，仅供参考）*
