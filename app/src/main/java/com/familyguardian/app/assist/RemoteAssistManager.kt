package com.familyguardian.app.assist

import com.familyguardian.app.util.AppLogger
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 子女端远程协助管理器
 * 
 * 架构（v2 去掉 WebRTC P2P，改用 CloudBase 帧中继）：
 * 1. 发起协助请求 → HTTP POST /remote-assist action=request
 * 2. 轮询状态          → HTTP POST /remote-assist action=check_status
 * 3. 老人同意后拉取屏幕帧 → HTTP POST /remote-assist action=poll_frame
 * 4. 发送触控指令      → HTTP POST /remote-assist action=signal (type=touch)
 * 5. 结束协助          → HTTP POST /remote-assist action=end
 */
class RemoteAssistManager(private val context: Context) {

    companion object {
        private const val TAG = "RemoteAssistManager"
        private const val SIGNAL_URL =
            "https://diedaobao-cdn-d4g496tvv296f0ac2-1409685971.ap-shanghai.app.tcloudbase.com/remote-assist"
        private const val STATUS_POLL_MS = 2000L
        private const val FRAME_POLL_MS = 300L   // 帧轮询间隔（独立集合后服务端<1s响应）
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
    private var framePollJob: Job? = null
    private var lastFrameNum = 0
    private var elderScreenWidth = 720
    private var elderScreenHeight = 1280

    fun initialize(guardianUserId: String, boundElderId: String) {
        userId = guardianUserId
        elderId = boundElderId
        Log.i(TAG, "初始化: userId=$userId, elderId=$elderId")
        AppLogger.i(TAG, "[诊断] 子女端初始化: userId=$userId, elderId=$elderId")
    }

    // ==================== 发起协助 ====================

    fun requestAssist(guardianName: String) {
        // v19.7.4: 非IDLE状态先清理旧会话
        if (currentState != State.IDLE) {
            AppLogger.w(TAG, "requestAssist: 当前状态=$currentState，先清理旧会话")
            stopPolling()
            currentState = State.IDLE
        }
        updateState(State.REQUESTING, null)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val body = JSONObject().apply {
                    put("action", "request")
                    put("elderId", elderId)
                    put("guardianId", userId)
                    put("guardianName", guardianName)
                }
                val json = httpPost(body)
                val code = json.optInt("code", 0)

                if (code == 200) {
                    requestStartTime = System.currentTimeMillis()
                    updateState(State.REQUESTING, "等待老人响应...")
                    startStatusPolling()
                } else {
                    val msg = json.optString("message", "请求失败")
                    when (code) {
                        409 -> updateState(State.ERROR, msg)
                        404 -> updateState(State.ERROR, "老人设备未注册")
                        else -> updateState(State.ERROR, msg)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "发起请求异常: ${e.message}")
                updateState(State.ERROR, "网络异常: ${e.message}")
            }
        }
    }

