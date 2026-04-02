package com.example.callmemorecorder

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.example.callmemorecorder.data.AppContainer

/**
 * Application class with manual DI container
 */
class CallMemoApp : Application(), Configuration.Provider {

    // Manual dependency injection container
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()
}
