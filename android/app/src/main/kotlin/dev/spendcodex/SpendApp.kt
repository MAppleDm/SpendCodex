package dev.spendcodex

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@HiltAndroidApp
class SpendApp : Application()

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
