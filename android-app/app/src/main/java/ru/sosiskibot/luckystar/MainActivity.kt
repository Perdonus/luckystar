package ru.sosiskibot.luckystar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.sosiskibot.luckystar.ui.LuckyStarApp
import ru.sosiskibot.luckystar.ui.theme.LuckyStarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LuckyStarTheme {
                LuckyStarApp()
            }
        }
    }
}
