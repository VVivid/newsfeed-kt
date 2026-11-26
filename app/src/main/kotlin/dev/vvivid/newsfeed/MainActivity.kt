package dev.vvivid.newsfeed

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NewsfeedApplication).container
        setContent {
            val sessionState by container.sessionManager.state.collectAsStateWithLifecycle()
            NewsfeedApp(
                sessionState = sessionState,
                signInService = container.signInService,
                onSignOut = { container.sessionManager.signOut(forgetServer = false) },
                onChangeServer = { container.sessionManager.signOut(forgetServer = true) },
            )
        }
    }
}
