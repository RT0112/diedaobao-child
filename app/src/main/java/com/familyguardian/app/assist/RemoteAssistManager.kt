package com.familyguardian.app.assist

import android.content.Context
import android.os.Handler
import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.*
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 子女端远程协助管理器
 * 
 * 职责：
 * 1. 发起协助请求（通过 CloudBase）
 * 2. WebRTC PeerConnection 建立（answer 应答方）
 * 3. 接收老人端屏幕画面
 * 4. 发送触控指令（通过 DataChannel）
 * 5. 轮询信令消息
 * 6. 结束协助
 */
class RemoteAssistManager(private val context: Context) {

    companion object {
        private const val TAG = "RemoteAssistManager"
        private const val SIGNAL_URL = "https://diedaobao-cdn-d4g496tvv296f0ac2-1409685971.ap-shanghai.app.tcloudbase.com/remote-assist"

        private val ICE_SERVERS = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
    }

    // 状态
    enum class State {
        IDLE, REQUESTING, ACCEPTED, CONNECTING, STREAMING, DISCONNECTED, ERROR, REJECTED, TIMEOUT
    }

    // 回调
    var onStateChange: ((State, String?) -> Unit)? = null
    var onVideoTrackReady: ((VideoTrack) -> Unit)? = null
    var onScreenSizeReceived: ((Int, Int) -> Unit)? = null

    // WebRTC
    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null

    // 信令轮询
    private var signalingJob: Job? = null
    private var isPolling = false

    // 会话
    private var userId: String? = null
    private var elderId: String? = null
    private var sessionId: String? = null
    private var currentState = State.IDLE
    private var elderScreenWidth = 720
    private var elderScreenHeight = 1280

    fun initialize(guardianUserId: String, boundElderId: String) {
        userId = guardianUserId
        elderId = boundElderId
        initializeWebRTC()
        Log.i(TAG, "初始化: userId=$userId, elderId=$elderId")
    }

    // ==================== 发起协助 ====================

