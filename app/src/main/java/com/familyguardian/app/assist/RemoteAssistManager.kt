package com.familyguardian.app.assist

import com.familyguardian.app.cloud.WSClient
import com.familyguardian.app.util.AppLogger
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 子女端远程协助管理器（纯 WebSocket 实时推送，无 HTTP 降级）
 *
 * 架构（纯 WS）：
 * 1. 发起协助请求 → WS assist_request
 * 2. 等待老人响应 → WS assist_response 推送
 * 3. 接收屏幕帧   → WS assist_frame 推送
 * 4. 发送触控指令 → WS assist_signal
 * 5. 结束协助     → WS assist_end
 */
class RemoteAssistManager(private val context: Context) {

    companion object {
        private const val TAG = "RemoteAssistManager"
        private const val REQUEST_TIMEOUT_MS = 60_000L
    }

    enum class State {
        IDLE, REQUESTING, ACCEPTED, CONNECTING, STREAMING, DISCONNECTED, ERROR, REJECTED, TIMEOUT
    }

    var onStateChange: ((State, String?) -> Unit)? = null
    var onFrameReceived: ((Bitmap, Int, Int) -> Unit)? = null

    private var userId: String? = null
    private var elderId: String? = null
    var currentState = State.IDLE
        private set
    private var requestStartTime = 0L
    private var lastFrameNum = 0
    private var elderScreenWidth = 720
    private var elderScreenHeight = 1280
    // 优化3: 帧渲染代数计数器，只渲染最新帧，丢弃积压旧帧
    private val renderGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    fun initialize(guardianUserId: String, boundElderId: String) {
        userId = guardianUserId
        elderId = boundElderId
        Log.i(TAG, "初始化: userId=$userId, elderId=$elderId")
        AppLogger.i(TAG, "[诊断] 子女端初始化: userId=$userId, elderId=$elderId")

        // 连接 WebSocket
        // WSClient.connect(context)  // 已在 FamilyGuardianApp 统一调用

        // 监听 WS 事件
        startWSEventListener()
    }

    // ==================== WS 事件监听 ====================

    private var wsEventListenerJob: Job? = null

    private fun startWSEventListener() {
        wsEventListenerJob?.cancel()
        wsEventListenerJob = CoroutineScope(Dispatchers.IO).launch {
            WSClient.events.collect { event ->
                when (event) {
                    is WSClient.WSEvent.AssistResponse -> {
                        // 放宽状态检查：只要不是IDLE或DISCONNECTED都处理（Activity重建后状态可能丢失）
                        if (currentState == State.IDLE || currentState == State.DISCONNECTED) return@collect
                        stopWSTimeoutWatch()
                        if (event.accepted) {
                            updateState(State.ACCEPTED, "老人已接受")
                            // 等待老人端屏幕共享启动
                            delay(500)
                            updateState(State.CONNECTING, "等待屏幕画面...")
                            startFrameTimeoutWatch()
                        } else {
                            updateState(State.REJECTED, "老人拒绝了协助请求")
                        }
                    }

                    is WSClient.WSEvent.AssistFrame -> {
                        if (currentState != State.STREAMING && currentState != State.CONNECTING) return@collect
                        handleWSFrame(event)
                    }

                    is WSClient.WSEvent.AssistEnd -> {
                        stopAll()
                        updateState(State.DISCONNECTED, "协助已结束")
                    }

                    is WSClient.WSEvent.AssistCancel -> {
                        stopAll()
                        updateState(State.ERROR, "协助请求已取消")
                    }

                    is WSClient.WSEvent.FallEvent -> {
                        // 跌倒事件由 HomeFragment 处理，这里不处理
                    }

                    is WSClient.WSEvent.LocationUpdate -> {
                        // 位置更新由 HomeFragment 处理，这里不处理
                    }

                    is WSClient.WSEvent.GeofenceBreach -> {
                        // 围栏越界由 HomeFragment 处理，这里不处理
                    }
                }
            }
        }
    }

    private fun handleWSFrame(frame: WSClient.WSEvent.AssistFrame) {
        try {
            // 优先使用 WS 二进制直传的原始 JPEG（无 Base64 膨胀，延迟更低）
            val bytes: ByteArray = if (frame.jpegBytes != null && frame.jpegBytes!!.isNotEmpty()) {
                frame.jpegBytes!!
            } else if (frame.frameData.isNotEmpty()) {
                // 降级：Base64 JSON 格式（HTTP fallback 或旧版兼容）
                Base64.decode(frame.frameData, Base64.DEFAULT)
            } else {
                return
            }

            if (bytes.isEmpty()) return

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                lastFrameNum = frame.frameNum
                stopFrameTimeoutWatch()
                if (currentState != State.STREAMING) {
                    updateState(State.STREAMING, null)
                }
                // 优化3: 只渲染最新帧，丢弃积压的旧帧
                val myGen = renderGeneration.incrementAndGet()
                Handler(android.os.Looper.getMainLooper()).post {
                    if (myGen == renderGeneration.get()) {
                        onFrameReceived?.invoke(bitmap, frame.width, frame.height)
                    }
                    // 旧帧自动丢弃，不渲染（避免UI线程队列积压导致画面延迟）
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "WS帧解码失败: ${e.message}")
        }
    }

