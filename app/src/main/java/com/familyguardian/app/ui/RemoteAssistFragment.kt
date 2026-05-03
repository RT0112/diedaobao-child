package com.familyguardian.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.R
import com.familyguardian.app.assist.RemoteAssistManager
import com.familyguardian.app.cloud.CloudBaseClient
import org.webrtc.*

/**
 * 远程协助 Fragment（子女端）
 * 
 * 三阶段界面：
 * 1. 发起阶段 — "发起协助"按钮
 * 2. 等待阶段 — loading + 倒计时
 * 3. 协助中 — 屏幕画面 + 触控操作 + 结束按钮
 */
class RemoteAssistFragment : Fragment() {

    private lateinit var manager: RemoteAssistManager
    private var surfaceView: SurfaceView? = null
    private var videoRenderer: SurfaceViewRenderer? = null

    // UI 组件
    private lateinit var containerIdle: View
    private lateinit var containerWaiting: View
    private lateinit var containerAssisting: View
    private lateinit var containerError: View
    private lateinit var tvWaitingStatus: TextView
    private lateinit var tvErrorMsg: TextView
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnRetry: Button
    private lateinit var tvTimer: TextView

    private var startTime = 0L
    private var timerRunning = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_remote_assist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 查找视图
        containerIdle = view.findViewById(R.id.container_idle)
        containerWaiting = view.findViewById(R.id.container_waiting)
        containerAssisting = view.findViewById(R.id.container_assisting)
        containerError = view.findViewById(R.id.container_error)
        tvWaitingStatus = view.findViewById(R.id.tv_waiting_status)
        tvErrorMsg = view.findViewById(R.id.tv_error_msg)
        btnStart = view.findViewById(R.id.btn_start_assist)
        btnEnd = view.findViewById(R.id.btn_end_assist)
        btnRetry = view.findViewById(R.id.btn_retry)
        tvTimer = view.findViewById(R.id.tv_timer)
        videoRenderer = view.findViewById(R.id.video_renderer)

        // 初始化 VideoRenderer
        videoRenderer?.init(EglBase.create().eglBaseContext, null)
        videoRenderer?.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        videoRenderer?.setEnableHardwareScaler(true)

        // 初始化管理器
        manager = RemoteAssistManager(requireContext())
        val userId = requireContext().getSharedPreferences("cloudbase_prefs", android.content.Context.MODE_PRIVATE)
            .getString("user_id", null) ?: ""
        manager.initialize(userId, CloudBaseClient.getElderId() ?: "")

        // 监听状态变化
        manager.onStateChange = { state, msg ->
            activity?.runOnUiThread {
                when (state) {
                    RemoteAssistManager.State.IDLE -> showIdle()
                    RemoteAssistManager.State.REQUESTING -> showWaiting(msg ?: "正在请求协助...")
                    RemoteAssistManager.State.ACCEPTED -> showWaiting(msg ?: "老人已接受，连接中...")
                    RemoteAssistManager.State.CONNECTING -> showWaiting(msg ?: "建立连接...")
                    RemoteAssistManager.State.STREAMING -> {
                        startTimer()
                        showAssisting()
                    }
                    RemoteAssistManager.State.DISCONNECTED -> {
                        stopTimer()
                        showError(msg ?: "连接已断开")
                    }
                    RemoteAssistManager.State.ERROR -> {
                        stopTimer()
                        showError(msg ?: "发生错误")
                    }
                    RemoteAssistManager.State.REJECTED -> showError("老人拒绝了协助请求")
                    RemoteAssistManager.State.TIMEOUT -> showError("等待超时，老人未响应")
                }
            }
        }

        // 监听视频轨道
        manager.onVideoTrackReady = { track ->
            activity?.runOnUiThread {
                track.addSink(videoRenderer)
            }
        }

        // 监听屏幕尺寸
        manager.onScreenSizeReceived = { w, h ->
            // 可用于坐标换算
        }

        // 按钮事件
        btnStart.setOnClickListener { onStartClicked() }
        btnEnd.setOnClickListener { onEndClicked() }
        btnRetry.setOnClickListener {
            showIdle()
            onStartClicked()
        }

        // 视频区域触控事件
        videoRenderer?.setOnTouchListener { _, event ->
            handleTouchEvent(event)
            true
        }

        showIdle()
    }

    private fun showIdle() {
        containerIdle.visibility = View.VISIBLE
        containerWaiting.visibility = View.GONE
        containerAssisting.visibility = View.GONE
        containerError.visibility = View.GONE
    }

    private fun showWaiting(msg: String) {
        containerIdle.visibility = View.GONE
        containerWaiting.visibility = View.VISIBLE
        containerAssisting.visibility = View.GONE
        containerError.visibility = View.GONE
        tvWaitingStatus.text = msg
    }

    private fun showAssisting() {
        containerIdle.visibility = View.GONE
        containerWaiting.visibility = View.GONE
        containerAssisting.visibility = View.VISIBLE
        containerError.visibility = View.GONE
    }

    private fun showError(msg: String) {
        containerIdle.visibility = View.GONE
        containerWaiting.visibility = View.GONE
        containerAssisting.visibility = View.GONE
        containerError.visibility = View.VISIBLE
        tvErrorMsg.text = msg
    }

    private fun onStartClicked() {
        val guardianName = CloudBaseClient.getUserName().ifEmpty { "家属" }

        // 从 SharedPreferences 获取 userId
        val prefs = requireContext().getSharedPreferences("cloudbase_prefs", android.content.Context.MODE_PRIVATE)
        val uid = prefs.getString("user_id", null)
        val eid = CloudBaseClient.getElderId()

        if (uid == null || eid == null) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }

        manager.initialize(uid, eid)
        manager.requestAssist(guardianName)
    }

    private fun onEndClicked() {
        stopTimer()
        manager.endAssist()
        showIdle()
    }

    private fun handleTouchEvent(event: MotionEvent) {
        if (manager.onVideoTrackReady == null) return

        val elderW = manager.getElderScreenSize().first
        val elderH = manager.getElderScreenSize().second

        if (elderW <= 0 || elderH <= 0) return

        val viewW = videoRenderer?.width ?: return
        val viewH = videoRenderer?.height ?: return

        if (viewW <= 0 || viewH <= 0) return

        // 坐标换算：从 SurfaceView 坐标 → 老人端屏幕坐标
        val scaleX = elderW.toFloat() / viewW
        val scaleY = elderH.toFloat() / viewH
        val elderX = event.x * scaleX
        val elderY = event.y * scaleY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // 记录起始位置（用于判断是否为滑动）
                videoRenderer?.tag = floatArrayOf(event.x, event.y, elderX, elderY)
            }
            MotionEvent.ACTION_UP -> {
                val start = videoRenderer?.tag as? FloatArray
                if (start != null && start.size >= 4) {
                    val dx = Math.abs(event.x - start[0])
                    val dy = Math.abs(event.y - start[1])
                    if (dx < 20 && dy < 20) {
                        // 点击（移动距离小）
                        manager.sendClick(start[2], start[3])
                    } else {
                        // 滑动
                        manager.sendSwipe(start[2], start[3], elderX, elderY)
                    }
                }
            }
        }
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunning = true
        updateTimer()
    }

    private fun stopTimer() {
        timerRunning = false
    }

    private fun updateTimer() {
        if (!timerRunning) return
        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        val min = elapsed / 60
        val sec = elapsed % 60
        tvTimer.text = String.format("%d:%02d", min, sec)
        tvTimer.postDelayed({ updateTimer() }, 1000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        manager.dispose()
        videoRenderer?.release()
    }
}
