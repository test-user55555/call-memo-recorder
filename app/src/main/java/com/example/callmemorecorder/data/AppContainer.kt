package com.example.callmemorecorder.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.example.callmemorecorder.data.local.AppDatabase
import com.example.callmemorecorder.data.local.RecordDao
import com.example.callmemorecorder.data.remote.TranscriptionApiService
import com.example.callmemorecorder.data.repository.DriveRepository
import com.example.callmemorecorder.data.repository.RecordRepository
import com.example.callmemorecorder.data.repository.TranscriptionRepository
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.callmemorecorder.BuildConfig

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Manual Dependency Injection container.
 * Uses SQLiteOpenHelper instead of Room to avoid kapt/annotation processing.
 */
class AppContainer(private val context: Context) {

    // SQLiteOpenHelper-based Database (no kapt needed)
    val database: AppDatabase by lazy {
        AppDatabase(context.applicationContext)
    }

    // DAO
    val recordDao: RecordDao by lazy { RecordDao(database) }

    // DataStore
    val dataStore: DataStore<Preferences> by lazy { context.dataStore }

    // OkHttp
    val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY
                    else HttpLoggingInterceptor.Level.NONE
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    // Retrofit
    val retrofit: Retrofit by lazy {
        val baseUrl = BuildConfig.BACKEND_BASE_URL.let {
            if (it.endsWith("/")) it else "$it/"
        }
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    // Transcription API (nullable - graceful degradation)
    val transcriptionApiService: TranscriptionApiService? by lazy {
        if (BuildConfig.TRANSCRIPTION_ENABLED)
            retrofit.create(TranscriptionApiService::class.java)
        else null
    }

    // Repositories
    val recordRepository: RecordRepository by lazy { RecordRepository(recordDao) }
    val driveRepository: DriveRepository by lazy { DriveRepository(context) }
    val transcriptionRepository: TranscriptionRepository by lazy {
        TranscriptionRepository(transcriptionApiService)
    }
}
