package com.familyguardian.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.familyguardian.app.databinding.FragmentHistoryBinding

class HistoryFragment : Fragment() {
    
    private var _binding: FragmentHistoryBinding? = null
    private val b get() = _binding ?: throw IllegalStateException("View destroyed")
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return b.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // TODO: 加载跌倒历史
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
