package com.familyguardian.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.ItemFallEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FallEventAdapter : ListAdapter<CloudBaseClient.FallEvent, FallEventAdapter.ViewHolder>(DiffCallback()) {
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFallEventBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
    
    class ViewHolder(private val binding: ItemFallEventBinding) : RecyclerView.ViewHolder(binding.root) {
        private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
        
        fun bind(event: CloudBaseClient.FallEvent) {
            val timeStr = dateFormat.format(Date(event.timestamp))
            binding.tvTime.text = timeStr
            
            val locationStr = if (event.latitude != null && event.longitude != null) {
                String.format("%.4f, %.4f", event.latitude, event.longitude)
            } else {
                "未知位置"
            }
            binding.tvLocation.text = locationStr
            
            binding.tvImpact.text = String.format("冲击: %.1fg", event.impactG)
            binding.tvMlScore.text = String.format("ML: %.0f%%", event.mlScore * 100)
            
            // 状态标签
            binding.tvStatus.text = when {
                event.mlScore > 0.8 -> "🔴 高风险"
                event.mlScore > 0.5 -> "🟡 中风险"
                else -> "🟢 低风险"
            }
        }
    }
    
    class DiffCallback : DiffUtil.ItemCallback<CloudBaseClient.FallEvent>() {
        override fun areItemsTheSame(oldItem: CloudBaseClient.FallEvent, newItem: CloudBaseClient.FallEvent): Boolean {
            return oldItem.eventId == newItem.eventId
        }
        
        override fun areContentsTheSame(oldItem: CloudBaseClient.FallEvent, newItem: CloudBaseClient.FallEvent): Boolean {
            return oldItem == newItem
        }
    }
}