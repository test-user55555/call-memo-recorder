package com.example.callmemorecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.example.callmemorecorder.navigation.AppNavHost
import com.example.callmemorecorder.navigation.Screen
import com.example.callmemorecorder.ui.theme.CallMemoRecorderTheme
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private val appContainer get() = (application as CallMemoApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val setupCompleted = runBlocking {
            appContainer.dataStore.data
                .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
                .map { prefs -> prefs[booleanPreferencesKey("setup_completed")] ?: false }
                .first()
        }

        setContent {
            CallMemoRecorderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavHost(
                        navController = navController,
                        startDestination = if (setupCompleted) Screen.Home.route else Screen.Setup.route
                    )
                }
            }
        }
    }
}
