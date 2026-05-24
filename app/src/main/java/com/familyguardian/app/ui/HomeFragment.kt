package com.familyguardian.app.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.data.AppDatabase
import androidx.core.app.NotificationCompat
import com.familyguardian.app.data.FallNotification
import com.familyguardian.app.data.GeofenceNotification
import com.familyguardian.app.databinding.FragmentHomeBinding
import com.familyguardian.app.cloud.WSClient
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {
    
    private var _binding: FragmentHomeBinding? = null
    
    private val elderName by lazy { CloudBaseClient.getElderName() }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return _binding!!.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val b = _binding ?: return
        
        // 绑定按钮
        b.btnBind.setOnClickListener { onBindClick() }
        
        // 查看位置按钮 → 打开地图Activity
        b.btnViewLocation.setOnClickListener { onViewLocation() }
        
        // 下拉刷新
        b.swipeRefresh.setOnRefreshListener {
            loadElderStatus()
        }
        
        // 首次启动自动注册
        ensureRegistered()
        
        // 更新UI状态
        updateUI()

        // v21: 连接 WS 并监听跌倒事件 + 位置更新
        startWSListener()
    }
    
    /**
     * 确保已注册（首次启动自动注册）
     */
    private fun ensureRegistered() {
        if (!CloudBaseClient.isRegistered()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val success = CloudBaseClient.autoRegister(requireContext())
                if (!success) {
                    Toast.makeText(requireContext(), "网络注册失败，部分功能不可用", Toast.LENGTH_LONG).show()
                } else {
                    // 注册成功后，从云端同步绑定关系
                    CloudBaseClient.syncBindingFromCloud()
                }
            }
        } else {
            // 已注册但也要同步最新绑定（防止老人重新注册后 elderId 过期）
            viewLifecycleOwner.lifecycleScope.launch {
                CloudBaseClient.syncBindingFromCloud()
            }
        }
    }
    
    /**
     * 更新UI状态（绑定/未绑定）
     */
    private fun updateUI() {
        val b = _binding ?: return
        val hasBound = CloudBaseClient.hasBoundElder()
        
        if (hasBound) {
            b.cardBindElder.visibility = View.GONE
            b.cardElderStatus.visibility = View.VISIBLE
            b.layoutActions.visibility = View.VISIBLE
            loadElderStatus()
        } else {
            b.cardBindElder.visibility = View.VISIBLE
            b.cardElderStatus.visibility = View.GONE
            b.layoutActions.visibility = View.GONE
        }
    }
    
    /**
     * 绑定按钮点击
     */
    private fun onBindClick() {
        val b = _binding ?: return
        val code = b.etBindCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(requireContext(), "请输入绑定码", Toast.LENGTH_SHORT).show()
            return
        }
        if (code.length != 6) {
            showBindError("绑定码为6位数字")
            return
        }
        
        // 先确保已注册
        if (!CloudBaseClient.isRegistered()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val registered = CloudBaseClient.autoRegister(requireContext())
                if (!registered) {
                    showBindError("注册失败，请检查网络")
                    return@launch
                }
                doBind(code)
            }
        } else {
            doBind(code)
        }
    }
    
    private fun doBind(code: String) {
        val b = _binding ?: return
        b.btnBind.isEnabled = false
        b.btnBind.text = "绑定中..."
        b.tvBindError.visibility = View.GONE
        
        viewLifecycleOwner.lifecycleScope.launch {
            val result = CloudBaseClient.bindElder(code)
            
            if (!isAdded) return@launch
            _binding?.let { b ->
                b.btnBind.isEnabled = true
                b.btnBind.text = "绑定"
                
                if (result.success) {
                    Toast.makeText(requireContext(), "绑定成功！", Toast.LENGTH_SHORT).show()
                    b.etBindCode.text?.clear()
                    if (isAdded) updateUI()
                } else {
                    showBindError(result.message)
                }
            }
        }
    }
    
    private fun showBindError(message: String) {
        if (!isAdded) return
        _binding?.let { b ->
            b.tvBindError.text = message
            b.tvBindError.visibility = View.VISIBLE
        }
    }
    
    private fun loadElderStatus() {
        if (!isAdded) return
        if (_binding == null) return
        if (!CloudBaseClient.hasBoundElder()) return
        
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isAdded) return@launch
            
            val status = CloudBaseClient.getElderStatus()
            if (!isAdded) return@launch
            
            // 关闭下拉刷新动画
            _binding?.swipeRefresh?.isRefreshing = false
            
            _binding?.let { b ->
                if (status != null) {
                    b.tvElderName.text = status.name.ifEmpty { elderName }
                    
                    // 保存老人信息
                    CloudBaseClient.saveElderInfo(status.name, null)
                    
                    val statusText = if (status.status == "fallen") "⚠️ 跌倒报警！" else "状态正常 ✅"
                    b.tvStatus.text = statusText
                    b.tvStatus.setTextColor(
                        if (status.status == "fallen") 0xFFF44336.toInt() else 0xFF4CAF50.toInt()
                    )
                    
                    val timeStr = formatTime(status.lastUpdate)
                    b.tvLastUpdate.text = "最后更新：$timeStr"
                    
                    // 位置状态：显示位置时间
                    val loc = status.lastLocation
                    if (loc != null) {
                        val locTime = formatTime(loc.timestamp)
                        b.btnViewLocation.text = "📍 查看位置（$locTime）"
                    } else {
                        b.btnViewLocation.text = "📍 查看位置"
                    }
                    
                    // 检测新跌倒事件 → 存本地通知
                    checkAndSaveFallNotification(status)
                } else {
                    b.tvElderName.text = elderName
                    b.tvStatus.text = "获取状态失败"
                    b.tvStatus.setTextColor(0xFF666666.toInt())
                }
            }
        }
    }
    
    /**
     * 查看位置 → 打开地图Activity
     */
    private fun onViewLocation() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = android.content.Intent(requireContext(), MapActivity::class.java)
        startActivity(intent)
    }
    
    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> "${diff / 3600_000}小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /**
     * 检测到新跌倒事件时存本地并发系统通知
     * 云端只用于传递信号，历史数据纯本地
     */
    private fun checkAndSaveFallNotification(status: CloudBaseClient.ElderStatus) {
        val fallEvent = status.lastFallEvent ?: return
        if (fallEvent.eventId.isEmpty()) return
        
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            // 去重：已存过的事件不再重复
            val existing = db.fallNotificationDao().getByEventId(fallEvent.eventId)
            if (existing != null) return@launch
            
            // 存本地
            val notification = FallNotification(
                eventId = fallEvent.eventId,
                elderName = status.name.ifEmpty { elderName },
                timestamp = fallEvent.timestamp,
                latitude = fallEvent.latitude,
                longitude = fallEvent.longitude,
                impactG = fallEvent.impactG,
                mlScore = fallEvent.mlScore,
                isRead = false,
                isHandled = false,
                // v0.47: 完整检测数据
                ffDuration = fallEvent.ffDuration,
                physicalScore = fallEvent.physicalScore.toFloat(),
                weightedScore = fallEvent.weightedScore.toFloat(),
                decisionPath = fallEvent.decisionPath,
                sensorDataJson = fallEvent.sensorDataJson,
                feedRate = fallEvent.feedRate.toFloat()
            )
            db.fallNotificationDao().insert(notification)
            
            // 发系统通知
            showFallNotification(notification)
            
            Log.i("HomeFragment", "新跌倒通知已保存: eventId=${fallEvent.eventId}")
        }
    }
    
    private fun showFallNotification(notification: FallNotification) {
        try {
            val manager = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            
            // 读取强制弹窗通知开关
            val prefs = requireContext().getSharedPreferences("family_guardian_settings", 0)
            val forcePopup = prefs.getBoolean("force_popup_notification", true)
            
            // 创建两个独立渠道：full_screen（强制弹窗用）和 fall_alert（普通通知用）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val fullScreenChannel = android.app.NotificationChannel(
                    "full_screen_channel", "跌倒警报（全屏）", android.app.NotificationManager.IMPORTANCE_MAX
                ).apply {
                    description = "跌倒告警全屏弹窗，优先级最高"
                    enableVibration(true)
                    enableLights(true)
                    setBypassDnd(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                val normalChannel = android.app.NotificationChannel(
                    "fall_alert", "跌倒警报", android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "老人跌倒时接收紧急通知"
                    enableVibration(true)
                    enableLights(true)
                }
                manager.createNotificationChannel(fullScreenChannel)
                manager.createNotificationChannel(normalChannel)
            }
            
            // 点击通知打开跌倒位置地图（view_fall模式）
            val intent = android.content.Intent(requireContext(), MapActivity::class.java).apply {
                putExtra("mode", "view_fall")
                putExtra("fallLat", notification.latitude)
                putExtra("fallLng", notification.longitude)
                putExtra("fallTime", notification.timestamp)
                putExtra("fallName", notification.elderName)
            }
            
            val contentIntent = android.app.PendingIntent.getActivity(
                requireContext(), 0, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            
            // 强制弹窗模式：使用全屏Intent（类似来电），绕过MIUI静默通知
            // v24: 移除 canDrawOverlays 检查 — 全屏Intent不需要悬浮窗权限
            // USE_FULL_SCREEN_INTENT 权限已在Manifest中声明，系统会自动允许
            val useFullScreen = forcePopup && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M

            if (useFullScreen) {
                // 全屏弹窗模式：参照老人端实现，使用 NotificationCompat + PRIORITY_MAX + CATEGORY_ALARM + ongoing
                val fullScreenIntent = android.app.PendingIntent.getActivity(
                    requireContext(), 1, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val notif = NotificationCompat.Builder(requireContext(), "full_screen_channel")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🚨 ${notification.elderName}跌倒警告")
                    .setContentText("请及时确认老人状况")
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setOngoing(true)
                    .setAutoCancel(false)
                    .setFullScreenIntent(fullScreenIntent, true)
                    .setContentIntent(contentIntent)
                    .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()
                manager.notify(notification.eventId.hashCode(), notif)
            } else {
                // 普通通知模式
                val notif = NotificationCompat.Builder(requireContext(), "fall_alert")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("🚨 ${notification.elderName}跌倒警告")
                    .setContentText("请及时确认老人状况")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(contentIntent)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .build()
                manager.notify(notification.eventId.hashCode(), notif)
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "系统通知发送失败: ${e.message}")
        }
    }
    
    private fun showGeofenceBreachNotification(event: WSClient.WSEvent.GeofenceBreach) {
        try {
            val manager = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "geofence_alert", "围栏越界告警", android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "老人离开围栏区域时接收告警"
                    enableVibration(true)
                    enableLights(true)
                }
                manager.createNotificationChannel(channel)
            }

            val fenceNames = event.breaches.joinToString("、")
            val timeStr = if (event.timestamp > 0) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(event.timestamp))
            } else ""

            val title = "⚠️ ${event.elderName}已离开围栏区域${if (timeStr.isNotEmpty()) " ($timeStr)" else ""}"

            // 【新增】点击跳转到地图查看位置
            val intent = android.content.Intent(requireContext(), MapActivity::class.java).apply {
                putExtra("mode", "view")
                putExtra("elderId", event.elderId)
            }
            val contentIntent = android.app.PendingIntent.getActivity(
                requireContext(), 1, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notif = NotificationCompat.Builder(requireContext(), "geofence_alert")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(fenceNames)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()
            // 用 elderId + timestamp 作为通知ID，防止多条围栏通知互相覆盖
            val notifId = (event.elderId + event.timestamp.toString()).hashCode()
            manager.notify(notifId, notif)

            android.widget.Toast.makeText(requireContext(), "$title\n$fenceNames", android.widget.Toast.LENGTH_LONG).show()

            // 【新增】保存到数据库（供通知记录页面显示）
            val db = AppDatabase.getInstance(requireContext())
            viewLifecycleOwner.lifecycleScope.launch {
                db.geofenceNotificationDao().insert(GeofenceNotification(
                    elderId = event.elderId,
                    elderName = event.elderName,
                    breaches = fenceNames,
                    timestamp = event.timestamp,
                    isRead = false
                ))
                Log.i("HomeFragment", "围栏通知已保存到数据库: $fenceNames")
            }
        } catch (e: Exception) {
            Log.e("HomeFragment", "围栏通知发送失败: ${e.message}")
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到页面刷新状态（按需，不轮询）
        updateUI()
    }

    // ==================== v21: WS 实时事件监听 ====================

    private var wsListenerJob: kotlinx.coroutines.Job? = null

    private fun startWSListener() {
        // 连接 WS
        // WSClient.connect(requireContext())  // 已在 FamilyGuardianApp 统一调用

        // 监听 WS 事件
        wsListenerJob?.cancel()
        wsListenerJob = viewLifecycleOwner.lifecycleScope.launch {
            WSClient.events.collect { event ->
                when (event) {
                    is WSClient.WSEvent.FallEvent -> {
                        Log.i("HomeFragment", "🔴 WS收到跌倒事件: eventId=${event.eventId}")
                        // 保存到本地并发系统通知
                        val notification = FallNotification(
                            eventId = event.eventId,
                            elderName = CloudBaseClient.getElderName(),
                            timestamp = event.timestamp,
                            latitude = event.latitude,
                            longitude = event.longitude,
                            impactG = event.impactG,
                            mlScore = event.mlScore,
                            isRead = false,
                            isHandled = false,
                            // v0.47: 完整检测数据
                            ffDuration = event.ffDuration,
                            physicalScore = event.physicalScore,
                            weightedScore = event.weightedScore,
                            decisionPath = event.decisionPath,
                            sensorDataJson = event.sensorDataJson,
                            feedRate = event.feedRate
                        )
                        val db = AppDatabase.getInstance(requireContext())
                        val existing = db.fallNotificationDao().getByEventId(event.eventId)
                        if (existing == null) {
                            db.fallNotificationDao().insert(notification)
                            showFallNotification(notification)
                        }
                        // 刷新UI
                        loadElderStatus()
                    }

                    is WSClient.WSEvent.LocationUpdate -> {
                        Log.i("HomeFragment", "📍 WS收到位置更新")
                        // 刷新UI显示新位置
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            loadElderStatus()
                        }
                    }

                    is WSClient.WSEvent.GeofenceBreach -> {
                        Log.i("HomeFragment", "⚠️ WS收到围栏越界告警: ${event.breaches}")
                        showGeofenceBreachNotification(event)
                        loadElderStatus()
                    }

                    else -> { /* 其他事件由 RemoteAssistManager 处理 */ }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        wsListenerJob?.cancel()
        wsListenerJob = null
        _binding = null
    }
}
