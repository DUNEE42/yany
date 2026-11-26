package com.xinyu.companion.domain

import kotlin.math.ceil

object TokenEstimator {
    /**
     * Conservative text estimate. Provider usage remains authoritative after completion.
     */
    fun estimate(text: String): Int {
        if (text.isBlank()) return 0
        val cjk = text.count { it.code in 0x3400..0x9FFF }
        val nonCjk = text.length - cjk
        return ceil(cjk / 1.6 + nonCjk / 3.8).toInt()
    }
}

