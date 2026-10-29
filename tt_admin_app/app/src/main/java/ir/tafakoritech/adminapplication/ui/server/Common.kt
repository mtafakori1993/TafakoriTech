package ir.tafakoritech.adminapplication.ui.server

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ir.tafakoritech.adminapplication.api.TaskStatus
import ir.tafakoritech.adminapplication.ui.theme.FreshGreen
import ir.tafakoritech.adminapplication.ui.theme.SlateMuted
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A small colored pill that summarizes a task status. */
@Composable
internal fun StatusChip(status: TaskStatus) {
    val (label, color) = when (status) {
        TaskStatus.RUNNING -> "running" to Color(0xFF3B8EEA)
        TaskStatus.COMPLETED -> "completed" to FreshGreen
        TaskStatus.FAILED -> "failed" to MaterialTheme.colorScheme.error
        TaskStatus.STOPPED -> "stopped" to SlateMuted
    }
    Pill(label, color)
}

@Composable
internal fun Pill(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color
        )
    }
}

@Composable
internal fun StatusDot(ok: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (ok) FreshGreen else MaterialTheme.colorScheme.error)
    )
}

@Composable
internal fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
internal fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A scrollback console view that renders monospaced server output. */
@Composable
internal fun ConsoleText(
    text: String,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    fontSize: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
) {
    val base = MaterialTheme.typography.bodySmall
    val style = if (fontSize != androidx.compose.ui.unit.TextUnit.Unspecified)
        base.copy(fontSize = fontSize, lineHeight = fontSize * 1.35f)
    else base
    Text(
        text = text.ifBlank { "Waiting for output…" },
        style = style,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = modifier
    )
}

private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

/** Formats an epoch-seconds timestamp (the API uses float seconds). */
internal fun formatEpochSeconds(seconds: Double): String =
    if (seconds <= 0) "" else timeFormat.format(Date((seconds * 1000).toLong()))
