package com.instantconverter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Entry "Convert to FCFA" shown in the text selection menu of every app
 * (Chrome: tap ⋮ in the selection bar). Forwards the text to the bubble.
 */
class ProcessTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        if (!BubbleService.convert(this, text, explicit = true)) {
            // No overlay permission: fall back to a simple toast.
            val parsed = AmountParser.parse(text, requireCurrency = false)
            val msg = if (parsed == null) {
                getString(R.string.no_amount)
            } else {
                val conv = RateRepository.converter(this)
                val cur = parsed.currency ?: Currency.EUR
                "${conv.formatSource(parsed.value, cur)} = ${conv.formatXof(conv.toXof(parsed.value, cur))}"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
