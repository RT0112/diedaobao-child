package com.familyguardian.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.familyguardian.app.cloud.CloudBaseClient
import com.familyguardian.app.databinding.FragmentHistoryBinding
import kotlinx.coroutines.launch

class HistoryFragment : Fragment() {
    
    private var _binding: FragmentHistoryBinding? = null
    
    private lateinit var adapter: FallEventAdapter
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return _binding!!.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val b = _binding ?: return
        
        // 设置 RecyclerView
        adapter = FallEventAdapter()
        b.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        b.rvHistory.adapter = adapter
        
        // 加载数据
        loadHistory()
        
        // 下拉刷新（简单实现）
        b.rvHistory.setOnClickListener { loadHistory() }
    }
    
    private fun loadHistory() {
        if (!CloudBaseClient.hasBoundElder()) {
            adapter.submitList(emptyList())
            Toast.makeText(requireContext(), "请先绑定老人设备", Toast.LENGTH_SHORT).show()
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                
                val events = CloudBaseClient.getFallHistory(30)
                if (!isAdded) return@launch
                
                _binding?.let { b ->
                    if (events.isEmpty()) {
                        adapter.submitList(emptyList())
                        Toast.makeText(requireContext(), "暂无跌倒记录", Toast.LENGTH_SHORT).show()
                    } else {
                        adapter.submitList(events)
                    }
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "加载历史失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        if (CloudBaseClient.hasBoundElder()) {
            loadHistory()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}