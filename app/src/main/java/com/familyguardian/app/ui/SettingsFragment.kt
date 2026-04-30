package com.familyguardian.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {
    
    private var _binding: FragmentSettingsBinding? = null
    private val b get() = _binding ?: throw IllegalStateException("View destroyed")
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 绑定按钮
        b.btnBind.setOnClickListener { onBind() }
        
        // 关于按钮
        b.btnAbout.setOnClickListener { showAbout() }
        
        // 更新UI显示绑定状态
        updateBindingStatus()
    }
    
    private fun updateBindingStatus() {
        val hasBound = CloudBaseClient.hasBoundElder()
        b.etBindCode.hint = if (hasBound) "已绑定（输入新码替换）" else "输入绑定码"
        b.btnBind.text = if (hasBound) "更换绑定" else "绑定"
    }
    
    private fun onBind() {
        val code = b.etBindCode.text.toString().trim()
        if (code.isEmpty()) {
            Toast.makeText(requireContext(), "请输入绑定码", Toast.LENGTH_SHORT).show()
            return
        }
        
        b.btnBind.isEnabled = false
        b.btnBind.text = "绑定中..."
        
        lifecycleScope.launch {
            val success = CloudBaseClient.bindElder(requireContext(), code)
            
            if (success) {
                Toast.makeText(requireContext(), "绑定成功", Toast.LENGTH_SHORT).show()
                b.etBindCode.text.clear()
            } else {
                Toast.makeText(requireContext(), "绑定失败：无效的绑定码", Toast.LENGTH_SHORT).show()
            }
            
            b.btnBind.isEnabled = true
            updateBindingStatus()
        }
    }
    
    private fun showAbout() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("关于亲情守护")
            .setMessage("""
                亲情守护 v0.1.0
                
                守护老人安全，及时接收跌倒报警通知。
                
                后端服务：腾讯云 CloudBase
                环境ID：diedaobao-cdn-d4g496tvv296f0ac2
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}