    // ==================== 发起协助（纯 WS） ====================

    fun requestAssist(guardianName: String) {
        if (currentState != State.IDLE) {
            AppLogger.w(TAG, "requestAssist: 当前状态=$currentState，先清理旧会话")
            val eid = elderId
            if (eid != null) WSClient.sendAssistEnd(eid)
            stopAll()
            currentState = State.IDLE
        }
        updateState(State.REQUESTING, null)
        requestStartTime = System.currentTimeMillis()

        // 纯 WS 发送协助请求
        val eid = elderId
        if (eid != null) {
            WSClient.sendAssistRequest(eid, guardianName)
            AppLogger.i(TAG, "WS 发送协助请求: elderId=$eid")

            // HTTP 降级保障：老人端WS离线时也能通过HTTP轮询收到请求
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val uid = userId ?: return@launch
                    val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
                    val token = prefs.getString("jwt_token", null)
                    val body = org.json.JSONObject().apply {
                        put("action", "request")
                        put("elderId", eid)
                        put("guardianId", uid)
                        put("guardianName", guardianName)
                    }
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val requestBody = body.toString().toRequestBody("application/json".toMediaType())
                    val request = okhttp3.Request.Builder()
                        .url("${com.familyguardian.app.config.ServerConfig.BASE_URL}/remote-assist")
                        .post(requestBody)
                        .apply {
                            if (!token.isNullOrEmpty()) addHeader("Authorization", "Bearer $token")
                        }
                        .build()
                    val response = client.newCall(request).execute()
                    response.use {
                        if (it.isSuccessful) {
                            AppLogger.i(TAG, "HTTP协助请求降级已发送: code=${it.code}")
                        } else {
                            AppLogger.w(TAG, "HTTP协助请求降级失败: code=${it.code}")
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "HTTP协助请求降级异常: ${e.message}")
                }
            }
        }
        // 超时检测
        startWSTimeoutWatch()
    }

    private var wsTimeoutJob: Job? = null

    private fun startWSTimeoutWatch() {
        wsTimeoutJob?.cancel()
        wsTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            delay(REQUEST_TIMEOUT_MS)
            if (currentState == State.REQUESTING) {
                updateState(State.TIMEOUT, "等待超时，老人未响应")
                val eid = elderId
                if (eid != null) WSClient.sendAssistCancel(eid)
            }
        }
    }

    private fun stopWSTimeoutWatch() {
        wsTimeoutJob?.cancel()
        wsTimeoutJob = null
    }

    fun cancelRequest() {
        if (currentState != State.REQUESTING) return
        val eid = elderId
        if (eid != null) WSClient.sendAssistCancel(eid)
        stopAll()
        updateState(State.IDLE, null)
    }

    fun endAssist() {
        updateState(State.DISCONNECTED, null)
        val eid = elderId
        if (eid != null) WSClient.sendAssistEnd(eid)
        stopAll()
    }

    // ==================== 触控指令（纯 WS） ====================

    fun sendClick(x: Float, y: Float) {
        val eid = elderId ?: return
        WSClient.sendTouchSignal(eid, "click", x, y, 100L)
    }

    fun sendLongClick(x: Float, y: Float) {
        val eid = elderId ?: return
        WSClient.sendTouchSignal(eid, "click", x, y, 800L)
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        val eid = elderId ?: return
        WSClient.sendSwipeSignal(eid, x1, y1, x2, y2, 300L)
    }

    fun sendKeyAction(keyCode: String) {
        val eid = elderId ?: return
        WSClient.sendKeySignal(eid, keyCode)
    }

    fun getElderScreenSize(): Pair<Int, Int> = Pair(elderScreenWidth, elderScreenHeight)

    // ==================== 帧超时检测 ====================

    private var frameTimeoutJob: Job? = null

    private fun startFrameTimeoutWatch() {
        frameTimeoutJob?.cancel()
        frameTimeoutJob = CoroutineScope(Dispatchers.IO).launch {
            var count = 0
            while (isActive && (currentState == State.CONNECTING || currentState == State.STREAMING)) {
                delay(1000)
                count++
                if (count >= 300) {  // 5分钟超时
                    updateState(State.DISCONNECTED, "连接超时，请检查老人端屏幕共享状态")
                    return@launch
                }
            }
        }
    }

    private fun stopFrameTimeoutWatch() {
        frameTimeoutJob?.cancel()
        frameTimeoutJob = null
    }

    // ==================== 清理 ====================

    private fun stopAll() {
        stopWSTimeoutWatch()
        stopFrameTimeoutWatch()
    }

    fun dispose() {
        stopAll()
        wsEventListenerJob?.cancel()
        wsEventListenerJob = null
        currentState = State.IDLE
        Log.i(TAG, "资源已释放")
    }

    private fun updateState(state: State, message: String?) {
        currentState = state
        Log.i(TAG, "状态变更: $state, msg=$message")
        Handler(android.os.Looper.getMainLooper()).post {
            onStateChange?.invoke(state, message)
        }
    }
}
