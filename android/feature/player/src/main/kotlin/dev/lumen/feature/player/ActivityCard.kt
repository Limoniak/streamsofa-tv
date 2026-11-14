package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.lumen.core.ChannelActivity
import dev.lumen.core.Poll
import dev.lumen.core.Prediction
import dev.lumen.core.PredictionStatus
import dev.lumen.designsystem.LumenColors

/** Sondage et prédiction en cours, en tête du chat ; en lecture seule (voter se fait sur Twitch). */
@Composable
fun ActivityCard(activity: ChannelActivity, modifier: Modifier = Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        activity.poll?.let { PollBlock(it) }
        activity.prediction?.let { PredictionBlock(it) }
    }
}

@Composable
private fun PollBlock(poll: Poll) {
    val status = if (poll.active) {
        pluralStringResource(R.plurals.activity_poll_left, poll.totalVotes.toInt(), poll.totalVotes.toInt(), formatSeconds((poll.remainingMs / 1000u).toLong()))
    } else {
        pluralStringResource(R.plurals.activity_poll_ended, poll.totalVotes.toInt(), poll.totalVotes.toInt())
    }
    val best = poll.choices.maxOfOrNull { it.votes } ?: 0u
    Block(stringResource(R.string.activity_poll), poll.title, status) {
        poll.choices.forEach { choice ->
            Share(
                label = choice.title,
                share = if (poll.totalVotes > 0u) choice.votes.toFloat() / poll.totalVotes.toFloat() else 0f,
                highlight = !poll.active && choice.votes == best && best > 0u,
            )
        }
    }
}

@Composable
private fun PredictionBlock(prediction: Prediction) {
    val total = prediction.outcomes.sumOf { it.points }
    val now = System.currentTimeMillis() / 1000
    val status = when (prediction.status) {
        PredictionStatus.ACTIVE -> stringResource(R.string.activity_prediction_open, formatSeconds((prediction.locksAtS - now).coerceAtLeast(0)))
        PredictionStatus.LOCKED -> stringResource(R.string.activity_prediction_locked)
        PredictionStatus.RESOLVED -> stringResource(
            R.string.activity_prediction_won,
            prediction.outcomes.firstOrNull { it.winner }?.title.orEmpty(),
        )
    }
    Block(stringResource(R.string.activity_prediction), prediction.title, status) {
        prediction.outcomes.forEach { outcome ->
            Share(
                label = outcome.title,
                share = if (total > 0u) outcome.points.toFloat() / total.toFloat() else 0f,
                highlight = outcome.winner,
            )
        }
    }
}

@Composable
private fun Block(kind: String, title: String, status: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(LumenColors.Surface, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(kind.uppercase(), color = LumenColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, color = LumenColors.OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        content()
        Text(status, color = LumenColors.OnSurfaceMuted, fontSize = 13.sp)
    }
}

/** Une option et sa part, en barre et en pourcentage. */
@Composable
private fun Share(label: String, share: Float, highlight: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = (if (highlight) "✓ " else "") + label,
                color = if (highlight) LumenColors.Accent else LumenColors.OnSurface,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(percent.format(share), color = LumenColors.OnSurfaceMuted, fontSize = 14.sp)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(LumenColors.Background, RoundedCornerShape(2.dp))) {
            Box(
                Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(if (highlight) LumenColors.Accent else LumenColors.OnSurfaceMuted, RoundedCornerShape(2.dp)),
            )
        }
    }
}

// « 60 % » en français, « 60% » en anglais.
private val percent = java.text.NumberFormat.getPercentInstance()

private fun formatSeconds(seconds: Long) = "%d:%02d".format(seconds / 60, seconds % 60)
