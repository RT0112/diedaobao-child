package com.familyguardian.app.cloud

import android.content.Context
import android.util.Log
import com.familyguardian.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
    
    // K70 本地服务器（子女端通过局域网IP连接）
    private const val WS_URL = "wss://scheduling-researchers-discuss-compatible.trycloudflare.com/ws"
    
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
    private val _events = MutableSharedFlow<WSEvent>(extraBufferCapacity = 50)
    val events: SharedFlow<WSEvent> = _events
    
    private var userId: String? = null
    private var elderId: String? = null
    
    // ========== 连接管理 ==========
    
    fun connect(context: Context) {
        val prefs = context.getSharedPreferences("cloudbase", Context.MODE_PRIVATE)
        userId = prefs.getString("user_id", null)
        elderId = prefs.getString("elder_id", null)
        
        if (userId == null) {
            Log.w(TAG, "userId 为空，跳过 WS 连接")
            return
        }
        
        if (isConnected && webSocket != null) {
            Log.i(TAG, "WebSocket 已连接，跳过")
            return
        }
        
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
                isConnected = true
                reconnectAttempts = 0
                
                // 认证
                val auth = JSONObject().apply {
                    put("type", "auth")
                    put("data", JSONObject().apply {
                        put("userId", userId)
                        put("role", "guardian")
                    })
                }
                webSocket.send(auth.toString())
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket 关闭: $code $reason")
                isConnected = false
                webSocket.close(1000, null)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket 已关闭: $code $reason")
                isConnected = false
                scheduleReconnect(context)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 失败: ${t.message}")
                isConnected = false
                webSocket.cancel()
                scheduleReconnect(context)
            }
        })
    }
    
    fun disconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        isConnected = false
        Log.i(TAG, "WebSocket 已断开")
    }
    
    // ========== 消息处理 ==========
    
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
                    Log.i(TAG, "🔴 收到跌倒事件推送: eventId=$eventId")
                    
                    scope.launch {
                        _events.emit(WSEvent.FallEvent(
                            eventId = eventId,
                            timestamp = timestamp,
                            impactG = impactG,
                            mlScore = mlScore,
                            latitude = if (lat != 0.0) lat else null,
                            longitude = if (lng != 0.0) lng else null
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
            val longitude: Double?
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
        
        /** 屏幕帧推送 */
        data class AssistFrame(
            val frameData: String,
            val width: Int,
            val height: Int,
            val frameNum: Int
        ) : WSEvent()
        
        /** 协助结束 */
        object AssistEnd : WSEvent()
        
        /** 协助取消 */
        object AssistCancel : WSEvent()
    }
}
