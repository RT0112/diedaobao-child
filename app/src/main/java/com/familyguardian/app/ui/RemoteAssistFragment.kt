package com.familyguardian.app.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.R
import com.familyguardian.app.assist.RemoteAssistManager
import com.familyguardian.app.cloud.CloudBaseClient
import kotlinx.coroutines.launch

/**
 * 远程协助 Fragment（子女端 v2 — 去掉 WebRTC，用 HTTP 帧中继）
 */
class RemoteAssistFragment : Fragment() {

    // ⚠️ manager 不随 View 销毁，保持在 Fragment 生命周期内
    // 切换底部导航栏时 onDestroyView 会杀 View 但 Fragment 对象保留
    private val manager: RemoteAssistManager by lazy {
        RemoteAssistManager(requireContext())
    }
    private var managerInitialized = false

    private lateinit var containerIdle: View
    private lateinit var containerWaiting: View
    private lateinit var containerAssisting: View
    private lateinit var containerError: View
    private lateinit var tvWaitingStatus: TextView
    private lateinit var tvErrorMsg: TextView
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnCancel: Button
    private lateinit var btnRetry: Button
    private lateinit var btnNavBack: Button
    private lateinit var btnNavHome: Button
    private lateinit var btnNavRecents: Button
    private lateinit var btnNavNotifications: Button
    private lateinit var tvTimer: TextView
    private lateinit var ivScreen: ImageView

    private var startTime = 0L
    private var timerRunning = false
    private var elderScreenW = 720
    private var elderScreenH = 1280

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_remote_assist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        containerIdle = view.findViewById(R.id.container_idle)
        containerWaiting = view.findViewById(R.id.container_waiting)
        containerAssisting = view.findViewById(R.id.container_assisting)
        containerError = view.findViewById(R.id.container_error)
        tvWaitingStatus = view.findViewById(R.id.tv_waiting_status)
        tvErrorMsg = view.findViewById(R.id.tv_error_msg)
        btnStart = view.findViewById(R.id.btn_start_assist)
        btnEnd = view.findViewById(R.id.btn_end_assist)
        btnCancel = view.findViewById(R.id.btn_cancel_request)
        btnRetry = view.findViewById(R.id.btn_retry)
        btnNavBack = view.findViewById(R.id.btn_nav_back)
        btnNavHome = view.findViewById(R.id.btn_nav_home)
        btnNavRecents = view.findViewById(R.id.btn_nav_recents)
        btnNavNotifications = view.findViewById(R.id.btn_nav_notifications)
        tvTimer = view.findViewById(R.id.tv_timer)
        ivScreen = view.findViewById(R.id.iv_screen)

        // 缩放型适配（保持比例）
        ivScreen.scaleType = ImageView.ScaleType.FIT_CENTER

        // 初始化 manager（仅首次）
        if (!managerInitialized) {
            val prefs = requireContext().getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
            val uid = prefs.getString("user_id", null) ?: ""

            // 从云端同步绑定关系（解决老人重新注册后userId变化的问题）
            viewLifecycleOwner.lifecycleScope.launch {
                val synced = CloudBaseClient.syncBindingFromCloud()
                val eid = CloudBaseClient.getElderId() ?: ""
                manager.initialize(uid, eid)
                managerInitialized = true
                android.util.Log.i("RemoteAssistFragment", "[诊断]同步绑定: $synced, userId=$uid, elderId=$eid")
            }
        }

        // 重新绑定回调（View 重建后 ivScreen 等是新的）
        manager.onStateChange = { state, msg ->
            activity?.runOnUiThread {
                when (state) {
                    RemoteAssistManager.State.IDLE -> showIdle()
                    RemoteAssistManager.State.REQUESTING -> showWaiting(msg ?: "正在请求协助...")
                    RemoteAssistManager.State.ACCEPTED -> showWaiting(msg ?: "老人已接受，连接中...")
                    RemoteAssistManager.State.CONNECTING -> showWaiting(msg ?: "建立连接...")
                    RemoteAssistManager.State.STREAMING -> {
                        if (!timerRunning) startTimer()
                        showAssisting()
                    }
                    RemoteAssistManager.State.DISCONNECTED -> { stopTimer(); showError(msg ?: "连接已断开") }
                    RemoteAssistManager.State.ERROR -> { stopTimer(); showError(msg ?: "发生错误") }
                    RemoteAssistManager.State.REJECTED -> showError("老人拒绝了协助请求")
                    RemoteAssistManager.State.TIMEOUT -> showError("等待超时，老人未响应")
                }
            }
        }

        manager.onFrameReceived = { bitmap, w, h ->
            elderScreenW = w
            elderScreenH = h
            ivScreen.setImageBitmap(bitmap)
        }

        // 根据当前 manager 状态恢复 UI（切 tab 回来时）
        restoreUIFromState()

        btnStart.setOnClickListener { onStartClicked() }
        btnEnd.setOnClickListener { onEndClicked() }
        btnCancel.setOnClickListener { onCancelClicked() }
        btnRetry.setOnClickListener { showIdle(); onStartClicked() }

