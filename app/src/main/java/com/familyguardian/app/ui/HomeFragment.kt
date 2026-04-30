package com.familyguardian.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment() {
    
    private var _binding: FragmentHomeBinding? = null
    private val b get() = _binding ?: throw IllegalStateException("View destroyed")
    
    private val elderPhone = "13800138000" // TODO: 从绑定数据获取
    private val elderName = "爸爸"        // TODO: 从绑定数据获取
    
    // 位置权限
    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.entries.all { it.value }
        if (granted) {
            openMap()
        } else {
            Toast.makeText(requireContext(), "需要位置权限才能查看位置", Toast.LENGTH_SHORT).show()
        }
    }
    
    // 拨打电话权限
    private val callPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            makeCall()
        } else {
            Toast.makeText(requireContext(), "需要电话权限才能拨打电话", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return b.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 初始化UI
        b.tvElderName.text = elderName
        b.tvStatus.text = "状态正常"
        b.tvLastUpdate.text = "最后更新：刚刚"
        
        // 绑定按钮
        b.btnViewLocation.setOnClickListener { onViewLocation() }
        b.btnCallElder.setOnClickListener { onCallElder() }
        
        // 加载老人状态
        loadElderStatus()
    }
    
    private fun loadElderStatus() {
        if (!CloudBaseClient.hasBoundElder()) {
            b.tvElderName.text = "未绑定老人"
            b.tvStatus.text = "请点击「设置」绑定老人设备"
            b.tvLastUpdate.visibility = View.GONE
            b.btnViewLocation.isEnabled = false
            b.btnCallElder.isEnabled = false
            return
        }
        
        lifecycleScope.launch {
            val status = CloudBaseClient.getElderStatus()
            if (status != null) {
                b.tvElderName.text = status.name.ifEmpty { elderName }
                
                val statusText = if (status.status == "fallen") "⚠️ 跌倒报警！" else "状态正常"
                b.tvStatus.text = statusText
                b.tvStatus.setTextColor(
                    ContextCompat.getColor(
                        requireContext(),
                        if (status.status == "fallen") android.R.color.holo_red_dark
                        else android.R.color.holo_green_dark
                    )
                )
                
                val timeStr = formatTime(status.lastUpdate)
                b.tvLastUpdate.text = "最后更新：$timeStr"
            } else {
                b.tvStatus.text = "获取状态失败"
            }
        }
    }
    
    private fun onViewLocation() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查位置权限
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (permissions.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }) {
            openMap()
        } else {
            locationPermission.launch(permissions)
        }
    }
    
    private fun openMap() {
        lifecycleScope.launch {
            val status = CloudBaseClient.getElderStatus()
            val location = status?.lastLocation
            
            if (location != null) {
                val uri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(requireContext().packageManager) != null) {
                    startActivity(intent)
                } else {
                    // 没有地图应用，复制坐标
                    val coord = "${location.latitude}, ${location.longitude}"
                    android.content.ClipboardManager::class.java
                    val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("坐标", coord))
                    Toast.makeText(requireContext(), "位置：$coord（已复制）", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), "暂无位置数据", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun onCallElder() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            makeCall()
        } else {
            callPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }
    
    private fun makeCall() {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$elderPhone"))
        startActivity(intent)
    }
    
    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3600_000 -> "${diff / 60_000}分钟前"
            diff < 86400_000 -> "${diff / 3600_000}小时前"
            else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 每次回到页面刷新状态
        if (CloudBaseClient.hasBoundElder()) {
            loadElderStatus()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}