package com.familyguardian.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.data.AppDatabase
import com.familyguardian.app.data.FallNotification
import com.familyguardian.app.data.GeofenceNotification
import com.familyguardian.app.databinding.FragmentHistoryBinding
import com.familyguardian.app.databinding.ItemFallEventBinding
import com.familyguardian.app.databinding.ItemGeofenceBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private lateinit var adapter: NotificationAdapter
    private val items = mutableListOf<NotificationItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val b = _binding ?: return

        adapter = NotificationAdapter(
            items,
            onFallClick = { onFallClick(it) },
            onGeofenceClick = { onGeofenceClick(it) }
        )
        b.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        b.rvHistory.adapter = adapter

        // 全部已读按钮（仅处理跌倒通知）
        b.btnMarkAllRead.setOnClickListener { markAllRead() }

        // 加载本地通知（跌倒 + 围栏混合）
        loadNotifications()
    }

    private fun loadNotifications() {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            combine(
                db.fallNotificationDao().getAll(),
                db.geofenceNotificationDao().getAll()
            ) { falls, geofences ->
                val fallItems = falls.map { NotificationItem.Fall(it) }
                val geofenceItems = geofences.map { NotificationItem.Geofence(it) }
                (fallItems + geofenceItems).sortedByDescending {
                    when (it) {
                        is NotificationItem.Fall -> it.n.timestamp
                        is NotificationItem.Geofence -> it.n.timestamp
                    }
                }
            }.collect { combined ->
                if (!isAdded) return@collect
                items.clear()
                items.addAll(combined)
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        val b = _binding ?: return
        if (items.isEmpty()) {
            b.tvEmpty.visibility = View.VISIBLE
            b.rvHistory.visibility = View.GONE
            b.btnMarkAllRead.visibility = View.GONE
        } else {
            b.tvEmpty.visibility = View.GONE
            b.rvHistory.visibility = View.VISIBLE
            // 全部已读：跌倒未读数>0时显示按钮
            val hasUnreadFalls = items.filterIsInstance<NotificationItem.Fall>().any { !it.n.isRead }
            b.btnMarkAllRead.visibility = if (hasUnreadFalls) View.VISIBLE else View.GONE
        }
    }

    private fun onFallClick(notification: FallNotification) {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.fallNotificationDao().markRead(notification.id)
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val timeStr = sdf.format(Date(notification.timestamp))
        val locationStr = if (notification.latitude != null && notification.longitude != null) {
            String.format("%.4f, %.4f", notification.latitude, notification.longitude)
        } else "未知"

        val message = buildString {
            append("⏰ $timeStr\n")
            append("👤 ${notification.elderName}\n")
            append("📍 位置: $locationStr")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🚨 跌倒警告")
            .setMessage(message)
            .setPositiveButton("📍 查看位置") { _, _ ->
                if (notification.latitude != null && notification.longitude != null) {
                    val intent = Intent(requireContext(), MapActivity::class.java).apply {
                        putExtra("mode", "view_fall")
                        putExtra("fallLat", notification.latitude)
                        putExtra("fallLng", notification.longitude)
                        putExtra("fallTime", timeStr)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(requireContext(), "未记录位置信息", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun onGeofenceClick(notification: GeofenceNotification) {
        val db = AppDatabase.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            db.geofenceNotificationDao().markRead(notification.id)
        }

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val timeStr = sdf.format(Date(notification.timestamp))

        AlertDialog.Builder(requireContext())
            .setTitle("⚠️ 围栏越界告警")
            .setMessage(buildString {
                append("⏰ $timeStr\n")
                append("👤 ${notification.elderName}\n")
                append("📍 离开区域: ${notification.breaches}")
            })
            .setPositiveButton("📍 查看位置") { _, _ ->
                val intent = Intent(requireContext(), MapActivity::class.java).apply {
                    putExtra("mode", "view_location")
                    putExtra("elderId", notification.elderId)
                }
                startActivity(intent)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

// ========== 统一通知项（跌倒 + 围栏混合列表）==========

sealed class NotificationItem {
    abstract val id: Long
    abstract val timestamp: Long
    abstract val elderName: String

    data class Fall(val n: FallNotification) : NotificationItem() {
        override val id = n.id
        override val timestamp = n.timestamp
        override val elderName = n.elderName
    }
    data class Geofence(val n: GeofenceNotification) : NotificationItem() {
        override val id = n.id
        override val timestamp = n.timestamp
        override val elderName = n.elderName
    }
}

class NotificationAdapter(
    private val items: List<NotificationItem>,
    private val onFallClick: (FallNotification) -> Unit,
    private val onGeofenceClick: (GeofenceNotification) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_FALL = 0
        const val TYPE_GEOFENCE = 1
    }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is NotificationItem.Fall -> TYPE_FALL
        is NotificationItem.Geofence -> TYPE_GEOFENCE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_FALL -> {
                val binding = ItemFallEventBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                FallViewHolder(binding) { onFallClick(it) }
            }
            else -> {
                val binding = ItemGeofenceBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                GeofenceViewHolder(binding) { onGeofenceClick(it) }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NotificationItem.Fall -> (holder as FallViewHolder).bind(item.n)
            is NotificationItem.Geofence -> (holder as GeofenceViewHolder).bind(item.n)
        }
    }

    override fun getItemCount() = items.size

    class FallViewHolder(
        val binding: ItemFallEventBinding,
        private val onClick: (FallNotification) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        fun bind(n: FallNotification) {
            binding.apply {
                val isUnread = !n.isRead
                tvTime.text = sdf.format(Date(n.timestamp))
                tvTime.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                tvLocation.text = "${n.elderName} · 跌倒警告"
                tvLocation.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                tvImpact.text = "ML: ${"%.0f".format(n.mlScore * 100)}%"
                tvMlScore.text = if (n.latitude != null && n.longitude != null) "📍有位置" else "📍无位置"

                tvStatus.text = when {
                    !n.isHandled -> "🔴 待处理"
                    else -> "✅ 已处理"
                }
                tvStatus.setTypeface(null, if (isUnread) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                root.setOnClickListener { onClick(n) }
                root.setBackgroundColor(if (isUnread) 0x10FF0000 else 0x00000000)
            }
        }
    }

    // 围栏通知视图
    class GeofenceViewHolder(
        val binding: ItemGeofenceBinding,
        private val onClick: (GeofenceNotification) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        private val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        fun bind(n: GeofenceNotification) {
            binding.apply {
                tvName.text = sdf.format(Date(n.timestamp))
                tvDetail.text = "⚠️ ${n.elderName} 离开 ${n.breaches}"
                root.setOnClickListener { onClick(n) }
                root.setBackgroundColor(0x00000000)
            }
        }
    }
}
