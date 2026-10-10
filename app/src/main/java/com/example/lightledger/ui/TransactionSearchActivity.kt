package com.example.lightledger.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.lightledger.LedgerApp
import com.example.lightledger.databinding.ActivityTransactionSearchBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Calendar

class TransactionSearchActivity : LedgerActivity() {
    private lateinit var binding: ActivityTransactionSearchBinding
    private var form: TransactionSearchForm? = null
    private lateinit var initial: TransactionFilter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionSearchBinding.inflate(layoutInflater)
        setContentView(binding.root); SystemBarInsets.apply(binding.root)
        initial = TransactionFilter.readFrom(savedInstanceState ?: intent.getBundleExtra(TransactionFilter.EXTRA))
        binding.goBack.setOnClickListener { finish() }
        lifecycleScope.launch {
            try {
                val repository = (application as LedgerApp).repository
                val categories = repository.categoryList(); val tags = repository.tags()
                form = TransactionSearchForm(binding, initial, categories, tags, ::pickDate) { result ->
                    setResult(RESULT_OK, Intent().putExtra(TransactionFilter.EXTRA, result.writeTo(Bundle())))
                    finish()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(this@TransactionSearchActivity, "筛选条件加载失败，请重新打开", Toast.LENGTH_LONG).show() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { (form?.snapshot() ?: initial).writeTo(outState); super.onSaveInstanceState(outState) }
    private fun pickDate(time: Long, selected: (Long) -> Unit) {
        val date = Calendar.getInstance().apply { timeInMillis = time }
        DatePickerDialog(this, { _, year, month, day -> selected(Calendar.getInstance().apply { clear(); set(year, month, day) }.timeInMillis) },
            date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
    }
}

