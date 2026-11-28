package dev.vvivid.newsfeed.news

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.vvivid.newsfeed.R
import dev.vvivid.newsfeed.audio.ArticlePlayer
import dev.vvivid.newsfeed.audio.AudioPlaybackService
import dev.vvivid.newsfeed.audio.PlayerState
import dev.vvivid.newsfeed.reader.PlayPauseButton
import dev.vvivid.newsfeed.reader.clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The article audio loaded in the player, as the home screen shows it.
 *
 * @property elapsedMs how far the voice is, and [totalMs] how long the audio is; 0 when the audio
 *   was started without its parts' lengths, by an older version of the app.
 * @property section the heading of the section being read, if any.
 */
data class NowPlaying(
    val articleId: String,
    val title: String,
    val section: String?,
    val isPlaying: Boolean,
    val elapsedMs: Long,
    val totalMs: Long,
)

/** What the home screen's player does. */
class NowPlayingActions(
    val onToggle: () -> Unit = {},
    val onSkip: (deltaMs: Long) -> Unit = {},
    /** Opens the article the audio belongs to, where the reader follows the voice. */
    val onOpen: (articleId: String) -> Unit = {},
)

/**
 * The audio that keeps playing after the reader closes, for the home screen: what it is and how far
 * it got, with play, pause and skipping. Connects to the player when created.
 *
 * @param newPlayer connects to the audio player, refreshing its state in the given scope.
 */
class NowPlayingViewModel(
    newPlayer: (CoroutineScope) -> ArticlePlayer,
) : ViewModel() {
    private val player = newPlayer(viewModelScope)

    val nowPlaying: StateFlow<NowPlaying?> =
        player.state
            .map(::nowPlayingOf)
            .stateIn(viewModelScope, SharingStarted.Eagerly, nowPlayingOf(player.state.value))

    fun toggle() {
        if (player.state.value.isPlaying) player.pause() else player.resume()
    }

    fun skip(deltaMs: Long) = player.skip(deltaMs)

    override fun onCleared() {
        player.release()
    }
}

/**
 * What the home screen shows for [state]: nothing when no article audio is loaded, or once it has
 * played to the end and stopped.
 */
internal fun nowPlayingOf(state: PlayerState): NowPlaying? {
    val articleId = state.articleId ?: return null
    val finished = !state.isPlaying && state.totalMs > 0 && state.elapsedMs >= state.totalMs - FINISHED_MARGIN_MS
    if (finished) return null
    return NowPlaying(
        articleId = articleId,
        title = state.title.orEmpty(),
        section = state.section,
        isPlaying = state.isPlaying,
        elapsedMs = state.elapsedMs,
        totalMs = state.totalMs,
    )
}

/** Audio this close to its end counts as played through. */
private const val FINISHED_MARGIN_MS = 1_000L

/**
 * The player floating above the bottom of the home screen while an article's audio is loaded:
 * the title, the section and time, skipping back and forward, and play or pause. Tapping the title
 * opens the article where the voice is.
 */
@Composable
internal fun HomePlayer(
    nowPlaying: NowPlaying,
    actions: NowPlayingActions,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClickLabel = stringResource(R.string.now_playing_open)) { actions.onOpen(nowPlaying.articleId) }
                            .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_headphones),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = nowPlaying.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = detail(nowPlaying),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                IconButton(onClick = { actions.onSkip(-AudioPlaybackService.SEEK_INCREMENT_MS) }) {
                    Icon(painterResource(R.drawable.ic_replay_15), contentDescription = stringResource(R.string.audio_back_15))
                }
                PlayPauseButton(
                    isPlaying = nowPlaying.isPlaying,
                    onListen = actions.onToggle,
                    onPause = actions.onToggle,
                    size = 48,
                )
                IconButton(onClick = { actions.onSkip(AudioPlaybackService.SEEK_INCREMENT_MS) }) {
                    Icon(painterResource(R.drawable.ic_forward_15), contentDescription = stringResource(R.string.audio_forward_15))
                }
            }
            if (nowPlaying.totalMs > 0) {
                LinearProgressIndicator(
                    progress = { nowPlaying.elapsedMs.toFloat() / nowPlaying.totalMs },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    drawStopIndicator = {},
                )
            }
        }
    }
}

/** "A cascade, not a score · 8:21 / 14:33", leaving out what isn't known. */
private fun detail(nowPlaying: NowPlaying): String {
    val time = if (nowPlaying.totalMs > 0) clock(nowPlaying.elapsedMs) + " / " + clock(nowPlaying.totalMs) else null
    return listOfNotNull(nowPlaying.section, time).joinToString(" · ")
}
