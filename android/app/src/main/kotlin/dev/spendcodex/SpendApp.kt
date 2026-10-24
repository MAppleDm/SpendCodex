package dev.spendcodex

import android.app.Application
import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.spendcodex.data.log.AppLog
import java.io.File
import java.time.ZoneId
import java.util.Locale
import javax.inject.Named

@HiltAndroidApp
class SpendApp : Application() {
    override fun onCreate() {
        // first, so that everything after it, a crash included, is in the logs folder
        AppLog.init(File(filesDir, "logs"))
        AppLog.catchCrashes()
        AppLog.i("App", "start: SpendCodex ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, ${Locale.getDefault()}, ${ZoneId.systemDefault()}")
        super.onCreate()
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** DeepSeek, unless the build points the model at a stand-in for testing (-PmodelBaseUrl). */
    @Provides @Named("modelBaseUrl")
    fun modelBaseUrl(): String = BuildConfig.MODEL_BASE_URL

    /** Written into every export, so a file says which version made it. */
    @Provides @Named("appVersion")
    fun appVersion(): String = BuildConfig.VERSION_NAME
}
