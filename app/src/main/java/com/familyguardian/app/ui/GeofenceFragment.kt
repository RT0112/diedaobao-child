package com.familyguardian.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.cloud.CloudBaseClient.GeofenceInfo
import com.familyguardian.app.databinding.FragmentGeofenceBinding
import kotlinx.coroutines.launch

/**
 * 电子围栏页面
 * - 显示围栏列表
 * - 添加/删除围栏
 * - 围栏告警记录
 */
class GeofenceFragment : Fragment() {
    
    private var _binding: FragmentGeofenceBinding? = null
    private val b get() = _binding ?: throw IllegalStateException("View destroyed")
    
    private lateinit var adapter: GeofenceAdapter
    private var fences = mutableListOf<GeofenceInfo>()
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeofenceBinding.inflate(inflater, container, false)
        return b.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        adapter = GeofenceAdapter(fences) { fence ->
            showFenceOptions(fence)
        }
        
        b.recyclerFences.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerFences.adapter = adapter
        
        // 添加围栏
        b.btnAddFence.setOnClickListener { showAddFenceDialog() }
        
        // 刷新
        b.swipeRefresh.setOnRefreshListener { loadFences() }
        
        // 加载围栏
        loadFences()
    }
    
    private fun loadFences() {
        if (!CloudBaseClient.hasBoundElder()) {
            b.layoutEmpty.visibility = View.VISIBLE
            b.recyclerFences.visibility = View.GONE
            b.tvEmptyHint.text = "请先绑定老人设备"
            b.swipeRefresh.isRefreshing = false
            return
        }
        
        lifecycleScope.launch {
            fences.clear()
            val loaded = CloudBaseClient.getGeofences()
            fences.addAll(loaded)
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
    }
    
    private fun showAddFenceDialog() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dialogView = LayoutInflater.from(requireContext()).inflate(
            android.R.layout.activity_list_item, null  // 临时用系统布局
        )
        
        // 自定义对话框
        val nameEdit = EditText(requireContext()).apply { hint = "围栏名称（如：家、公园）" }
        val radiusEdit = EditText(requireContext()).apply {
            hint = "半径（米，50-2000）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val latEdit = EditText(requireContext()).apply {
            hint = "纬度（可选，默认老人当前位置）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        val lngEdit = EditText(requireContext()).apply {
            hint = "经度（可选，默认老人当前位置）"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        
        val layout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 30, 50, 10)
            addView(nameEdit)
            addView(radiusEdit)
            addView(latEdit)
            addView(lngEdit)
        }
        
        AlertDialog.Builder(requireContext())
            .setTitle("添加电子围栏")
            .setView(layout)
            .setPositiveButton("添加") { _, _ ->
                val name = nameEdit.text.toString().trim().ifEmpty { "围栏" }
                val radius = radiusEdit.text.toString().trim().toIntOrNull() ?: 200
                val lat = latEdit.text.toString().trim().toDoubleOrNull()
                val lng = lngEdit.text.toString().trim().toDoubleOrNull()
                
                if (radius < 50 || radius > 2000) {
                    Toast.makeText(requireContext(), "半径范围：50-2000米", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                addFence(name, lat, lng, radius)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun addFence(name: String, lat: Double?, lng: Double?, radius: Int) {
        lifecycleScope.launch {
            // 如果没有提供坐标，使用老人当前位置
            var fenceLat = lat
            var fenceLng = lng
            
            if (fenceLat == null || fenceLng == null) {
                val status = CloudBaseClient.getElderStatus()
                val loc = status?.lastLocation
                if (loc != null) {
                    fenceLat = loc.latitude
                    fenceLng = loc.longitude
                } else {
                    Toast.makeText(requireContext(), "无法获取老人当前位置，请手动输入坐标", Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            
            val success = CloudBaseClient.addGeofence(name, fenceLat, fenceLng, radius)
            if (success) {
                Toast.makeText(requireContext(), "围栏「$name」已添加", Toast.LENGTH_SHORT).show()
                loadFences()
            } else {
                Toast.makeText(requireContext(), "添加围栏失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showFenceOptions(fence: GeofenceInfo) {
        val options = arrayOf("在地图上查看", "删除围栏")
        AlertDialog.Builder(requireContext())
            .setTitle(fence.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // 打开地图
                        val intent = android.content.Intent(requireContext(), MapActivity::class.java)
                        startActivity(intent)
                    }
                    1 -> {
                        AlertDialog.Builder(requireContext())
                            .setTitle("确认删除")
                            .setMessage("确定要删除围栏「${fence.name}」吗？")
                            .setPositiveButton("删除") { _, _ ->
                                deleteFence(fence)
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            }
            .show()
    }
    
    private fun deleteFence(fence: GeofenceInfo) {
        lifecycleScope.launch {
            val success = CloudBaseClient.deleteGeofence(fence.id)
            if (success) {
                Toast.makeText(requireContext(), "围栏已删除", Toast.LENGTH_SHORT).show()
                loadFences()
            } else {
                Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
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
