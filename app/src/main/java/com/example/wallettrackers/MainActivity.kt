package com.example.wallettrackers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.wallettrackers.screens.HomeScreen
import com.example.wallettrackers.ui.theme.WalletTrackersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WalletTrackersTheme {
                HomeScreen()
            }
        }
    }
}
