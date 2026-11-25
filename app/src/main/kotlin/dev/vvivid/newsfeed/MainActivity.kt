package dev.vvivid.newsfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.vvivid.newsfeed.onboarding.OnboardingRoute
import dev.vvivid.newsfeed.ui.theme.NewsfeedTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NewsfeedTheme {
                OnboardingRoute()
            }
        }
    }
}
