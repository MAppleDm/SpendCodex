package dev.spendcodex.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.spendcodex.data.ai.AiReader
import dev.spendcodex.data.backup.DocAssets
import dev.spendcodex.data.db.AppDatabase
import dev.spendcodex.data.settings.KeyCipher
import dev.spendcodex.data.settings.KeystoreCipher
import dev.spendcodex.data.settings.SecretStore
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    /** The phone's clock and zone; tests pass a fixed one. */
    @Provides
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides @Singleton
    fun docs(@ApplicationContext context: Context): DocAssets = DocAssets { context.assets.open(it) }

    /** Only DeepSeek is called with it: nothing else of the app goes over the network but the GitHub update check. */
    @Provides @Singleton @Named("model")
    fun modelHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun aiReader(@Named("model") http: OkHttpClient, @Named("modelBaseUrl") baseUrl: String, secrets: SecretStore): AiReader =
        AiReader(http, baseUrl, key = { secrets.deepseekKey })
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindings {
    @Binds abstract fun cipher(impl: KeystoreCipher): KeyCipher
}
