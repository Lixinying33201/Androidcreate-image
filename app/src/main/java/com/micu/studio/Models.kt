package com.micu.studio

import android.content.Context
import android.content.SharedPreferences

enum class GenMode(val label: String, val needImage: Boolean) {
    TEXT("文生图", false),
    EDIT("图生图", true),
    FUSION("多图融合", true),
    BATCH("批量编辑", true),
    ITER("迭代模式", false)
}

/** 生图执行方式：多模态主控（自动选参数/模型/提示词） 或 生图模型直出（用户 prompt + 用户参数） */
enum class Executor(val label: String) {
    MULTIMODAL("多模态主控"), DIRECT("生图模型直出")
}

/** 多模态主控模型默认系统提示词：多模态模型是大脑，生图模型只是它的工具调用 */
const val DEFAULT_CHAT_SYSTEM: String =
    "你是生图工作台的主控多模态模型。用户通过你创作图片，生图模型只是你的一个工具调用，你不直接画图。" +
    "你的职责：\n" +
    "1. 理解用户的创作目标，必要时分析用户提供的参考图片（主体、构图、配色、光影、风格、材质、视角、氛围等）；\n" +
    "2. 决策生图任务：判断用文生图还是带参考图的编辑/融合，输出细节丰富、可直接交给生图模型执行的英文提示词（prompt）；\n" +
    "3. 评审生图模型返回的结果图是否达到目标：达标输出 satisfied=true；不达标输出简短中文点评和优化后的英文提示词 next_prompt；\n" +
    "4. 所有面向生图模型的输出必须是可执行指令，不含无关解释。评审输出统一为 JSON 格式：{\"satisfied\":true或false,\"comment\":\"点评\",\"next_prompt\":\"优化后提示词\"}。"

/** 默认生图规范 prompt：依据 OpenAI GPT Image 2.5 官方 Prompting Guide 提炼，可在设置页修改 */
const val DEFAULT_ASSET_PROMPT: String =
    "按 OpenAI GPT Image 2.5 官方 Prompting Guide 书写生图提示词：\n" +
    "1. 先定义成品与用途，按『场景/背景 → 主体 → 关键细节 → 约束 → 用途』结构组织；\n" +
    "2. 用看得见的细节替代抽象形容词：指定材质、光线方向、色彩、媒介（如 photorealistic、soft window light from the left）；\n" +
    "3. 人物：写清动作、视线、入镜范围（全身/半身/特写）；\n" +
    "4. 画面内文字是契约：文字放引号内，指定位置与字体风格，声明 no other text；\n" +
    "5. 编辑/改图：把『要改什么』与『不能改什么』分开列（Change only X. Keep everything else exactly the same），并列出排除项（水印、logo、多余文字）；\n" +
    "6. 多张参考图：编号并说明用途（主体/风格/背景/服装），交代组合关系；\n" +
    "7. 每轮只改一件事，关键限制每轮重述；\n" +
    "8. 默认 1~3 句清晰英文，复杂需求才用结构化分节。"

data class ReviewResult(val satisfied: Boolean, val comment: String, val nextPrompt: String)

data class SavedImage(val uri: String, val prompt: String, val date: Long)

/** 配置持久化（SharedPreferences） */
class AppConfig(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var imgBase: String
        get() = sp.getString("imgBase", "https://www.micuapi.ai")!!
        set(v) { sp.edit().putString("imgBase", v).apply() }

    var imgKey: String
        get() = sp.getString("imgKey", "")!!
        set(v) { sp.edit().putString("imgKey", v).apply() }

    var imgModel: String
        get() = sp.getString("imgModel", "gpt-image-2.5-flare")!!
        set(v) { sp.edit().putString("imgModel", v).apply() }

    var quality: String
        get() = sp.getString("quality", "low")!!
        set(v) { sp.edit().putString("quality", v).apply() }

    var size: String
        get() = sp.getString("size", "1024x1024")!!
        set(v) { sp.edit().putString("size", v).apply() }

    var count: Int
        get() = sp.getInt("count", 1)
        set(v) { sp.edit().putInt("count", v).apply() }

    var chatBase: String
        get() = sp.getString("chatBase", "https://www.micuapi.ai")!!
        set(v) { sp.edit().putString("chatBase", v).apply() }

    var chatKey: String
        get() = sp.getString("chatKey", "")!!
        set(v) { sp.edit().putString("chatKey", v).apply() }

    var chatModel: String
        get() = sp.getString("chatModel", "gpt-4o")!!
        set(v) { sp.edit().putString("chatModel", v).apply() }

    /** 多模态主控模型的系统提示词（注入到 chat/completions 的 system 消息） */
    var chatSystem: String
        get() = sp.getString("chatSystem", DEFAULT_CHAT_SYSTEM)!!
        set(v) { sp.edit().putString("chatSystem", v).apply() }

    var maxTurns: Int
        get() = sp.getInt("maxTurns", 0)
        set(v) { sp.edit().putInt("maxTurns", v).apply() }

    /** 生图执行方式：multimodal（多模态主控，自动选参数）/ direct（生图模型直出，用户参数） */
    var executor: String
        get() = sp.getString("executor", "multimodal")!!
        set(v) { sp.edit().putString("executor", v).apply() }

    /** 直出模式指定生图模型；auto 表示不指定，用上方生图模型 */
    var directModel: String
        get() = sp.getString("directModel", "auto")!!
        set(v) { sp.edit().putString("directModel", v).apply() }

    var chatTemp: Float
        get() = sp.getFloat("chatTemp", 0.7f)
        set(v) { sp.edit().putFloat("chatTemp", v).apply() }

    var iterOn: Boolean
        get() = sp.getBoolean("iterOn", false)
        set(v) { sp.edit().putBoolean("iterOn", v).apply() }

    /** 批量张数上限（设置可调，默认 4） */
    var batchCount: Int
        get() = sp.getInt("batchCount", 4)
        set(v) { sp.edit().putInt("batchCount", v).apply() }

    /** 单轮最多上传参考图张数（设置可调，默认 4） */
    var maxUpload: Int
        get() = sp.getInt("maxUpload", 4)
        set(v) { sp.edit().putInt("maxUpload", v).apply() }

    /** 迭代窗口（秒）：在此窗口内用户可提前追加要求/参考图 */
    var iterWindowSec: Int
        get() = sp.getInt("iterWindowSec", 60)
        set(v) { sp.edit().putInt("iterWindowSec", v).apply() }

    /** 迭代自动评审续生开关 */
    var iterAutoReview: Boolean
        get() = sp.getBoolean("iterAutoReview", true)
        set(v) { sp.edit().putBoolean("iterAutoReview", v).apply() }

    /** 生图规范 prompt：指导多模态按官方规范写提示词，可在设置修改 */
    var assetPrompt: String
        get() = sp.getString("assetPrompt", DEFAULT_ASSET_PROMPT)!!
        set(v) { sp.edit().putString("assetPrompt", v).apply() }

    /** 多模态总结标题开关 */
    var visionTitle: Boolean
        get() = sp.getBoolean("visionTitle", true)
        set(v) { sp.edit().putBoolean("visionTitle", v).apply() }
}
