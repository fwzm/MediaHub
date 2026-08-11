package com.mediahub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mediahub.app.navigation.MediaHubNavHost
import com.mediahub.app.ui.theme.MediaHubTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MediaHubTheme {
                MediaHubNavHost()
            }
        }
    }
}
