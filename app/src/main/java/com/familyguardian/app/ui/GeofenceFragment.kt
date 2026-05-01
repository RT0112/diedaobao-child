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
 * 电子围栏页面 v2.0 (v0.6.4 修复版)
 * - 最高防御级别：所有binding访问都在guard下
 * - 使用 viewLifecycleOwner.lifecycleScope
 * - 所有网络操作加 try-catch
 */
class GeofenceFragment : Fragment() {
    
    private var _binding: FragmentGeofenceBinding? = null
    
    private lateinit var adapter: GeofenceAdapter
    private val fences = mutableListOf<GeofenceInfo>()
    
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
            onItemClick = { fence -> openMapForView(fence) },
            onItemLongClick = { fence -> showFenceOptions(fence) }
        )
        
        b.recyclerFences.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerFences.adapter = adapter
        
        // 添加围栏 → 打开地图（添加模式）
        b.btnAddFence.setOnClickListener { openMapForAdd() }
        
        // 刷新
        b.swipeRefresh.setOnRefreshListener { 
            if (isAdded) loadFences() 
        }
        
        // 加载围栏
        if (isAdded) loadFences()
    }
    
    private fun loadFences() {
        // 安全检查
        if (!isAdded || _binding == null) return
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
                if (!isAdded) return@launch
                
                fences.clear()
                val loaded = CloudBaseClient.getGeofences()
                fences.addAll(loaded)
                
                if (!isAdded) return@launch
                _binding?.let { b ->
                    adapter.notifyDataSetChanged()
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
                if (isAdded) {
                    _binding?.swipeRefresh?.isRefreshing = false
                    Toast.makeText(requireContext(), "加载围栏失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 打开地图页 — 添加围栏模式
     */
    private fun openMapForAdd() {
        if (!isAdded) return
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
        if (!isAdded) return
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
        if (!isAdded) return
        val options = arrayOf("📍 在地图上查看", "🗑️ 删除围栏")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(fence.name)
            .setItems(options) { _, which ->
                if (!isAdded) return@setItems
                when (which) {
                    0 -> openMapForView(fence)
                    1 -> confirmDelete(fence)
                }
            }
            .show()
    }
    
    private fun confirmDelete(fence: GeofenceInfo) {
        if (!isAdded) return
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("确认删除")
            .setMessage("确定要删除围栏「${fence.name}」吗？")
            .setPositiveButton("删除") { _, _ -> 
                if (isAdded) deleteFence(fence) 
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun deleteFence(fence: GeofenceInfo) {
        if (!isAdded) return
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                
                val success = CloudBaseClient.deleteGeofence(fence.id)
                if (!isAdded) return@launch
                
                if (success) {
                    Toast.makeText(requireContext(), "围栏「${fence.name}」已删除", Toast.LENGTH_SHORT).show()
                    loadFences()
                } else {
                    Toast.makeText(requireContext(), "删除失败，请重试", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "删除失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (isAdded) loadFences()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}