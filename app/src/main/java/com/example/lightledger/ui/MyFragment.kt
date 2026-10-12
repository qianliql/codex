package com.example.lightledger.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.lightledger.databinding.FragmentMyBinding
import com.example.lightledger.update.InstalledVersion

class MyFragment : Fragment() {
    private var binding: FragmentMyBinding? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val b = FragmentMyBinding.inflate(inflater, container, false)
        binding = b
        b.themeSettings.setOnClickListener { startActivity(Intent(requireContext(), ThemeActivity::class.java)) }
        b.manageCategories.setOnClickListener { startActivity(Intent(requireContext(), CategoryActivity::class.java)) }
        b.manageTags.setOnClickListener { startActivity(Intent(requireContext(), TagsActivity::class.java)) }
        b.manageMerchantRules.setOnClickListener { startActivity(Intent(requireContext(), MerchantRulesActivity::class.java)) }
        b.openSettings.setOnClickListener { startActivity(Intent(requireContext(), SettingsActivity::class.java)) }
        b.appVersion.text = "轻账本 ${InstalledVersion.read(requireContext()).name}"
        return b.root
    }
    override fun onResume() {
        super.onResume()
        binding?.themeSettings?.text = "主题配色 · ${LedgerThemes.selected(requireContext()).label}"
    }
    override fun onDestroyView() { binding = null; super.onDestroyView() }
}
