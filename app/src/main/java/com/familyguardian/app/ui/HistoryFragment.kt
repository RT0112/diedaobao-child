package com.familyguardian.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardian.app.data.AppDatabase
import com.familyguardian.app.data.FallNotification
import com.familyguardian.app.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private lateinit var adapter: FallNotificationAdapter
    private val notifications = mutableListOf<FallNotification>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = _binding ?: return

        adapter = FallNotificationAdapter(notifications) { notification ->
            onNotificationClick(notification)
        }
        b.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        b.rvHistory.adapter = adapter

        // 全部已读按钮
        b.btnMarkAllRead.setOnClickListener { markAllRead() }

        // 加载本地通知
        loadNotifications()
    }

    private fun loadNotifications() {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.fallNotificationDao().getAll().collect { list ->
                if (!isAdded) return@collect
                notifications.clear()
                notifications.addAll(list)
                adapter.notifyDataSetChanged()
                updateEmptyState()
                updateUnreadBadge()
            }
        }
    }

    private fun updateEmptyState() {
        val b = _binding ?: return
        if (notifications.isEmpty()) {
            b.tvEmpty.visibility = View.VISIBLE
            b.rvHistory.visibility = View.GONE
            b.btnMarkAllRead.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.rvHistory.visibility = View.VISIBLE
            val hasUnread = notifications.any { !it.isRead }
            b.btnMarkAllRead.visibility = if (hasUnread) View.VISIBLE else View.GONE
        }
    }

    private fun updateUnreadBadge() {
        // TODO: 更新底部导航Badge显示未读数
    }

    private fun onNotificationClick(notification: FallNotification) {
        val db = AppDatabase.getInstance(requireContext())

        // 标记已读
        viewLifecycleOwner.lifecycleScope.launch {
            db.fallNotificationDao().markRead(notification.id)
        }

        // 显示详情对话框
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val timeStr = sdf.format(Date(notification.timestamp))
        val locationStr = if (notification.latitude != null && notification.longitude != null) {
            String.format("%.4f, %.4f", notification.latitude, notification.longitude)
        } else "未知"

        val message = buildString {
            append("⏰ $timeStr\n")
            append("👤 ${notification.elderName}\n")
            append("💥 冲击力: ${"%.1f".format(notification.impactG)}g\n")
            append("🤖 ML置信度: ${"%.0f".format(notification.mlScore * 100)}%\n")
            append("📍 位置: $locationStr")
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("🚨 跌倒警报")
            .setMessage(message)
            .setPositiveButton("📍 查看位置") { _, _ ->
                if (notification.latitude != null && notification.longitude != null) {
                    val mapUrl = "https://uri.amap.com/marker?position=${notification.longitude},${notification.latitude}&name=跌倒位置"
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapUrl)))
                } else {
                    Toast.makeText(requireContext(), "未记录位置信息", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun markAllRead() {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.fallNotificationDao().markAllRead()
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次回来刷新（可能有新通知通过轮询写入）
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * 通知列表适配器 — 短信风格
 */
class FallNotificationAdapter(
    private val notifications: List<FallNotification>,
    private val onClick: (FallNotification) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<FallNotificationAdapter.ViewHolder>() {

    class ViewHolder(val binding: com.familyguardian.app.databinding.ItemFallEventBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = com.familyguardian.app.databinding.ItemFallEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notification = notifications[position]
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        holder.binding.apply {
            // 未读加粗/背景高亮
            val isUnread = !notification.isRead
            tvTime.text = sdf.format(Date(notification.timestamp))
            tvTime.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            // 内容行：冲击力 + ML
            tvLocation.text = "${notification.elderName} · 冲击 ${"%.1f".format(notification.impactG)}g"
            tvLocation.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            tvImpact.text = "ML: ${"%.0f".format(notification.mlScore * 100)}%"
            tvMlScore.text = if (notification.latitude != null && notification.longitude != null) "📍有位置" else "📍无位置"

            // 状态标签
            tvStatus.text = when {
                !notification.isHandled -> "🔴 待处理"
                else -> "✅ 已处理"
            }

            // 未读标记：小圆点
            tvStatus.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

            root.setOnClickListener { onClick(notification) }

            // 未读背景高亮
            root.setBackgroundColor(if (isUnread) 0x10FF0000 else 0x00000000)
        }
    }

    override fun getItemCount() = notifications.size
}
