package com.familyguardian.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.FragmentSettingsBinding

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
        
        // 权限设置
        b.btnPermissionSettings.setOnClickListener { openPermissionSettings() }
        
        // 解绑按钮
        b.btnUnbind.setOnClickListener { showUnbindConfirm() }
        
        // 关于按钮
        b.btnAbout.setOnClickListener { showAbout() }
        
        // 更新绑定状态显示
        updateBindingStatus()
    }
    
    private fun openPermissionSettings() {
        startActivity(Intent(requireContext(), PermissionActivity::class.java))
    }
    
    private fun updateBindingStatus() {
        val hasBound = CloudBaseClient.hasBoundElder()
        val context = context ?: return
        
        if (hasBound) {
            b.tvBindingInfo.text = "已绑定老人（ID: ${CloudBaseClient.getElderId()?.take(8)}...）"
            b.tvBindingInfo.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
            b.btnUnbind.visibility = View.VISIBLE
        } else {
            b.tvBindingInfo.text = "未绑定老人，请在首页绑定"
            b.tvBindingInfo.setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            b.btnUnbind.visibility = View.GONE
        }
    }
    
    private fun showUnbindConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle("确认解绑")
            .setMessage("解绑后将无法接收老人的跌倒报警，确定要解绑吗？")
            .setPositiveButton("解绑") { _, _ ->
                CloudBaseClient.unbindElder()
                Toast.makeText(requireContext(), "已解绑", Toast.LENGTH_SHORT).show()
                updateBindingStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    private fun showAbout() {
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName
        } catch (e: Exception) { "未知" }
        
        AlertDialog.Builder(requireContext())
            .setTitle("关于亲情守护")
            .setMessage("""
                亲情守护 v$version
                
                守护老人安全，及时接收跌倒报警通知。
                
                后端服务：腾讯云 CloudBase
                环境ID：diedaobao-cdn-d4g496tvv296f0ac2
            """.trimIndent())
            .setPositiveButton("确定", null)
            .show()
    }
    
    override fun onResume() {
        super.onResume()
        updateBindingStatus()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
