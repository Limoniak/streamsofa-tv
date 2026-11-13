package dev.lumen.feature.auth

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached
import kotlinx.coroutines.launch

private const val PIN_LENGTH = 4

/**
 * Saisie d'un PIN à la télécommande : ▲ ▼ changent le chiffre, ◀ ▶ passent d'une case à l'autre,
 * OK valide (sur la dernière case : envoie). Les touches numériques tapent directement.
 * [check] `false` : « PIN incorrect » et tout est remis à zéro.
 */
@Composable
fun PinDialog(
    title: String,
    check: suspend (String) -> Boolean,
    onSuccess: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
) {
    var digits by remember { mutableStateOf(List(PIN_LENGTH) { 0 }) }
    var cursor by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }

    fun submit() {
        val pin = digits.joinToString("")
        checking = true
        scope.launch {
            val ok = check(pin)
            checking = false
            if (ok) {
                onSuccess(pin)
            } else {
                wrong = true
                digits = List(PIN_LENGTH) { 0 }
                cursor = 0
            }
        }
    }

    fun type(digit: Int) {
        digits = digits.toMutableList().also { it[cursor] = digit }
        if (cursor < PIN_LENGTH - 1) cursor++ else submit()
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp)
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || checking) return@onKeyEvent false
                    val code = event.key.nativeKeyCode
                    when {
                        event.key == Key.DirectionUp -> digits = digits.toMutableList().also { it[cursor] = (it[cursor] + 1) % 10 }
                        event.key == Key.DirectionDown -> digits = digits.toMutableList().also { it[cursor] = (it[cursor] + 9) % 10 }
                        event.key == Key.DirectionLeft -> cursor = (cursor - 1).coerceAtLeast(0)
                        event.key == Key.DirectionRight -> cursor = (cursor + 1).coerceAtMost(PIN_LENGTH - 1)
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter ->
                            if (cursor < PIN_LENGTH - 1) cursor++ else submit()
                        code in NativeKeyEvent.KEYCODE_0..NativeKeyEvent.KEYCODE_9 -> type(code - NativeKeyEvent.KEYCODE_0)
                        code in NativeKeyEvent.KEYCODE_NUMPAD_0..NativeKeyEvent.KEYCODE_NUMPAD_9 -> type(code - NativeKeyEvent.KEYCODE_NUMPAD_0)
                        else -> return@onKeyEvent false
                    }
                    if (event.key != Key.DirectionCenter) wrong = false
                    true
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                digits.forEachIndexed { index, digit ->
                    val current = index == cursor
                    Box(
                        modifier = Modifier
                            .size(width = 64.dp, height = 80.dp)
                            .background(LumenColors.Background, RoundedCornerShape(12.dp))
                            .border(2.dp, if (current) LumenColors.Accent else LumenColors.Background, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Seule la case en cours montre son chiffre : on règle sans dévoiler les autres.
                        Text(
                            text = when {
                                current -> digit.toString()
                                index < cursor -> "•"
                                else -> "–"
                            },
                            style = MaterialTheme.typography.displaySmall,
                            color = if (current) LumenColors.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = if (wrong) stringResource(R.string.pin_wrong) else hint ?: stringResource(R.string.pin_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = if (wrong) LumenColors.Error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
