package com.familyguardian.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.cloud.CloudBaseClient.GeofenceInfo

/**
 * 电子围栏列表适配器 v2.0
 * - 点击：查看地图
 * - 长按：操作菜单
 */
class GeofenceAdapter(
    private val fences: List<GeofenceInfo>,
    private val onClick: (GeofenceInfo) -> Unit,
    private val onLongClick: (GeofenceInfo) -> Unit
) : RecyclerView.Adapter<GeofenceAdapter.FenceViewHolder>() {
    
    class FenceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(android.R.id.title)
        val tvDetail: TextView = view.findViewById(android.R.id.summary)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FenceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        view.setPadding(32, 24, 32, 24)
        return FenceViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: FenceViewHolder, position: Int) {
        val fence = fences[position]
        
        // 名称 + 状态图标
        val statusIcon = if (fence.isBreached) "⚠️" else "✅"
        holder.tvName.text = "📍 ${fence.name}  ${fence.radius}m  $statusIcon"
        holder.tvName.textSize = 18f
        
        // 详情：坐标
        holder.tvDetail.text = String.format("%.4f, %.4f", fence.latitude, fence.longitude)
        holder.tvDetail.textSize = 14f
        
        // 越界标红（用 ?android:attr/textColorPrimary 等价，安全）
        val textColor = if (fence.isBreached) 0xFFB71C1C.toInt() else 0xFF333333.toInt()
        holder.tvName.setTextColor(textColor)
        
        // 点击 → 查看地图
        holder.itemView.setOnClickListener { onClick(fence) }
        // 长按 → 操作菜单
        holder.itemView.setOnLongClickListener { 
            onLongClick(fence)
            true
        }
    }
    
    override fun getItemCount() = fences.size
}
