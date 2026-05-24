package com.familyguardian.app.cloud

import com.familyguardian.app.config.ServerConfig
import android.content.Context
import android.util.Log
import com.familyguardian.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
/**
 * WebSocket 实时推送客户端（子女端）
 * 
 * 接收服务器推送：
 * - 跌倒事件通知（fall_event）→ 替代轮询 get-status
 * - 位置更新（location_update）→ 替代轮询 get-status
 * - 协助响应（assist_response）→ 替代轮询 check_status
 * - 协助结束（assist_end）
 * - 协助取消（assist_cancel）
 * - 屏幕帧（assist_frame）→ 替代 HTTP poll_frame
 * 
 * 降级策略：WS断开时自动退回 HTTP 轮询
 */
object WSClient {
    private const val TAG = "WSClient"
    
    // URL已迁移到ServerConfig
    private val WS_URL = ServerConfig.WS_URL
    
    // 重连配置
    private const val RECONNECT_DELAY_MS = 5000L
    private const val MAX_RECONNECT_ATTEMPTS = 10
    private const val HEARTBEAT_INTERVAL_MS = 25000L
    
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 事件流：供 UI/Service 订阅
    // replay=1 保证新订阅者能收到最新一条消息（避免 FallEvent 丢失）
    private val _events = MutableSharedFlow<WSEvent>(replay = 1, extraBufferCapacity = 50)
    val events: SharedFlow<WSEvent> = _events
    
    private var userId: String? = null
    private var elderId: String? = null
    private var authToken: String? = null
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    // ========== 连接管理 ==========