    fun cancelRequest() {
        if (currentState != State.REQUESTING) return
        stopPolling()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                httpPost(JSONObject().apply {
                    put("action", "cancel")
                    put("elderId", elderId)
                    put("guardianId", userId)
                })
            } catch (_: Exception) {}
        }
        updateState(State.IDLE, null)
    }

    // ==================== 状态轮询 ====================

    private var statusPollJob: Job? = null

    private fun startStatusPolling() {
        stopPolling()
        statusPollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val elapsed = System.currentTimeMillis() - requestStartTime
                    if (elapsed > REQUEST_TIMEOUT_MS) {
                        updateState(State.TIMEOUT, "等待超时，老人未响应")
                        cancelRemoteRequest()
                        return@launch
                    }

                    val json = httpPost(JSONObject().apply {
                        put("action", "check_status")
                        put("elderId", elderId)
                    })

                    when (json.optString("status", "idle")) {
                        "active" -> {
                            // ⚠️ 不要调 statusPollJob?.cancel()！
                            // cancel() 会取消当前协程，导致 delay(500) 抛 CancellationException，
                            // startFramePolling() 永远不会被调用。
                            // return@launch 已经足够退出 while 循环。
                            // 保存老人真实屏幕分辨率（用于触控坐标映射）
                            val realW = json.optInt("screenWidth", 720)
                            val realH = json.optInt("screenHeight", 1280)
                            if (realW > 0 && realH > 0) {
                                elderScreenWidth = realW
                                elderScreenHeight = realH
                                Log.i(TAG, "老人真实屏幕分辨率: ${realW}x${realH}")
                            }
                            updateState(State.ACCEPTED, "老人已接受")
                            delay(500)
                            startFramePolling()
                            return@launch
                        }
                        "rejected" -> {
                            updateState(State.REJECTED, "老人拒绝了协助请求")
                            return@launch
                        }
                        "cancelled" -> {
                            updateState(State.ERROR, "请求已被取消")
                            return@launch
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "check_status 轮询异常: ${e.message}")
                }
                delay(STATUS_POLL_MS)
            }
        }
    }

    private suspend fun cancelRemoteRequest() {
        try {
            httpPost(JSONObject().apply {
                put("action", "cancel")
                put("elderId", elderId)
                put("guardianId", userId)
            })
        } catch (_: Exception) {}
    }

    // ==================== 帧拉取 ====================

    private fun startFramePolling() {
        updateState(State.CONNECTING, "等待屏幕画面...")
        lastFrameNum = 0

        framePollJob = CoroutineScope(Dispatchers.IO).launch {
            var emptyCount = 0
            var totalEmptyCount = 0  // v19.7.4: 累计空帧总数，不归零
            var decodeFailCount = 0
            val MAX_TOTAL_EMPTY = 200  // 200 * 300ms = 60s 无帧超时
            while (isActive) {
                try {
                    val json = httpPost(JSONObject().apply {
                        put("action", "poll_frame")
                        put("elderId", elderId)
                        put("lastFrameNum", lastFrameNum)
                    })

                    if (json.optBoolean("hasNewFrame", false)) {
                        emptyCount = 0
                        val frame = json.optJSONObject("frame")
                        if (frame == null) {
                            AppLogger.w(TAG, "poll_frame: frame字段缺失")
                            continue
                        }
                        val b64 = frame.optString("data", "")
                        val w = frame.optInt("width", 720)
                        val h = frame.optInt("height", 1280)
                        lastFrameNum = json.optInt("frameNum", lastFrameNum)

                        if (b64.isEmpty()) {
                            AppLogger.w(TAG, "poll_frame: data字段为空")
                            continue
                        }

                        val bytes = Base64.decode(b64, Base64.DEFAULT)
                        if (bytes.isEmpty()) {
                            AppLogger.w(TAG, "poll_frame: Base64解码后为空")
                            continue
                        }

                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bitmap != null) {
                            AppLogger.i(TAG, "[帧接收成功] #${lastFrameNum} ${bytes.size/1024}KB w=${w}h=${h}")
                            decodeFailCount = 0
                            if (currentState != State.STREAMING) {
                                // 帧的 w/h 是缩放后的分辨率（如360x640），不要用它更新触控映射
                                // 触控映射用 check_status 返回的真实分辨率
                                updateState(State.STREAMING, null)
                            }
                            Handler(android.os.Looper.getMainLooper()).post {
                                onFrameReceived?.invoke(bitmap, w, h)
                            }
                        } else {
                            decodeFailCount++
                            AppLogger.e(TAG, "❌ Bitmap解码失败! bytes=${bytes.size}, b64Len=${b64.length}, decodeFailCount=$decodeFailCount")
                            if (decodeFailCount >= 3) {
                                updateState(State.ERROR, "画面数据异常，请重新发起协助")
                                return@launch
                            }
                        }
                    } else {
                        emptyCount++
                        totalEmptyCount++
                        val msg = json.optString("message", "")
                        val targetId = json.optString("targetId", "")
                        val bufSize = json.optInt("bufferSize", -1)
                        AppLogger.i(TAG, "[帧拉取] 无新帧 emptyCount=$emptyCount total=$totalEmptyCount msg=$msg targetId=$targetId bufSize=$bufSize")
                        Log.d(TAG, "poll_frame: 无新帧 emptyCount=$emptyCount total=$totalEmptyCount msg=$msg targetId=$targetId bufSize=$bufSize")

                        // v19.7.4: 60秒无帧超时断开
                        if (totalEmptyCount >= MAX_TOTAL_EMPTY) {
                            AppLogger.w(TAG, "帧拉取超时: ${totalEmptyCount}次无帧，自动断开")
                            updateState(State.DISCONNECTED, "连接超时，老人端未上传画面")
                            return@launch
                        }

                        // 等待中显示为 CONNECTING，每30次(~7.5s)刷新提示
                        if (currentState == State.CONNECTING && emptyCount > 30) {
                            updateState(State.CONNECTING, "等待屏幕画面...(${totalEmptyCount}次)")
                            emptyCount = 0
                        }
                    }

                    // 检查屏幕就绪
                    val status = json.optString("status", "")
                    if (status == "idle") {
                        updateState(State.DISCONNECTED, "老人端已断开")
                        return@launch
                    }
                } catch (e: Exception) {
                    AppLogger.e(TAG, "帧拉取异常: ${e.message}")
                }
                delay(FRAME_POLL_MS)
            }
        }
    }

    // ==================== 触控指令 ====================

    fun sendClick(x: Float, y: Float) {
        sendTouchSignal("click", mapOf("x" to x, "y" to y, "duration" to 100L))
    }

    fun sendLongClick(x: Float, y: Float) {
        sendTouchSignal("click", mapOf("x" to x, "y" to y, "duration" to 800L))
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        sendTouchSignal("swipe", mapOf("x1" to x1, "y1" to y1, "x2" to x2, "y2" to y2, "duration" to 300L))
    }

    fun getElderScreenSize(): Pair<Int, Int> = Pair(elderScreenWidth, elderScreenHeight)

    private fun sendTouchSignal(action: String, params: Map<String, Any>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val signalData = mutableMapOf<String, Any>("type" to "touch", "touchAction" to action)
                signalData.putAll(params)
                httpPost(JSONObject().apply {
                    put("action", "signal")
                    put("from", userId)
                    put("to", elderId)
                    put("signal", JSONObject(signalData as Map<*, *>))
                })
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送触控失败: ${e.message}")
            }
        }
    }

    // v19.7.3: 导航键指令
    fun sendKeyAction(keyCode: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val signalData = mapOf<String, Any>("type" to "key", "keyCode" to keyCode)
                httpPost(JSONObject().apply {
                    put("action", "signal")
                    put("from", userId)
                    put("to", elderId)
                    put("signal", JSONObject(signalData))
                })
                AppLogger.i(TAG, "导航键已发送: $keyCode")
            } catch (e: Exception) {
                AppLogger.e(TAG, "发送导航键失败: ${e.message}")
            }
        }
    }

    // ==================== 结束 ====================

    fun endAssist() {
        updateState(State.DISCONNECTED, null)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 通知老人结束
                httpPost(JSONObject().apply {
                    put("action", "signal")
                    put("from", userId)
                    put("to", elderId)
                    put("signal", JSONObject(mapOf("type" to "end_session")))
                })
                // 通知云端结束
                httpPost(JSONObject().apply {
                    put("action", "end")
                    put("elderId", elderId)
                })
            } catch (_: Exception) {}
        }
        stopPolling()
    }

    fun stopPolling() {
        statusPollJob?.cancel()
        statusPollJob = null
        framePollJob?.cancel()
        framePollJob = null
    }

    fun dispose() {
        stopPolling()
        currentState = State.IDLE
        Log.i(TAG, "资源已释放")
    }

    // ==================== 工具 ====================

    private suspend fun httpPost(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = URL(SIGNAL_URL)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.outputStream.write(body.toString().toByteArray(Charsets.UTF_8))
            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response)
        } catch (e: Exception) {
            AppLogger.e(TAG, "httpPost 失败: ${e.message}, url=$SIGNAL_URL, action=${body.optString("action","?")}")
            throw e  // 不吞异常，让调用方处理
        } finally {
            conn.disconnect()
        }
    }

    private fun updateState(state: State, message: String?) {
        currentState = state
        Log.i(TAG, "状态变更: $state, msg=$message")
        Handler(android.os.Looper.getMainLooper()).post {
            onStateChange?.invoke(state, message)
        }
    }
}
