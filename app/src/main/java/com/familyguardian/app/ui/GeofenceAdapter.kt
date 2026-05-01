package com.familyguardian.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.cloud.CloudBaseClient.GeofenceInfo
import com.familyguardian.app.databinding.ItemGeofenceBinding

/**
 * 电子围栏列表适配器 v2.1
 * - 自定义布局 item_geofence.xml，替代不稳定的 android.R.layout.simple_list_item_2
 * - 点击：查看地图 / 长按：操作菜单
 * - 使用 viewBinding，安全
 */
class GeofenceAdapter(
    private val fences: List<GeofenceInfo>,
    private val onItemClick: (GeofenceInfo) -> Unit,
    private val onItemLongClick: (GeofenceInfo) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.FenceViewHolder>() {
    
    class FenceViewHolder(
        private val binding: ItemGeofenceBinding,
        private val onClick: (GeofenceInfo) -> Unit,
        private val onLongClick: (GeofenceInfo) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {
        
        private var fence: GeofenceInfo? = null
        
        init {
            binding.root.setOnClickListener {
                fence?.let { onClick(it) }
            }
            binding.root.setOnLongClickListener {
                fence?.let { onLongClick(it) }
                true
            }
        }
        
        fun bind(item: GeofenceInfo) {
            fence = item
            // 名称 + 状态图标
            val statusIcon = if (item.isBreached) "⚠️" else "✅"
            binding.tvName.text = "📍 ${item.name}  ${item.radius}m  $statusIcon"
            
            // 详情：坐标
            binding.tvDetail.text = String.format("%.4f, %.4f", item.latitude, item.longitude)
            
            // 越界标红（硬编码颜色避免R引用问题）
            val textColor = if (item.isBreached) {
                0xFFF44336.toInt() // danger red
            } else {
                0xFF666666.toInt() // text_secondary
            }
            binding.tvName.setTextColor(if (item.isBreached) 0xFFF44336.toInt() else 0xFF666666.toInt())
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FenceViewHolder {
        val binding = ItemGeofenceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return FenceViewHolder(binding, onItemClick, onItemLongClick)
    }
    
    override fun onBindViewHolder(holder: FenceViewHolder, position: Int) {
        holder.bind(fences[position])
    }
    
    override fun getItemCount() = fences.size
}