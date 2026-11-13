package dev.lumen.designsystem

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/**
 * Chargeur d'images de l'app : émotes animées (GIF et WebP animé), sauf sur un appareil
 * à faible mémoire où elles restent figées sur leur première image.
 */
fun lumenImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
    .components {
        if (!context.getSystemService(ActivityManager::class.java).isLowRamDevice) {
            // Décodeur du système à partir d'Android 9 (WebP animé compris), décodeur Coil avant.
            add(if (Build.VERSION.SDK_INT >= 28) AnimatedImageDecoder.Factory() else GifDecoder.Factory())
        }
    }
    .build()
