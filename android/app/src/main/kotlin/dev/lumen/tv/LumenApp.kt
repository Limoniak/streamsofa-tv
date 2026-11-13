package dev.lumen.tv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.lumen.data.CoreSettings
import dev.lumen.designsystem.lumenImageLoader

@HiltAndroidApp
class LumenApp : Application(), SingletonImageLoader.Factory {
    override fun newImageLoader(context: PlatformContext): ImageLoader = lumenImageLoader(context)
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    fun coreSettings() = CoreSettings(
        twitchClientId = BuildConfig.TWITCH_CLIENT_ID,
        appVersion = BuildConfig.VERSION_NAME,
    )
}
