package com.xinyu.companion.data.diagnostics

import android.util.Log
import com.xinyu.companion.BuildConfig
import com.xinyu.companion.domain.diagnostics.DiagnosticSink
import com.xinyu.companion.domain.diagnostics.GenerationOutcome

class AndroidDiagnosticSink : DiagnosticSink {
    override fun recordGeneration(
        assistantMessageId: String,
        providerId: String,
        model: String,
        outcome: GenerationOutcome,
        durationMs: Long,
        errorCode: String?,
    ) {
        Log.i(
            TAG,
            "generation outcome=${outcome.name.lowercase()} " +
                "duration_ms=$durationMs " +
                "error_code=${errorCode ?: "none"} " +
                "provider=$providerId " +
                "model=$model " +
                "app_version=${BuildConfig.VERSION_NAME} " +
                "message_id=$assistantMessageId",
        )
    }

    private companion object {
        const val TAG = "XinYuDiagnostics"
    }
}
