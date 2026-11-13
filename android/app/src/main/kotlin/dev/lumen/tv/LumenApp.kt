package dev.lumen.tv

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.lumen.data.CoreSettings

@HiltAndroidApp
class LumenApp : Application()

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun coreSettings() = CoreSettings(
        twitchClientId = BuildConfig.TWITCH_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME,
    )
}
