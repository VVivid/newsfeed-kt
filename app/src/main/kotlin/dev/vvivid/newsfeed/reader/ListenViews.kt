package dev.vvivid.newsfeed.reader

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.audio.ArticleAudio
import dev.vvivid.newsfeed.audio.AudioVoice
import java.text.NumberFormat
import kotlin.math.ceil

/** The reader's audio callbacks, passed down as one value. */
class AudioActions(
    val onCreate: (voiceId: String) -> Unit = {},
    val onPlaySample: (voiceId: String) -> Unit = {},
    val onCancel: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onListen: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onSkip: (deltaMs: Long) -> Unit = {},
    val onStepPart: (delta: Int) -> Unit = {},
    val onPreviousSection: () -> Unit = {},
    val onNextSection: () -> Unit = {},
    val onSeekToFraction: (Float) -> Unit = {},
    val onSpeed: (Float) -> Unit = {},
    val onSleepTimer: (minutes: Int?) -> Unit = {},
    val onMessageShown: (AudioMessage) -> Unit = {},
)

/** The speeds the player offers. */
private val Speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** The sleep timer's choices, in minutes. */
private val SleepMinutes = listOf(15, 30, 45, 60)

private const val SKIP_MS = 15_000L

/**
 * Asks which voice to use and what creating the audio means, then creates it. ModalBottomSheet is
 * still experimental in Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateAudioSheet(
    title: String,
    state: AudioUiState.NotCreated,
    onCreate: (voiceId: String) -> Unit,
    onPlaySample: (voiceId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var voiceId by rememberSaveable { mutableStateOf(state.voices.first().id) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.audio_create_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(text = title, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.audio_voice_label),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
            for (voice in state.voices) {
                VoiceOption(
                    voice,
                    selected = voice.id == voiceId,
                    onSelect = { voiceId = voice.id },
                    onPlaySample = { onPlaySample(voice.id) },
                )
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val minutes = speechMinutes(state.characters)
                    InfoLine(R.drawable.ic_schedule, pluralStringResource(R.plurals.audio_length, minutes, minutes))
                    InfoLine(
                        R.drawable.ic_waveform,
                        stringResource(R.string.audio_cost, NumberFormat.getIntegerInstance().format(state.characters)),
                    )
                    InfoLine(R.drawable.ic_lock, stringResource(R.string.audio_lock_note), emphasized = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(52.dp)) {
                    Text(stringResource(R.string.audio_cancel))
                }
                Button(
                    onClick = { onCreate(voiceId) },
                    enabled = !state.isCreating,
                    modifier = Modifier.weight(2f).height(52.dp),
                ) {
                    Text(stringResource(R.string.audio_create))
                }
            }
        }
    }
}

@Composable
private fun VoiceOption(
    voice: AudioVoice,
    selected: Boolean,
    onSelect: () -> Unit,
    onPlaySample: () -> Unit,
) {
    val border =
        if (selected) {
            BorderStroke(
                2.dp,
                MaterialTheme.colorScheme.primary,
            )
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = border,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier =
                Modifier
                    .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                    .padding(start = 8.dp, top = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The row handles the selection, so the button itself is only a picture of it.
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(text = voice.name, style = MaterialTheme.typography.titleMedium)
                if (voice.description.isNotEmpty()) {
                    Text(
                        text = voice.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            val sampleLabel = stringResource(R.string.audio_sample_of, voice.name)
            OutlinedButton(
                onClick = onPlaySample,
                modifier = Modifier.semantics { contentDescription = sampleLabel },
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(16.dp))
                Text(text = stringResource(R.string.audio_sample), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun InfoLine(
    @DrawableRes icon: Int,
    text: String,
    emphasized: Boolean = false,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A card docked above the bottom bar, for jobs and the mini player. */
@Composable
private fun DockedCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        content = content,
    )
}

