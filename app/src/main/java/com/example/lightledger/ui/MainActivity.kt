package com.example.lightledger.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.lightledger.R
import com.example.lightledger.databinding.ActivityMainBinding
import com.example.lightledger.notification.NotificationAccess

class MainActivity : LedgerActivity() {
    private lateinit var binding: ActivityMainBinding
    private var pendingSelectionId: Long? = null
    fun selectBill(id: Long) {
        pendingSelectionId = id
        binding.bottomNavigation.selectedItemId = R.id.nav_transactions
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.enableAutomatic.setOnClickListener { NotificationAccess.openSettings(this) }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showTab(item.itemId)
            true
        }
        if (savedInstanceState == null) binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    override fun onResume() {
        super.onResume()
        NotificationAccess.reconnectIfNeeded(this)
        val enabled = NotificationAccess.isEnabled(this)
        binding.accessBanner.visibility = if (enabled) View.GONE else View.VISIBLE
        val prefs = getSharedPreferences("permission_guide", MODE_PRIVATE)
        if (!enabled && !prefs.getBoolean("shown", false)) {
            prefs.edit().putBoolean("shown", true).apply()
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("开启自动记账")
                .setMessage("允许轻账本读取微信、支付宝的支付通知，才能自动记录账单。点击后会打开系统授权页，在那里允许即可。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("去允许") { _, _ -> NotificationAccess.openSettings(this) }.show()
        }
    }

    private fun showTab(id: Int) {
        val fragment: Fragment = when (id) {
            R.id.nav_transactions -> TransactionsFragment().apply {
                pendingSelectionId?.let { arguments = Bundle().apply { putLong("select_id", it) } }
                pendingSelectionId = null
            }
            R.id.nav_statistics -> StatisticsFragment()
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        supportFragmentManager.beginTransaction().replace(R.id.pageContainer, fragment).commit()
    }
}
