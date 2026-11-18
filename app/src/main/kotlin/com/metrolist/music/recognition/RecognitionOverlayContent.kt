/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.constants.DarkModeKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SelectedThemeColorKey
import com.metrolist.music.constants.ThumbnailCornerRadius
import com.metrolist.music.ui.screens.settings.DarkMode
import com.metrolist.music.ui.theme.DefaultThemeColor
import com.metrolist.music.ui.theme.MetrolistTheme
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.shazamkit.models.RecognitionResult
import com.metrolist.shazamkit.models.RecognitionStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val OverlayWidth = 300.dp

/**
 * The floating recognizer. No haze here: an overlay cannot blur another app's window, so the
 * card uses a solid Material surface in the user's own theme instead.
 */
@Composable
fun RecognitionOverlayContent(host: RecognitionOverlay) {
    OverlayTheme {
        val status by MusicRecognitionService.recognitionStatus.collectAsStateWithLifecycle()
        Box(Modifier.padding(8.dp)) {
            Surface(
                modifier = Modifier.width(OverlayWidth),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shadowElevation = 8.dp,
            ) {
                Column {
                    OverlayHeader(host)
                    SourceSelector(
                        host = host,
                        enabled = status !is RecognitionStatus.Listening && status !is RecognitionStatus.Processing,
                    )
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = { (fadeIn() + scaleIn()).togetherWith(fadeOut() + scaleOut()) },
                        label = "overlayRecognition",
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                    ) { current ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (current) {
                                is RecognitionStatus.Ready -> {
                                    ListenButton(level = 0f, onClick = host::identify)
                                    Hint(stringResource(R.string.tap_to_recognize))
                                }

                                is RecognitionStatus.Listening -> {
                                    val level by MusicRecognitionService.audioLevel.collectAsStateWithLifecycle()
                                    ListenButton(level = level, onClick = host::cancel)
                                    Hint(stringResource(R.string.recognition_notification_listening))
                                }

                                is RecognitionStatus.Processing -> {
                                    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(40.dp),
                                            strokeWidth = 3.dp,
                                        )
                                    }
                                    Hint(stringResource(R.string.recognition_notification_processing))
                                }

                                is RecognitionStatus.Success -> RecognizedTrack(current.result, host)

                                is RecognitionStatus.NoMatch -> RetryMessage(current.message, host)

                                is RecognitionStatus.Error -> RetryMessage(current.message, host)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OverlayTheme(content: @Composable () -> Unit) {
    val (darkMode) = rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.ON)
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val useDarkTheme = if (darkMode == DarkMode.AUTO) systemDark else darkMode == DarkMode.ON
    val (pureBlackEnabled) = rememberPreference(PureBlackKey, defaultValue = true)
    val (themeColorInt) = rememberPreference(SelectedThemeColorKey, defaultValue = DefaultThemeColor.toArgb())

    MetrolistTheme(
        darkTheme = useDarkTheme,
        pureBlack = pureBlackEnabled && useDarkTheme,
        themeColor = Color(themeColorInt),
        frostedUi = false,
        content = content,
    )
}

@Composable
private fun OverlayHeader(host: RecognitionOverlay) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { host.beginDrag() },
                        onDragEnd = { host.endDrag() },
                        onDragCancel = { host.endDrag() },
                    ) { change, amount ->
                        change.consume()
                        host.drag(amount.x, amount.y)
                    }
                }.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.drag_handle),
            contentDescription = stringResource(R.string.recognition_overlay_move),
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.recognize_music),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = host::close) {
            Icon(
                painter = painterResource(R.drawable.close),
                contentDescription = stringResource(R.string.close),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SourceSelector(
    host: RecognitionOverlay,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = host.source == RecognitionSource.MICROPHONE,
            onClick = { host.selectSource(RecognitionSource.MICROPHONE) },
            enabled = enabled,
            label = { Text(stringResource(R.string.recognition_source_microphone), style = MaterialTheme.typography.labelMedium) },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            FilterChip(
                selected = host.source == RecognitionSource.PLAYBACK,
                onClick = { host.selectSource(RecognitionSource.PLAYBACK) },
                enabled = enabled,
                label = { Text(stringResource(R.string.recognition_source_internal), style = MaterialTheme.typography.labelMedium) },
            )
        }
    }
}

/** The listening button from the full screen, shrunk: a ring of bars that swells with loudness. */
@Composable
private fun ListenButton(
    level: Float,
    onClick: () -> Unit,
) {
    val smoothLevel by animateFloatAsState(
        targetValue = level,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "overlayAudioLevel",
    )
    val primary = MaterialTheme.colorScheme.primary

    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val barBase = 32.dp.toPx()
            val barMax = 12.dp.toPx()
            val barCount = 36
            val loudness = smoothLevel.coerceIn(0f, 1f)
            for (i in 0 until barCount) {
                val angle = (2 * PI * i / barCount).toFloat()
                val direction = Offset(cos(angle), sin(angle))
                drawLine(
                    color = primary.copy(alpha = 0.25f + 0.75f * loudness),
                    start = center + direction * barBase,
                    end = center + direction * (barBase + 2.dp.toPx() + barMax * loudness),
                    strokeWidth = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
        Box(
            modifier =
                Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        val scale = 1f + 0.06f * smoothLevel.coerceIn(0f, 1f)
                        scaleX = scale
                        scaleY = scale
                    }.clip(CircleShape)
                    .background(primary)
                    .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.mic),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun RecognizedTrack(
    result: RecognitionResult,
    host: RecognitionOverlay,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { host.openResult(result) }
                .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = result.coverArtHqUrl ?: result.coverArtUrl,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(ThumbnailCornerRadius)),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = result.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    TextButton(onClick = host::identify) {
        Text(stringResource(R.string.try_again))
    }
}

@Composable
private fun RetryMessage(
    message: String,
    host: RecognitionOverlay,
) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    TextButton(onClick = host::identify) {
        Text(stringResource(R.string.try_again))
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}