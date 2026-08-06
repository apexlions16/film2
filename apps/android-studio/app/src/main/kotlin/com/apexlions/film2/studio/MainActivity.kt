package com.apexlions.film2.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.apexlions.film2.studio.navigation.Film2StudioNavGraph
import com.apexlions.film2.studio.ui.theme.Film2StudioTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Film2StudioTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Film2StudioNavGraph()
                }
            }
        }
    }
}
