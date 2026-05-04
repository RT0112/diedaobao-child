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

    private lateinit var manager: RemoteAssistManager

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
        tvTimer = view.findViewById(R.id.tv_timer)
        ivScreen = view.findViewById(R.id.iv_screen)

        // 缩放型适配（保持比例）
        ivScreen.scaleType = ImageView.ScaleType.FIT_CENTER

        manager = RemoteAssistManager(requireContext())
        val prefs = requireContext().getSharedPreferences("cloudbase", android.content.Context.MODE_PRIVATE)
        val uid = prefs.getString("user_id", null) ?: ""

        // 从云端同步绑定关系（解决老人重新注册后userId变化的问题）
        viewLifecycleOwner.lifecycleScope.launch {
            val synced = CloudBaseClient.syncBindingFromCloud()
            val eid = CloudBaseClient.getElderId() ?: ""
            manager.initialize(uid, eid)
            android.util.Log.i("RemoteAssistFragment", "[诊断]同步绑定: $synced, userId=$uid, elderId=$eid")
        }

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

        btnStart.setOnClickListener { onStartClicked() }
        btnEnd.setOnClickListener { onEndClicked() }
        btnCancel.setOnClickListener { onCancelClicked() }
        btnRetry.setOnClickListener { showIdle(); onStartClicked() }

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

        // 坐标换算：ImageView 坐标 → 老人端屏幕坐标
        val scaleX = elderScreenW.toFloat() / viewW
        val scaleY = elderScreenH.toFloat() / viewH
        val elderX = event.x * scaleX
        val elderY = event.y * scaleY

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
        stopTimer()
        manager.dispose()
    }
}
