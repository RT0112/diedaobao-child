package com.familyguardian.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
    
    private val elderName by lazy { CloudBaseClient.getElderName() }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return _binding!!.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val b = _binding ?: return
        
        // 绑定按钮
        b.btnBind.setOnClickListener { onBindClick() }
        
        // 查看位置按钮 → 打开地图Activity
        b.btnViewLocation.setOnClickListener { onViewLocation() }
        
        // 首次启动自动注册
        ensureRegistered()
        
        // 更新UI状态
        updateUI()
    }
    
    /**
     * 确保已注册（首次启动自动注册）
     */
    private fun ensureRegistered() {
        if (!CloudBaseClient.isRegistered()) {
            lifecycleScope.launch {
                val success = CloudBaseClient.autoRegister(requireContext())
                if (!success) {
                    Toast.makeText(requireContext(), "网络注册失败，部分功能不可用", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 更新UI状态（绑定/未绑定）
     */
    private fun updateUI() {
        val b = _binding ?: return
        val hasBound = CloudBaseClient.hasBoundElder()
        
        if (hasBound) {
            b.cardBindElder.visibility = View.GONE
            b.cardElderStatus.visibility = View.VISIBLE
            b.layoutActions.visibility = View.VISIBLE
            loadElderStatus()
        } else {
            b.cardBindElder.visibility = View.VISIBLE
            b.cardElderStatus.visibility = View.GONE
            b.layoutActions.visibility = View.GONE
        }
    }
    
    /**
     * 绑定按钮点击
     */
    private fun onBindClick() {
        val b = _binding ?: return
        val code = b.etBindCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(requireContext(), "请输入绑定码", Toast.LENGTH_SHORT).show()
            return
        }
        if (code.length != 6) {
            showBindError("绑定码为6位数字")
            return
        }
        
        // 先确保已注册
        if (!CloudBaseClient.isRegistered()) {
            lifecycleScope.launch {
                val registered = CloudBaseClient.autoRegister(requireContext())
                if (!registered) {
                    showBindError("注册失败，请检查网络")
                    return@launch
                }
                doBind(code)
            }
        } else {
            doBind(code)
        }
    }
    
    private fun doBind(code: String) {
        val b = _binding ?: return
        b.btnBind.isEnabled = false
        b.btnBind.text = "绑定中..."
        b.tvBindError.visibility = View.GONE
        
        lifecycleScope.launch {
            val result = CloudBaseClient.bindElder(code)
            
            _binding?.let { b ->
                b.btnBind.isEnabled = true
                b.btnBind.text = "绑定"
                
                if (result.success) {
                    Toast.makeText(requireContext(), "绑定成功！", Toast.LENGTH_SHORT).show()
                    b.etBindCode.text?.clear()
                    updateUI()
                } else {
                    showBindError(result.message)
                }
            }
        }
    }
    
    private fun showBindError(message: String) {
        _binding?.let { b ->
            b.tvBindError.text = message
            b.tvBindError.visibility = View.VISIBLE
        }
    }
    
    private fun loadElderStatus() {
        if (!CloudBaseClient.hasBoundElder()) return
        
        lifecycleScope.launch {
            val status = CloudBaseClient.getElderStatus()
            _binding?.let { b ->
                if (status != null) {
                    b.tvElderName.text = status.name.ifEmpty { elderName }
                    
                    // 保存老人信息
                    CloudBaseClient.saveElderInfo(status.name, null)
                    
                    val statusText = if (status.status == "fallen") "⚠️ 跌倒报警！" else "状态正常 ✅"
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
                    
                    // 位置状态
                    b.btnViewLocation.text = "📍 查看位置"
                } else {
                    b.tvElderName.text = elderName
                    b.tvStatus.text = "获取状态失败"
                    b.tvStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
                }
            }
        }
    }
    
    /**
     * 查看位置 → 打开地图Activity
     */
    private fun onViewLocation() {
        if (!CloudBaseClient.hasBoundElder()) {
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        val intent = android.content.Intent(requireContext(), MapActivity::class.java)
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
        updateUI()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