    fun requestAssist(guardianName: String) {
        if (currentState == State.REQUESTING || currentState == State.STREAMING) return

        updateState(State.REQUESTING, null)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(SIGNAL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                val body = JSONObject().apply {
                    put("action", "request")
                    put("elderId", elderId)
                    put("guardianId", userId)
                    put("guardianName", guardianName)
                }
                conn.outputStream.write(body.toString().toByteArray())

                val response = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(response)
                val code = json.optInt("code", 0)

                if (code == 200) {
                    updateState(State.REQUESTING, "等待老人响应...")
                    startStatusPolling() // 轮询老人是否响应
                } else {
                    val msg = json.optString("message", "请求失败")
                    when (code) {
                        409 -> updateState(State.ERROR, msg)
                        404 -> updateState(State.ERROR, "老人设备未注册")
                        else -> updateState(State.ERROR, msg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发起请求异常: ${e.message}")
                updateState(State.ERROR, "网络异常: ${e.message}")
            }
        }
    }

    // ==================== 状态轮询 ====================

    private fun startStatusPolling() {
        stopPolling()
        isPolling = true
        signalingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isPolling) {
                try {
                    val status = pollAssistStatus()
                    when (status) {
                        "active" -> {
                            stopStatusPollingDirectly()
                            sessionId = pollSessionId()
                            updateState(State.ACCEPTED, "老人已接受，正在建立连接...")
                            delay(500)
                            startConnection()
                            return@launch
                        }
                        "idle" -> {
                            if (currentState == State.REQUESTING) {
                                // 可能是被拒绝（需要进一步判断）
                                // 如果之前已经 REQUESTING 了一段时间 → 可能是超时
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "状态轮询异常: ${e.message}")
                }
                delay(2000) // 每2秒轮询
            }
        }
    }

    private fun stopStatusPollingDirectly() {
        isPolling = false
        signalingJob?.cancel()
    }

    private suspend fun pollAssistStatus(): String {
        val url = URL(SIGNAL_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val body = JSONObject().apply {
            put("action", "check_status")
            put("elderId", elderId)
        }
        conn.outputStream.write(body.toString().toByteArray())

        val response = conn.inputStream.bufferedReader().readText()
        return JSONObject(response).optString("status", "idle")
    }

    private suspend fun pollSessionId(): String? {
        val url = URL(SIGNAL_URL)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 5000

        val body = JSONObject().apply {
            put("action", "check_status")
            put("elderId", elderId)
        }
        conn.outputStream.write(body.toString().toByteArray())

        val response = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(response)
        return json.optString("sessionId", null)
    }

    // ==================== WebRTC 连接 ====================

    private fun initializeWebRTC() {
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.i(TAG, "WebRTC 初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "WebRTC 初始化失败: ${e.message}")
        }
    }

    private fun startConnection() {
        updateState(State.CONNECTING, "建立 P2P 连接...")
        
        peerConnection = createPeerConnection()
        if (peerConnection == null) {
            updateState(State.ERROR, "创建连接失败")
            return
        }

        // 开始信令轮询（接收 offer）
        startSignalingPoll()
    }

    private fun createPeerConnection(): PeerConnection? {
        return try {
            val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            val observer = object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(mapOf(
                        "type" to "ice_candidate",
                        "candidate" to mapOf(
                            "sdp" to candidate.sdp,
                            "sdpMid" to candidate.sdpMid,
                            "sdpMLineIndex" to candidate.sdpMLineIndex
                        )
                    ))
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.i(TAG, "ICE 状态: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            updateState(State.STREAMING, null)
                            // 请求屏幕尺寸
                            sendTouchCommand("""{"action":"screen_size"}""")
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED,
                        PeerConnection.IceConnectionState.CLOSED -> {
                            updateState(State.DISCONNECTED, "连接已断开")
                        }
                        else -> Unit
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel) {
                    setupDataChannel(channel)
                }
                override fun onRenegotiationNeeded() = Unit
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        Handler(android.os.Looper.getMainLooper()).post {
                            onVideoTrackReady?.invoke(track)
                        }
                        Log.i(TAG, "收到远端视频轨")
                    } else if (track is AudioTrack) {
                        remoteAudioTrack = track
                        Log.i(TAG, "收到远端音频轨")
                    }
                }
            }

            val pc = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)

            // 创建音频源（用于语音通话）
            val audioConstraints = MediaConstraints()
            val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            val audioTrack = peerConnectionFactory?.createAudioTrack("voice_audio", audioSource)
            if (audioTrack != null) {
                pc?.addTrack(audioTrack, listOf("voice"))
            }

            pc
        } catch (e: Exception) {
            Log.e(TAG, "创建 PeerConnection 失败: ${e.message}")
            null
        }
    }

    private fun setupDataChannel(channel: DataChannel?) {
        dataChannel = channel
        channel?.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                Log.i(TAG, "DataChannel 状态: ${channel.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                // 接收老人端消息（如屏幕尺寸）
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val json = JSONObject(String(bytes))
                    val type = json.optString("type")
                    if (type == "screen_size") {
                        val w = json.optInt("width", 720)
                        val h = json.optInt("height", 1280)
                        elderScreenWidth = w
                        elderScreenHeight = h
                        Handler(android.os.Looper.getMainLooper()).post {
                            onScreenSizeReceived?.invoke(w, h)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DataChannel 消息异常: ${e.message}")
                }
            }
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
        })
    }

    // ==================== 触控指令 ====================

    fun sendClick(x: Float, y: Float) {
        // 坐标已经是老人端屏幕坐标（由 UI 层换算）
        sendTouchCommand(JSONObject().apply {
            put("action", "click")
            put("x", x)
            put("y", y)
            put("duration", 100L)
        }.toString())
    }

    fun sendLongClick(x: Float, y: Float) {
        sendTouchCommand(JSONObject().apply {
            put("action", "click")
            put("x", x)
            put("y", y)
            put("duration", 800L)
        }.toString())
    }

    fun sendSwipe(x1: Float, y1: Float, x2: Float, y2: Float) {
        sendTouchCommand(JSONObject().apply {
            put("action", "swipe")
            put("x1", x1)
            put("y1", y1)
            put("x2", x2)
            put("y2", y2)
            put("duration", 300L)
        }.toString())
    }

    fun sendDoubleClick(x: Float, y: Float) {
        sendTouchCommand(JSONObject().apply {
            put("action", "double_click")
            put("x", x)
            put("y", y)
        }.toString())
    }

    fun getElderScreenSize(): Pair<Int, Int> = Pair(elderScreenWidth, elderScreenHeight)

    private fun sendTouchCommand(json: String) {
        try {
            if (dataChannel?.state() != DataChannel.State.OPEN) {
                Log.w(TAG, "DataChannel 未开启，跳过触控指令")
                return
            }
            val buffer = DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)),
                false
            )
            dataChannel?.send(buffer)
        } catch (e: Exception) {
            Log.e(TAG, "发送触控指令失败: ${e.message}")
        }
    }

    // ==================== 信令 ====================

    private fun startSignalingPoll() {
        isPolling = true
        signalingJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isPolling) {
                pollSignals()
                delay(1000) // 每1秒轮询
            }
        }
    }

    private suspend fun pollSignals() {
        try {
            val url = URL(SIGNAL_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val body = JSONObject().apply {
                put("action", "poll_signal")
                put("userId", userId)
            }
            conn.outputStream.write(body.toString().toByteArray())

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            val signals = json.optJSONArray("signals") ?: return

            for (i in 0 until signals.length()) {
                val signal = signals.getJSONObject(i)
                val type = signal.optString("type")

                when (type) {
                    "offer" -> {
                        val sdp = signal.optString("sdp")
                        if (sdp.isNotEmpty()) handleOffer(sdp)
                    }
                    "ice_candidate" -> {
                        val candidate = signal.optJSONObject("candidate")
                        if (candidate != null) handleIceCandidate(candidate)
                    }
                }
            }
        } catch (e: Exception) {
            // 轮询失败下次重试
        }
    }

    private fun sendSignal(data: Map<String, Any>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(SIGNAL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val body = JSONObject().apply {
                    put("action", "signal")
                    put("from", userId)
                    put("to", elderId)
                    put("signal", JSONObject(data as Map<*, *>))
                }
                conn.outputStream.write(body.toString().toByteArray())
                conn.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "发送信令失败: ${e.message}")
            }
        }
    }

    private fun handleOffer(sdp: String) {
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "setRemoteDescription 失败: $error")
            }
            override fun onCreateSuccess(p0: SessionDescription?) = Unit
            override fun onCreateFailure(p0: String?) = Unit
        }, sessionDescription)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        sendSignal(mapOf(
                            "type" to "answer",
                            "sdp" to sessionDescription.description
                        ))
                    }
                    override fun onSetFailure(error: String) {
                        Log.e(TAG, "setLocalDescription 失败: $error")
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) = Unit
                    override fun onCreateFailure(p0: String?) = Unit
                }, sessionDescription)
            }
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "createAnswer 失败: $error")
            }
            override fun onSetFailure(p0: String?) = Unit
        }, constraints)
    }

    private fun handleIceCandidate(candidate: JSONObject) {
        val sdp = candidate.optString("sdp", "")
        val sdpMid = candidate.optString("sdpMid", "")
        val sdpMLineIndex = candidate.optInt("sdpMLineIndex", 0)
        if (sdp.isNotEmpty()) {
            peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, sdp))
        }
    }

    // ==================== 结束 ====================

    fun endAssist() {
        updateState(State.DISCONNECTED, null)

        // 发送结束信令
        sendSignal(mapOf("type" to "end_session"))

        // 通知云端
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(SIGNAL_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                val body = JSONObject().apply {
                    put("action", "end")
                    put("userId", userId)
                }
                conn.outputStream.write(body.toString().toByteArray())
                conn.inputStream.close()
            } catch (e: Exception) {
                Log.e(TAG, "结束通知异常: ${e.message}")
            }
        }

        stopPolling()
        dispose()
    }

    fun stopPolling() {
        isPolling = false
        signalingJob?.cancel()
        signalingJob = null
    }

    fun dispose() {
        stopPolling()
        dataChannel?.close()
        dataChannel = null
        remoteVideoTrack?.dispose()
        remoteVideoTrack = null
        remoteAudioTrack?.dispose()
        remoteAudioTrack = null
        peerConnection?.close()
        peerConnection = null
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
