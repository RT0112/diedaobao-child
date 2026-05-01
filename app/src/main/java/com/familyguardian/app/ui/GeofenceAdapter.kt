package com.familyguardian.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.familyguardian.app.cloud.CloudBaseClient.GeofenceInfo

/**
 * 电子围栏列表适配器
 */
class GeofenceAdapter(
    private val fences: List<GeofenceInfo>,
    private val onClick: (GeofenceInfo) -> Unit
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
        holder.tvName.text = "📍 ${fence.name}"
        holder.tvName.textSize = 18f
        holder.tvDetail.text = "中心: ${String.format("%.4f", fence.latitude)}, ${String.format("%.4f", fence.longitude)} | 半径: ${fence.radius}米"
        holder.tvDetail.textSize = 14f
        
        if (fence.isBreached) {
            holder.tvName.setTextColor(0xFFF44336.toInt())
        } else {
            holder.tvName.setTextColor(0xFF333333.toInt())
        }
        
        holder.itemView.setOnClickListener { onClick(fence) }
    }
    
    override fun getItemCount() = fences.size
}