/** The server is making the audio: progress and Cancel. */
@Composable
internal fun GeneratingCard(
    state: AudioUiState.Generating,
    onCancel: () -> Unit,
) {
    val audio = state.audio
    DockedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_waveform), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.audio_generating, audio.voice.name), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = generatingDetail(audio),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = onCancel, enabled = !state.isCancelling) { Text(stringResource(R.string.audio_cancel)) }
            }
            LinearProgressIndicator(
                progress = { if (audio.segmentsTotal > 0) audio.segmentsDone.toFloat() / audio.segmentsTotal else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.audio_generating_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun generatingDetail(audio: ArticleAudio): String {
    val remainingMinutes = ceil((audio.segmentsTotal - audio.segmentsDone) * SECONDS_PER_PART / 60.0).toInt()
    val parts = pluralStringResource(R.plurals.audio_generating_parts, audio.segmentsTotal, audio.segmentsDone, audio.segmentsTotal)
    val left =
        if (remainingMinutes <= 1) {
            stringResource(R.string.audio_generating_under_a_minute)
        } else {
            pluralStringResource(R.plurals.audio_generating_minutes_left, remainingMinutes, remainingMinutes)
        }
    return "$parts · $left"
}

/** About how long the server takes per part, for the time-left estimate. */
private const val SECONDS_PER_PART = 3.0

/** The audio is coming to this device. */
@Composable
internal fun DownloadingCard(state: AudioUiState.Downloading) {
    DockedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.audio_downloading), style = MaterialTheme.typography.titleSmall)
            val total = state.audio.segments.size
            LinearProgressIndicator(progress = {
                if (total >
                    0
                ) {
                    state.filesPresent.toFloat() / total
                } else {
                    0f
                }
            }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The server gave up: its reason, Try again and Delete. */
@Composable
internal fun FailedCard(
    state: AudioUiState.Failed,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    DockedCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.audio_failed), style = MaterialTheme.typography.titleSmall)
            Text(
                text = state.audio.error ?: stringResource(R.string.audio_failed_unknown),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.audio_delete)) }
                Button(onClick = onRetry, enabled = !state.isRetrying) { Text(stringResource(R.string.audio_retry)) }
            }
        }
    }
}

/** Under the title of an article with audio. */
@Composable
internal fun AudioLockLine(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.audio_locked),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The saved audio under the title: play it and how long it is. */
@Composable
internal fun ReadyAudioCard(
    audio: ArticleAudio,
    listen: ListenState,
    onListen: () -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayPauseButton(isPlaying = listen.isPlaying, onListen = onListen, onPause = onPause, size = 48)
            Column {
                val minutes = minutesOf(audio.durationMs)
                Text(pluralStringResource(R.plurals.audio_listen_minutes, minutes, minutes), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(R.string.audio_saved_offline, audio.voice.name),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onListen: () -> Unit,
    onPause: () -> Unit,
    size: Int,
) {
    FilledIconButton(
        onClick = if (isPlaying) onPause else onListen,
        modifier = Modifier.size(size.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
    ) {
        Icon(
            painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
            contentDescription = stringResource(if (isPlaying) R.string.audio_pause else R.string.audio_play),
            modifier = Modifier.size((size / 2).dp),
        )
    }
}

/** The player docked above the bottom bar while this article's audio is loaded. */
@Composable
internal fun MiniPlayer(
    title: String,
    voiceName: String,
    listen: ListenState,
    actions: AudioActions,
    onExpand: () -> Unit,
) {
    DockedCard {
        Column {
            LinearProgressIndicator(
                progress = { if (listen.durationMs > 0) listen.positionMs.toFloat() / listen.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp),
                drawStopIndicator = {},
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clickable(onClickLabel = stringResource(R.string.audio_open_player), onClick = onExpand)
                            .padding(vertical = 6.dp),
                ) {
                    Text(
                        text = listen.section ?: title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.audio_mini_detail, voiceName, speedLabel(listen.speed), timeLeft(listen)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { actions.onStepPart(-1) }) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.audio_previous_part))
                }
                PlayPauseButton(isPlaying = listen.isPlaying, onListen = actions.onListen, onPause = actions.onPause, size = 48)
                IconButton(onClick = { actions.onStepPart(1) }) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.audio_next_part))
                }
            }
        }
    }
}

@Composable
private fun timeLeft(listen: ListenState): String {
    val minutes = minutesOf(((listen.durationMs - listen.positionMs) / listen.speed.coerceAtLeast(0.1f)).toLong())
    return pluralStringResource(R.plurals.audio_minutes_left, minutes, minutes)
}

private fun minutesOf(durationMs: Long): Int = ceil(durationMs / 60_000.0).toInt().coerceAtLeast(1)

private fun speedLabel(speed: Float): String = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"

