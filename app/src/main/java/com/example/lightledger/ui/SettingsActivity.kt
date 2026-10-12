package com.example.lightledger.ui

import android.os.Bundle
import com.example.lightledger.R
import com.example.lightledger.databinding.ActivitySettingsBinding

class SettingsActivity : LedgerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.goBack.setOnClickListener { finish() }
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, SettingsFragment()).commit()
    }
}