    fun connect(context: Context) {
        // 用 AtomicBoolean 防止同一进程内重入
        if (!isConnecting.compareAndSet(false, true)) {
            Log.w(TAG, "connect() 正在执行中，跳过重复调用")
            return
        }

        try {
            val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
            userId = prefs.getString("user_id", null)
            elderId = prefs.getString("elder_id", null)
            authToken = prefs.getString("jwt_token", null)

            if (userId == null) {
                Log.w(TAG, "userId 为空，跳过 WS 连接")
                return
            }

            // 检查 WS 连接是否还活着
            val ws = webSocket
            if (isConnected && ws != null) {
                try {
                    ws.send("{\"type\":\"ping\"}")
                    Log.i(TAG, "WebSocket 已连接，跳过")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "WebSocket 连接已断开，准备重连")
                    isConnected = false
                    webSocket = null
                    try { ws.close(1000, "Connection dead") } catch (_: Exception) {}
                }
            }

            // 关闭旧连接（如果存在）
            webSocket?.close(1000, "Reconnecting")
            webSocket = null
            isConnected = false
            reconnectAttempts = 0

            Log.i(TAG, "连接 WebSocket: $WS_URL, userId=$userId, elderId=$elderId")

            val client = OkHttpClient.Builder()
                .pingInterval(HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url(WS_URL)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket 已连接")
                    this@WSClient.webSocket = webSocket
                    this@WSClient.isConnected = true
                    reconnectAttempts = 0

                    // 认证（含 elderId，服务器靠它把老人→家属的消息路由到正确的 WS 连接）
                    val auth = JSONObject().apply {
                        put("type", "auth")
                        if (!authToken.isNullOrEmpty()) {
                            put("token", authToken)
                        }
                        put("data", JSONObject().apply {
                            put("userId", userId)
                            put("role", "guardian")
                            if (elderId != null) put("elderId", elderId)
                        })
                    }
                    Log.d(TAG, "WS auth: userId=$userId, elderId=$elderId, token=${!authToken.isNullOrEmpty()}")
                    webSocket.send(auth.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleBinaryFrame(bytes)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket 关闭中: $code $reason")
                    if (this@WSClient.webSocket === webSocket) {
                        this@WSClient.isConnected = false
                        this@WSClient.webSocket = null
                    }
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "WebSocket 已关闭: $code $reason")
                    if (this@WSClient.webSocket === webSocket) {
                        this@WSClient.isConnected = false
                        this@WSClient.webSocket = null
                    }
                    scheduleReconnect(context)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 失败: ${t.message}")
                    if (this@WSClient.webSocket === webSocket) {
                        this@WSClient.isConnected = false
                        this@WSClient.webSocket = null
                    }
                    webSocket.cancel()
                    scheduleReconnect(context)
                }
            })
        } finally {
            isConnecting.set(false)
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        Log.i(TAG, "WebSocket 已断开")
    }
    
    // ========== 消息处理 ==========

    /**
     * 处理二进制帧（WebSocket 直传 JPEG，无 Base64 膨胀）
     * 协议: 4字节大端 headerLen + JSON header + JPEG body
     * header 格式: {"to":"guardianId","w":360,"h":640,"fn":5}
     */
    private fun handleBinaryFrame(bytes: ByteString) {
        try {
            if (bytes.size < 4) return
            val buf = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.BIG_ENDIAN)
            val headerLen = buf.int
            if (bytes.size < 4 + headerLen) return
            val headerBytes = ByteArray(headerLen)
            buf.get(headerBytes)
            val headerJson = String(headerBytes, Charsets.UTF_8)
            val header = JSONObject(headerJson)
            val jpegData = ByteArray(bytes.size - 4 - headerLen)
            buf.get(jpegData)

            val width = header.optInt("w", 720)
            val height = header.optInt("h", 1280)
            val frameNum = header.optInt("fn", 0)

            if (jpegData.isEmpty()) return

            scope.launch {
                _events.emit(WSEvent.AssistFrame(
                    frameData = "",
                    width = width,
                    height = height,
                    frameNum = frameNum,
                    jpegBytes = jpegData
                ))
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "二进制帧解析失败: ${e.message}")
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type", "")
            val data = json.optJSONObject("data")
            
            when (type) {
                "auth_result" -> {
                    if (json.optBoolean("success", false)) {
                        Log.i(TAG, "WebSocket 认证成功")
                    } else {
                        Log.e(TAG, "WebSocket 认证失败: ${json.optString("message")}")
                    }
                }
                "pong" -> { /* 心跳响应 */ }
                
                "fall_event" -> {
                    // 跌倒事件推送（替代 get-status 轮询）
                    val eventId = data?.optString("eventId", "") ?: ""
                    val timestamp = data?.optLong("timestamp", 0L) ?: 0L
                    val impactG = data?.optDouble("impactG", 0.0) ?: 0.0
                    val mlScore = data?.optDouble("mlScore", 0.0) ?: 0.0
                    val lat = data?.optDouble("latitude", 0.0) ?: 0.0
                    val lng = data?.optDouble("longitude", 0.0) ?: 0.0
                    // v0.47: 完整检测数据
                    val ffDuration = data?.optLong("ffDuration", 0L) ?: 0L
                    val physicalScore = (data?.optDouble("physicalScore", 0.0) ?: 0.0).toFloat()
                    val weightedScore = (data?.optDouble("weightedScore", 0.0) ?: 0.0).toFloat()
                    val decisionPath = data?.optString("decisionPath", "") ?: ""
                    val sensorDataJson = data?.optString("sensorDataJson", "[]") ?: "[]"
                    val feedRate = (data?.optDouble("feedRate", 0.0) ?: 0.0).toFloat()
                    Log.i(TAG, "🔴 收到跌倒事件推送: eventId=$eventId")

                    scope.launch {
                        _events.emit(WSEvent.FallEvent(
                            eventId = eventId,
                            timestamp = timestamp,
                            impactG = impactG,
                            mlScore = mlScore,
                            latitude = if (lat != 0.0) lat else null,
                            longitude = if (lng != 0.0) lng else null,
                            ffDuration = ffDuration,
                            physicalScore = physicalScore,
                            weightedScore = weightedScore,
                            decisionPath = decisionPath,
                            sensorDataJson = sensorDataJson,
                            feedRate = feedRate
                        ))
                    }
                }
                
                "location_update" -> {
                    // 位置更新推送（替代 get-status 轮询中的位置部分）
                    val lat = data?.optDouble("latitude", 0.0) ?: 0.0
                    val lng = data?.optDouble("longitude", 0.0) ?: 0.0
                    val accuracy = data?.optDouble("accuracy", 0.0) ?: 0.0
                    val ts = data?.optLong("timestamp", 0L) ?: 0L
                    Log.i(TAG, "📍 收到位置更新: $lat,$lng")
                    
                    scope.launch {
                        _events.emit(WSEvent.LocationUpdate(
                            latitude = lat,
                            longitude = lng,
                            accuracy = accuracy,
                            timestamp = ts
                        ))
                    }
                }
                
                "assist_response" -> {
                    // 协助请求响应（替代 check_status 轮询）
                    val accepted = data?.optBoolean("accepted", false) ?: false
                    val sessionId = data?.optString("sessionId", "") ?: ""
                    val eId = data?.optString("elderId", "") ?: ""
                    Log.i(TAG, "协助响应: accepted=$accepted, sessionId=$sessionId")
                    
                    scope.launch {
                        _events.emit(WSEvent.AssistResponse(
                            accepted = accepted,
                            sessionId = sessionId,
                            elderId = eId
                        ))
                    }
                }
                
                "assist_frame" -> {
                    // 屏幕帧推送（替代 HTTP poll_frame）
                    val frameData = data?.optString("frameData", "") ?: ""
                    val width = data?.optInt("width", 720) ?: 720
                    val height = data?.optInt("height", 1280) ?: 1280
                    val frameNum = data?.optInt("frameNum", 0) ?: 0
                    
                    if (frameData.isNotEmpty()) {
                        scope.launch {
                            _events.emit(WSEvent.AssistFrame(
                                frameData = frameData,
                                width = width,
                                height = height,
                                frameNum = frameNum
                            ))
                        }
                    }
                }
                
                "assist_end" -> {
                    Log.i(TAG, "协助已结束")
                    scope.launch {
                        _events.emit(WSEvent.AssistEnd)
                    }
                }
                
                "assist_cancel" -> {
                    Log.i(TAG, "协助请求已取消")
                    scope.launch {
                        _events.emit(WSEvent.AssistCancel)
                    }
                }
                
                "geofence_breach" -> {
                    // 围栏越界告警推送
                    val elderName = data?.optString("elderName", "老人") ?: "老人"
                    val breachList = data?.optJSONArray("breaches")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList()
                    val ts = data?.optLong("timestamp", 0L) ?: 0L
                    val eId = data?.optString("elderId", "") ?: ""
                    Log.i(TAG, "⚠️ 收到围栏越界告警: $breachList")
                    
                    scope.launch {
                        _events.emit(WSEvent.GeofenceBreach(
                            elderName = elderName,
                            breaches = breachList,
                            timestamp = ts,
                            elderId = eId
                        ))
                    }
                }
                
                "error" -> {
                    Log.e(TAG, "WS错误: ${json.optString("message")}")
                }
                
                else -> {
                    Log.d(TAG, "收到未知消息: $type")
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "处理消息失败: ${e.message}")
        }
    }
    
    // ========== 发送消息 ==========
    
    private fun safeSend(json: JSONObject) {
        if (!isConnected || webSocket == null) {
            Log.w(TAG, "WebSocket 未连接，跳过发送: ${json.optString("type")}")
            return
        }
        try {
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "发送失败: ${e.message}")
        }
    }
    
    /**
     * 发送位置拉取请求给老人端（替代 HTTP /request-elder-location）
     */
    fun sendLocationRequest(elderId: String) {
        val json = JSONObject().apply {
            put("type", "location_request")
            put("data", JSONObject().apply {
                put("elderId", elderId)
                put("requestTime", System.currentTimeMillis())
            })
        }
        safeSend(json)
    }

    /**
     * 发送协助请求给老人端
     */
    fun sendAssistRequest(elderId: String, guardianName: String) {
        val json = JSONObject().apply {
            put("type", "assist_request")
            put("data", JSONObject().apply {
                put("elderId", elderId)
                put("guardianName", guardianName)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送协助取消
     */
    fun sendAssistCancel(elderId: String) {
        val json = JSONObject().apply {
            put("type", "assist_cancel")
            put("data", JSONObject().apply {
                put("elderId", elderId)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送触摸信令
     */
    fun sendTouchSignal(to: String, touchAction: String, x: Float, y: Float, duration: Long = 100) {
        val json = JSONObject().apply {
            put("type", "assist_signal")
            put("data", JSONObject().apply {
                put("to", to)
                put("type", "touch")
                put("touchAction", touchAction)
                put("x", x.toInt())
                put("y", y.toInt())
                put("duration", duration)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送滑动信令
     */
    fun sendSwipeSignal(to: String, x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val json = JSONObject().apply {
            put("type", "assist_signal")
            put("data", JSONObject().apply {
                put("to", to)
                put("type", "touch")
                put("touchAction", "swipe")
                put("x1", x1.toInt())
                put("y1", y1.toInt())
                put("x2", x2.toInt())
                put("y2", y2.toInt())
                put("duration", duration)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送导航键信令
     */
    fun sendKeySignal(to: String, keyCode: String) {
        val json = JSONObject().apply {
            put("type", "assist_signal")
            put("data", JSONObject().apply {
                put("to", to)
                put("type", "key")
                put("keyCode", keyCode)
            })
        }
        safeSend(json)
    }
    
    /**
     * 发送协助结束
     */
    fun sendAssistEnd(elderId: String) {
        val json = JSONObject().apply {
            put("type", "assist_end")
            put("data", JSONObject().apply {
                put("elderId", elderId)
                put("reason", "ended")
            })
        }
        safeSend(json)
    }
    
    // ========== 重连机制 ==========
    
    private fun scheduleReconnect(context: Context) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "达到最大重连次数($MAX_RECONNECT_ATTEMPTS)，停止重连，切换HTTP降级")
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            reconnectAttempts++
            Log.i(TAG, "尝试重连 WebSocket ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
            connect(context)
        }
    }
    
    // ========== 状态查询 ==========
    
    fun isWSConnected(): Boolean = isConnected
    
    // ========== 事件类型 ==========
    
    sealed class WSEvent {
        /** 跌倒事件推送 */
        data class FallEvent(
            val eventId: String,
            val timestamp: Long,
            val impactG: Double,
            val mlScore: Double,
            val latitude: Double?,
            val longitude: Double?,
            val ffDuration: Long = 0L,
            val physicalScore: Float = 0f,
            val weightedScore: Float = 0f,
            val decisionPath: String = "",
            val sensorDataJson: String = "[]",
            val feedRate: Float = 0f
        ) : WSEvent()
        
        /** 位置更新推送 */
        data class LocationUpdate(
            val latitude: Double,
            val longitude: Double,
            val accuracy: Double,
            val timestamp: Long
        ) : WSEvent()
        
        /** 协助响应（老人接受/拒绝） */
        data class AssistResponse(
            val accepted: Boolean,
            val sessionId: String,
            val elderId: String
        ) : WSEvent()
        
        /** 屏幕帧推送（Base64 JSON 或 WS 二进制直传） */
        data class AssistFrame(
            val frameData: String,       // Base64 编码（旧兼容），二进制模式下为空
            val width: Int,
            val height: Int,
            val frameNum: Int,
            val jpegBytes: ByteArray? = null  // WS 二进制直传的原始 JPEG 字节
        ) : WSEvent()
        
        /** 协助结束 */
        object AssistEnd : WSEvent()
        
        /** 协助取消 */
        object AssistCancel : WSEvent()
        
        /** 围栏越界告警 */
        data class GeofenceBreach(
            val elderName: String,
            val breaches: List<String>,
            val timestamp: Long,
            val elderId: String
        ) : WSEvent()
    }
}