/**
 * The full player: progress, skipping, speed, sleep timer, the voice and what comes next.
 * ModalBottomSheet is still experimental in Material 3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSheet(
    title: String,
    audio: ArticleAudio,
    listen: ListenState,
    actions: AudioActions,
    onDeleteAudio: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.audio_now_listening),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                SleepTimerButton(listen, actions.onSleepTimer)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.headlineSmall)
                listen.section?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            ProgressSlider(listen, actions.onSeekToFraction)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = actions.onPreviousSection) {
                    Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.audio_previous_section))
                }
                SkipButton(R.drawable.ic_replay_15, R.string.audio_back_15) { actions.onSkip(-SKIP_MS) }
                PlayPauseButton(isPlaying = listen.isPlaying, onListen = actions.onListen, onPause = actions.onPause, size = 72)
                SkipButton(R.drawable.ic_forward_15, R.string.audio_forward_15) { actions.onSkip(SKIP_MS) }
                IconButton(onClick = actions.onNextSection) {
                    Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.audio_next_section))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (speed in Speeds) {
                    FilterChip(
                        selected = speed == listen.speed,
                        onClick = { actions.onSpeed(speed) },
                        label = { Text(speedLabel(speed)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_waveform), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(stringResource(R.string.audio_voice_saved, audio.voice.name), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = stringResource(R.string.audio_created_on, createdDate(audio)),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = onDeleteAudio) {
                        Text(stringResource(R.string.audio_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (listen.upNext.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.audio_up_next),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    listen.upNext.forEach { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun createdDate(audio: ArticleAudio): String {
    val context = LocalContext.current
    return remember(audio.createdAt) {
        DateUtils.formatDateTime(context, audio.createdAt.toEpochMilli(), DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH)
    }
}

@Composable
private fun ProgressSlider(
    listen: ListenState,
    onSeekToFraction: (Float) -> Unit,
) {
    // While the thumb is dragged it shows where it is, not where playback is.
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (listen.durationMs > 0) listen.positionMs.toFloat() / listen.durationMs else 0f
    val shown = if (dragging) dragFraction else fraction
    Column {
        Slider(
            value = shown,
            onValueChange = {
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                dragging = false
                onSeekToFraction(dragFraction)
            },
        )
        Row {
            val position = (listen.durationMs * shown).toLong()
            Text(clock(position), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text("−" + clock(listen.durationMs - position), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun clock(durationMs: Long): String {
    val seconds = (durationMs / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
private fun SkipButton(
    @DrawableRes icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(32.dp))
            Text("15", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SleepTimerButton(
    listen: ListenState,
    onSleepTimer: (Int?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_bedtime), contentDescription = null, modifier = Modifier.size(20.dp))
            val sleepAt = listen.sleepAtMillis
            if (sleepAt != null) {
                val minutes = ceil((sleepAt - System.currentTimeMillis()) / 60_000.0).toInt().coerceAtLeast(1)
                Text(
                    text = pluralStringResource(R.plurals.audio_sleep_in, minutes, minutes),
                    modifier = Modifier.padding(start = 6.dp),
                )
            } else {
                Text(stringResource(R.string.audio_sleep_timer), modifier = Modifier.padding(start = 6.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (minutes in SleepMinutes) {
                DropdownMenuItem(
                    text = { Text(pluralStringResource(R.plurals.audio_sleep_option, minutes, minutes)) },
                    onClick = {
                        open = false
                        onSleepTimer(minutes)
                    },
                )
            }
            if (listen.sleepAtMillis != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_sleep_off)) },
                    onClick = {
                        open = false
                        onSleepTimer(null)
                    },
                )
            }
        }
    }
}

/** Asks before deleting the audio, which unlocks the text and costs credits to make again. */
@Composable
internal fun DeleteAudioDialog(
    audio: ArticleAudio,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val minutes = minutesOf(audio.durationMs)
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.audio_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(pluralStringResource(R.plurals.audio_delete_removed, minutes, minutes, audio.voice.name))
                Text(stringResource(R.string.audio_delete_unlocks))
                Text(stringResource(R.string.audio_delete_cost, NumberFormat.getIntegerInstance().format(audio.characters)))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) { Text(stringResource(R.string.audio_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_cancel)) } },
    )
}

/** Asks before moving an article with audio to Trash, which deletes the audio for good. */
@Composable
internal fun TrashAudioDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.audio_trash_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.audio_trash_deletes))
                Text(stringResource(R.string.audio_trash_restore))
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) { Text(stringResource(R.string.action_move_to_trash)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_cancel)) } },
    )
}

/** Shown when the reader scrolled away from what the voice is reading. */
@Composable
internal fun BackToVoiceChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Icon(painterResource(R.drawable.ic_headphones), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.audio_back_to_voice), modifier = Modifier.padding(start = 8.dp))
    }
}

/** The snackbar text for [message]. */
@Composable
internal fun audioMessageText(message: AudioMessage): String =
    when (message) {
        is AudioMessage.CreateFailed -> message.reason ?: stringResource(R.string.audio_create_failed)
        AudioMessage.ChangedOnServer -> stringResource(R.string.audio_changed_on_server)
        AudioMessage.DownloadFailed -> stringResource(R.string.audio_download_failed)
        AudioMessage.DeleteFailed -> stringResource(R.string.audio_delete_failed)
        AudioMessage.Deleted -> stringResource(R.string.audio_deleted)
    }
