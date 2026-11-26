package com.xinyu.companion

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class XinYuApplication : Application() {
    var sqlCipherLoadFailure: Throwable? = null
        private set

    override fun onCreate() {
        super.onCreate()
        sqlCipherLoadFailure = runCatching {
            System.loadLibrary("sqlcipher")
        }.exceptionOrNull()
    }
}

