package com.familyguardian.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.cloud.CloudBaseClient.GeofenceInfo
import com.familyguardian.app.databinding.FragmentGeofenceBinding
import kotlinx.coroutines.launch

/**
 * 电子围栏页面 v2.0
 * - 显示围栏列表
 * - 添加围栏 → 打开地图页（拖拽画圆）
 * - 删除围栏
 * - 查看围栏 → 打开地图页（定位到围栏）
 */
class GeofenceFragment : Fragment() {
    
    private var _binding: FragmentGeofenceBinding? = null
    
    private lateinit var adapter: GeofenceAdapter
    private var fences = mutableListOf<GeofenceInfo>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeofenceBinding.inflate(inflater, container, false)
        return _binding!!.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val b = _binding ?: return
        
        adapter = GeofenceAdapter(fences, 
            onClick = { fence -> openMapForView(fence) },
            onLongClick = { fence -> showFenceOptions(fence) }
        )
        
        b.recyclerFences.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerFences.adapter = adapter
        
        // 添加围栏 → 打开地图（添加模式）
        b.btnAddFence.setOnClickListener { openMapForAdd() }
        
        // 刷新
        b.swipeRefresh.setOnRefreshListener { loadFences() }
        
        // 加载围栏
        loadFences()
    }
    
    private fun loadFences() {
        if (!CloudBaseClient.hasBoundElder()) {
            _binding?.let { b ->
                b.layoutEmpty.visibility = View.VISIBLE
                b.recyclerFences.visibility = View.GONE
                b.tvEmptyHint.text = "请先绑定老人设备"
                b.swipeRefresh.isRefreshing = false
            }
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                fences.clear()
                val loaded = CloudBaseClient.getGeofences()
                fences.addAll(loaded)
                adapter.notifyDataSetChanged()
                
                _binding?.let { b ->
                    b.swipeRefresh.isRefreshing = false
                    
                    if (fences.isEmpty()) {
                        b.layoutEmpty.visibility = View.VISIBLE
                        b.recyclerFences.visibility = View.GONE
                        b.tvEmptyHint.text = "暂无电子围栏\n点击下方按钮添加"
                    } else {
                        b.layoutEmpty.visibility = View.GONE
                        b.recyclerFences.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                _binding?.swipeRefresh?.isRefreshing = false
                Toast.makeText(requireContext(), "加载围栏失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 打开地图页 — 添加围栏模式
     */
    private fun openMapForAdd() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(requireContext(), MapActivity::class.java).apply {
            putExtra("mode", "add")
        }
        startActivity(intent)
    }
    
    /**
     * 打开地图页 — 查看单个围栏
     */
    private fun openMapForView(fence: GeofenceInfo) {
        val intent = Intent(requireContext(), MapActivity::class.java).apply {
            putExtra("mode", "view_fence")
            putExtra("fenceId", fence.id)
            putExtra("fenceName", fence.name)
            putExtra("fenceLat", fence.latitude)
            putExtra("fenceLng", fence.longitude)
            putExtra("fenceRadius", fence.radius)
        }
        startActivity(intent)
    }
    
    /**
     * 长按围栏 → 操作菜单
     */
    private fun showFenceOptions(fence: GeofenceInfo) {
        val options = arrayOf("📍 在地图上查看", "🗑️ 删除围栏")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(fence.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openMapForView(fence)
                    1 -> confirmDelete(fence)
                }
            }
            .show()
    }
    
    private fun confirmDelete(fence: GeofenceInfo) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除围栏「${fence.name}」吗？")
            .setPositiveButton("删除") { _, _ -> deleteFence(fence) }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteFence(fence: GeofenceInfo) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = CloudBaseClient.deleteGeofence(fence.id)
                if (success) {
                    Toast.makeText(requireContext(), "围栏「${fence.name}」已删除", Toast.LENGTH_SHORT).show()
                    loadFences()
                } else {
                    Toast.makeText(requireContext(), "删除失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        loadFences()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
