package com.xinyu.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xinyu.companion.ui.MainApp
import com.xinyu.companion.ui.StartupErrorScreen
import com.xinyu.companion.ui.theme.XinYuTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XinYuTheme {
                if ((application as XinYuApplication).sqlCipherLoadFailure == null) {
                    MainApp()
                } else {
                    StartupErrorScreen()
                }
            }
        }
    }
}

