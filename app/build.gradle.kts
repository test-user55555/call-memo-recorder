import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // kapt完全除去 - Room手動実装に変更
}

// Load local.properties safely
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

android {
    namespace = "com.example.callmemorecorder"
    compileSdk = 36

    // ── 固定 debug.keystore 署名設定 ──────────────────────────────────────
    // プロジェクトルートに debug.keystore を配置し、常に同じ署名でビルドする。
    // これにより「上書きインストール失敗」「Google Sign-In DEVELOPER_ERROR code=10」を防ぐ。
    signingConfigs {
        getByName("debug") {
            val keystoreFile = rootProject.file("debug.keystore")
            if (keystoreFile.exists()) {
                storeFile     = keystoreFile
                storePassword = "android"
                keyAlias      = "androiddebugkey"
                keyPassword   = "android"
            }
            // ファイルが存在しない場合は Android デフォルトの debug.keystore が使われる
        }
    }

    defaultConfig {
        applicationId = "com.example.callmemorecorder"
        minSdk = 28
        targetSdk = 28
        versionCode = 12
        versionName = "1.3.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_BASE_URL",
            "\"${localProperties.getProperty("BACKEND_BASE_URL", "https://your-backend.example.com")}\"")
        buildConfigField("String", "DRIVE_FOLDER_NAME",
            "\"${localProperties.getProperty("DRIVE_FOLDER_NAME", "CallMemoRecorder")}\"")
        buildConfigField("boolean", "DRIVE_ENABLED", "true")
        buildConfigField("boolean", "TRANSCRIPTION_ENABLED",
            "${localProperties.getProperty("TRANSCRIPTION_ENABLED", "false")}")
        buildConfigField("boolean", "RELEASE_SIGNING_ENABLED",
            "${localProperties.getProperty("RELEASE_SIGNING_ENABLED", "false")}") 
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            // applicationIdSuffix を削除: suffixがあると更新インストール時に
            // 別アプリ扱いされてインストール失敗する
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Kotlin 2.0+ uses compose compiler gradle plugin, no need to set kotlinCompilerExtensionVersion here

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Navigation
    implementation(libs.navigation.compose)

    // Material Icons Extended (Mic, Cloud, Stop, Info, List, Settings, Warning etc.)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Google Sign-In (Drive連携用)
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    // Google Drive REST API
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.43.3")

    // FTPS (Apache Commons Net)
    implementation("commons-net:commons-net:3.10.0")

    // Retrofit + OkHttp
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)

    // Permissions
    implementation(libs.accompanist.permissions)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
