plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "com.example.pixelchat"; compileSdk = 35
 defaultConfig { applicationId = "com.example.pixelchat"; minSdk = 30; targetSdk = 35; versionCode = 5; versionName = "5.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("com.google.mlkit:text-recognition:16.0.1") }
