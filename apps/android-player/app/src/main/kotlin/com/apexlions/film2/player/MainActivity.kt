package com.apexlions.film2.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.apexlions.film2.player.navigation.Film2PlayerNavGraph
import com.apexlions.film2.player.ui.theme.Film2PlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Film2PlayerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Film2PlayerNavGraph()
                }
            }
        }
    }
}