        // v19.7.3: 导航按钮
        btnNavBack.setOnClickListener { manager.sendKeyAction("back") }
        btnNavHome.setOnClickListener { manager.sendKeyAction("home") }
        btnNavRecents.setOnClickListener { manager.sendKeyAction("recents") }
        btnNavNotifications.setOnClickListener { manager.sendKeyAction("notifications") }

        // 屏幕触摸事件
        ivScreen.setOnTouchListener { _, event -> handleTouch(event); true }

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
        val prefs = requireContext().getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
        val uid = prefs.getString("user_id", null)
        val eid = CloudBaseClient.getElderId()

        //诊断：显示实际userId和elderId
        android.util.Log.i("RemoteAssistFragment", "[诊断]子女userId=$uid elderId=$eid")
        Toast.makeText(requireContext(), "子女:$uid\n老人:$eid", Toast.LENGTH_LONG).show()

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

    private fun onCancelClicked() {
        stopTimer()
        manager.cancelRequest()
        Toast.makeText(requireContext(), "已取消远程协助请求", Toast.LENGTH_SHORT).show()
        showIdle()
    }

    private fun handleTouch(event: MotionEvent) {
        if (manager.onFrameReceived == null) return
        if (elderScreenW <= 0 || elderScreenH <= 0) return

        val viewW = ivScreen.width
        val viewH = ivScreen.height
        if (viewW <= 0 || viewH <= 0) return

        // FIT_CENTER 缩放模式：计算实际显示区域（考虑 letterbox/pillarbox 黑边）
        // 实际显示的 bitmap 尺寸
        val drawable = ivScreen.drawable ?: return
        val bmpW = drawable.intrinsicWidth.toFloat()
        val bmpH = drawable.intrinsicHeight.toFloat()
        if (bmpW <= 0 || bmpH <= 0) return

        // FIT_CENTER 缩放因子
        val scale = minOf(viewW / bmpW, viewH / bmpH)
        // 实际显示区域在 ImageView 中的偏移（letterbox/pillarbox 黑边）
        val displayW = bmpW * scale
        val displayH = bmpH * scale
        val offsetX = (viewW - displayW) / 2
        val offsetY = (viewH - displayH) / 2

        // ImageView 坐标 → bitmap 坐标（减去黑边偏移 + 除以缩放因子）
        val bmpX = (event.x - offsetX) / scale
        val bmpY = (event.y - offsetY) / scale

        // bitmap 坐标 → 老人真实屏幕坐标（bitmap 是缩小版的屏幕截图）
        val scaleX = elderScreenW.toFloat() / bmpW
        val scaleY = elderScreenH.toFloat() / bmpH
        val elderX = bmpX * scaleX
        val elderY = bmpY * scaleY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                ivScreen.tag = floatArrayOf(event.x, event.y, elderX, elderY)
            }
            MotionEvent.ACTION_UP -> {
                val start = ivScreen.tag as? FloatArray ?: return
                val dx = Math.abs(event.x - start[0])
                val dy = Math.abs(event.y - start[1])
                if (dx < 20 && dy < 20) {
                    manager.sendClick(start[2], start[3])
                } else {
                    manager.sendSwipe(start[2], start[3], elderX, elderY)
                }
            }
        }
    }

    private fun startTimer() {
        startTime = System.currentTimeMillis()
        timerRunning = true
        updateTimer()
    }

    private fun stopTimer() { timerRunning = false }

    private fun updateTimer() {
        if (!timerRunning) return
        val sec = (System.currentTimeMillis() - startTime) / 1000
        tvTimer.text = String.format("%d:%02d", sec / 60, sec % 60)
        tvTimer.postDelayed({ updateTimer() }, 1000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ⚠️ 只清理 UI 资源，不 dispose manager！
        // 切换底部导航栏时 View 会被销毁重建，但 Fragment 对象保留
        // dispose() 会在 onDestroy 中调用
        stopTimer()
        manager.onFrameReceived = null
        manager.onStateChange = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Fragment 真正销毁时才释放 manager
        manager.dispose()
    }

    /**
     * 根据 manager 当前状态恢复 UI 显示
     * 用于切换底部导航栏回来后，画面不丢失
     */
    private fun restoreUIFromState() {
        when (manager.currentState) {
            RemoteAssistManager.State.IDLE -> showIdle()
            RemoteAssistManager.State.REQUESTING -> showWaiting("等待老人响应...")
            RemoteAssistManager.State.ACCEPTED -> showWaiting("老人已接受，连接中...")
            RemoteAssistManager.State.CONNECTING -> showWaiting("建立连接...")
            RemoteAssistManager.State.STREAMING -> {
                showAssisting()
                // STREAMING 状态下不需要重新 startTimer，
                // 因为帧回调会持续触发 onStateChange(STREAMING)，
                // 但 if (!timerRunning) 保护确保不重复
                if (!timerRunning) startTimer()
            }
            RemoteAssistManager.State.DISCONNECTED -> showError("连接已断开")
            RemoteAssistManager.State.ERROR -> showError("发生错误")
            RemoteAssistManager.State.REJECTED -> showError("老人拒绝了协助请求")
            RemoteAssistManager.State.TIMEOUT -> showError("等待超时，老人未响应")
        }
    }
}
