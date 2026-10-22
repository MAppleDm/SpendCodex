import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Where DeepSeek is called. Only for testing against a stand-in:  ./gradlew assembleDebug -PmodelBaseUrl=http://10.0.2.2:8090
val modelBaseUrl = providers.gradleProperty("modelBaseUrl").getOrElse("https://api.deepseek.com")

// Optional release signing: create android/keystore.properties (see docs/development.md). Without it the release
// APK is signed with the debug key, which is fine for installing on your own phone.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.spendcodex"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.spendcodex"
        minSdk = 26
        targetSdk = 37
        versionCode = 8
        versionName = "0.4.0"

        buildConfigField("String", "MODEL_BASE_URL", "\"$modelBaseUrl\"")
        manifestPlaceholders["usesCleartext"] = modelBaseUrl.startsWith("http://").toString()
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/**", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Robolectric (Android 16 sandbox) reaches into JDK internals.
tasks.withType<Test>().configureEach {
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    maxHeapSize = "2g"
    // ./gradlew :app:testDebugUnitTest --tests "*ScreenshotTest" -Pscreenshots=../docs/screenshots
    providers.gradleProperty("screenshots").orNull?.let { systemProperty("screenshots.dir", rootProject.file(it).absolutePath) }
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":data"))
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
