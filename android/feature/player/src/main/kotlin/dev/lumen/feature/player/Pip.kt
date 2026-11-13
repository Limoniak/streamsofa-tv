package dev.lumen.feature.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer

/** Incrustation (PiP) : la vidéo continue dans un coin pendant qu'on va sur le launcher ou une autre app. */
internal fun Context.supportsPip(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun Activity.enterPip() {
    runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
}

/** Activité qui héberge Compose (le contexte peut être enveloppé). */
@Composable
internal fun currentActivity(): ComponentActivity? {
    var context = LocalContext.current
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    return null
}

/** `true` tant que l'activité est en incrustation : le lecteur n'affiche alors que la vidéo. */
@Composable
internal fun rememberInPip(): Boolean {
    val activity = currentActivity()
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPip
}
