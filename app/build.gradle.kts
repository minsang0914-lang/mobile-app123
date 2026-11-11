import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").inputStream().use { load(it) }
}

fun keystoreValue(name: String): String {
    return keystoreProperties.getProperty(name)
        ?: error("keystore.properties 에 $name 항목이 없습니다.")
}

android {
    namespace = "io.github.minsang0914.svt"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.minsang0914.svt"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(keystoreValue("storeFile"))
            storePassword = keystoreValue("storePassword")
            keyAlias = keystoreValue("keyAlias")
            keyPassword = keystoreValue("keyPassword")
            storeType = keystoreValue("storeType")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

base {
    archivesName.set("SVT")
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
}
