package com.familyguardian.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.data.GeofenceNotification
import com.familyguardian.app.databinding.ItemGeofenceBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 围栏通知列表适配器 — 短信风格
 */
class GeofenceNotificationAdapter(
    private val notifications: List<GeofenceNotification>,
    private val onClick: (GeofenceNotification) -> Unit
) : RecyclerView.Adapter<GeofenceNotificationAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemGeofenceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGeofenceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val notification = notifications[position]
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

        holder.binding.apply {
            tvName.text = sdf.format(Date(notification.timestamp))
            tvDetail.text = "⚠️ ${notification.elderName} 离开 ${notification.breaches}"
            root.setOnClickListener { onClick(notification) }
            // 未读背景高亮（简化处理，围栏通知不做未读高亮）
            root.setBackgroundColor(0x00000000)
        }
    }

    override fun getItemCount() = notifications.size
}
