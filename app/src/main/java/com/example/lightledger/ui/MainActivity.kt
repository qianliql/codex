package com.example.lightledger.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.example.lightledger.R
import com.example.lightledger.databinding.ActivityMainBinding
import com.example.lightledger.notification.NotificationAccess
import com.example.lightledger.notification.PaymentNotificationService

class MainActivity : LedgerActivity() {
    private lateinit var binding: ActivityMainBinding
    private val accessHandler = Handler(Looper.getMainLooper())
    private var disconnectedAt: Long? = null
    private val accessCheck = object : Runnable {
        override fun run() {
            refreshAccessNotice()
            accessHandler.postDelayed(this, 2_000L)
        }
    }

    fun selectBill(id: Long) = openBills(id)

    fun openBills(selectionId: Long? = null) = openPage(TransactionsFragment().apply {
        selectionId?.let { arguments = Bundle().apply { putLong("select_id", it) } }
    })

    fun openStatistics() = openPage(StatisticsFragment())

    private fun openPage(fragment: Fragment) {
        pageTransaction().replace(R.id.pageContainer, fragment)
            .addToBackStack("home_detail").commit()
    }

    private fun pageTransaction(): FragmentTransaction = supportFragmentManager.beginTransaction()
        .setReorderingAllowed(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SystemBarInsets.apply(binding.root)
        binding.enableAutomatic.setOnClickListener { NotificationAccess.openSettings(this) }
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            showRoot(item.itemId)
            true
        }
        binding.bottomNavigation.setOnItemReselectedListener { returnToRoot() }
        if (savedInstanceState == null) showRoot(R.id.nav_home)
    }

    override fun onResume() {
        super.onResume()
        disconnectedAt = null
        accessHandler.removeCallbacks(accessCheck)
        accessCheck.run()
        val enabled = NotificationAccess.isEnabled(this)
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

    override fun onPause() {
        accessHandler.removeCallbacks(accessCheck)
        super.onPause()
    }

    private fun refreshAccessNotice() {
        val enabled = NotificationAccess.isEnabled(this)
        NotificationAccess.reconnectIfNeeded(this)
        val connected = enabled && PaymentNotificationService.isConnected
        if (!enabled || connected) disconnectedAt = null
        else if (disconnectedAt == null) disconnectedAt = SystemClock.elapsedRealtime()
        val needsReconnect = enabled && !connected &&
            SystemClock.elapsedRealtime() - (disconnectedAt ?: SystemClock.elapsedRealtime()) >= 5_000L
        binding.accessBanner.visibility = if (!enabled || needsReconnect) View.VISIBLE else View.GONE
        val message = if (!enabled) "自动记账未开启，请允许通知读取" else "监听未连接，请关闭再开启通知读取"
        val action = if (!enabled) "去开启" else "重新开启"
        if (binding.accessMessage.text.toString() != message) binding.accessMessage.text = message
        if (binding.enableAutomatic.text.toString() != action) binding.enableAutomatic.text = action
        (supportFragmentManager.findFragmentById(R.id.pageContainer) as? SettingsFragment)?.refreshStatus()
    }

    private fun returnToRoot() {
        supportFragmentManager.executePendingTransactions()
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun showRoot(id: Int) {
        returnToRoot()
        val fragment: Fragment = when (id) {
            R.id.nav_settings -> SettingsFragment()
            else -> HomeFragment()
        }
        pageTransaction().replace(R.id.pageContainer, fragment).commit()
    }
}
