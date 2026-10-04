package com.micu.studio

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 全局任务状态，供进度动画/进度条展示 */
sealed class TaskState {
    object Idle : TaskState()
    data class Planning(val step: Int = 0) : TaskState()
    data class Generating(val step: Int = 0) : TaskState()
    data class Reviewing(val step: Int = 0) : TaskState()
    data class Done(val ok: Boolean, val message: String = "") : TaskState()
}

/** 全局应用状态：会话、消息、任务协程作用域全部提升到 App 层，切 Tab 不再导致协程取消、任务清零 */
class AppState(context: Context) {

    val config = AppConfig(context.applicationContext)

    /** App 级协程作用域：生图任务挂在这里，页面销毁不影响任务继续 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 所有会话（按模式分组渲染） */
    val sessions = mutableStateListOf<ChatSession>()

    var currentSessionId by mutableStateOf<String?>(null)
        private set

    /** 全局任务进行中：防重入 */
    var busy by mutableStateOf(false)

    var taskState by mutableStateOf<TaskState>(TaskState.Idle)
        private set

    /** 待发送素材（长按移送 / 资产库选择后带入） */
    val pendingImages = mutableStateListOf<PendingImage>()
    var pendingMode by mutableStateOf<GenMode?>(null)

    fun setTask(s: TaskState) { taskState = s }

    fun newSession(mode: GenMode, title: String = mode.label): ChatSession {
        val s = ChatSession(mode = mode, title = title)
        sessions.add(0, s)
        currentSessionId = s.id
        return s
    }

    fun openSession(id: String) {
        currentSessionId = id
    }

    /** 删除会话（侧边栏长按） */
    fun deleteSession(id: String) {
        val target = sessions.firstOrNull { it.id == id } ?: return
        sessions.remove(target)
        if (currentSessionId == id) {
            currentSessionId = sessions.firstOrNull()?.id
        }
    }

    fun currentSession(): ChatSession? = sessions.firstOrNull { it.id == currentSessionId }

    /** 按模式取最近会话（移送目标旧对话选择用） */
    fun sessionsByMode(mode: GenMode): List<ChatSession> =
        sessions.filter { it.mode == mode }.sortedByDescending { it.updatedAt }

    fun addPendingImage(path: String) {
        if (pendingImages.none { it.path == path }) pendingImages.add(PendingImage(path, System.currentTimeMillis()))
    }

    fun clearPending() = pendingImages.clear()
}
