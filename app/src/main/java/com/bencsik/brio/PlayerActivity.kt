package com.bencsik.brio

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.bencsik.brio.core.*
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private lateinit var playback: Playback
    private lateinit var store: BrioStore
    private var exo by mutableStateOf<ExoPlayer?>(null)
    private var playerView: PlayerView? = null
    private var position by mutableLongStateOf(0)
    private var duration by mutableLongStateOf(0)
    private var showActions by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var next by mutableStateOf<Pair<Int, Int>?>(null)
    private var nextLoading by mutableStateOf(false)
    private var finished by mutableStateOf(false)
    private var resumePosition = 0L
    private var resumePlay = true
    private var loadingNext = false
    private var nextInfoJob: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrioStore(this)
        val parsed = runCatching { Gson().fromJson(intent.getStringExtra("playback"), Playback::class.java) }.getOrNull()
        if (parsed == null) { finish(); return }
        playback = savedInstanceState?.getString("playback")?.let { Gson().fromJson(it, Playback::class.java) } ?: parsed
        resumePosition = savedInstanceState?.getLong("position") ?: store.history().firstOrNull { it.playback.key == playback.key }?.let {
            if (it.durationMs > 0 && it.positionMs >= it.durationMs - 5000) 0L else it.positionMs
        } ?: 0L
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFB8F36A), onPrimary = Color(0xFF0B101A))) {
                val player = exo
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (player != null) AndroidView(factory = { context ->
                        PlayerView(context).apply {
                            this.player = player
                            keepScreenOn = true
                            controllerShowTimeoutMs = 4000
                            setShowSubtitleButton(true)
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> showActions = visibility == android.view.View.VISIBLE })
                            playerView = this
                            requestFocus()
                        }
                    }, update = { it.player = player }, modifier = Modifier.fillMaxSize())
                    if (showActions) TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp)) { Text("← ${playback.label}", color = Color.White) }
                    val stream = playback.stream
                    val actions = skipActions(stream, position, duration)
                    val intro = actions.introTargetMs != null
                    val credits = actions.inCredits
                    if (intro || credits || showActions || finished) Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = if (showActions) 110.dp else 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (intro) Button(onClick = { seek(actions.introTargetMs!!) }) { Text("Skip intro") }
                        else if (showActions && !credits && !finished) OutlinedButton(onClick = { seek(position + 90000) }) { Text("Skip 90 seconds") }
                        if (next != null && (credits || finished || showActions)) Button(onClick = ::playNext, enabled = !nextLoading) { Text(if (nextLoading) "Loading next…" else "Next episode ▶") }
                        else if (credits && actions.creditsTargetMs != null) Button(onClick = { seek(actions.creditsTargetMs!!) }) { Text("Skip credits") }
                    }
                    error?.let { message ->
                        AlertDialog(onDismissRequest = { error = null }, title = { Text("Playback needs attention") }, text = { Text(message) },
                            confirmButton = { Button(onClick = { error = null; if (exo == null) startPlayer() else { exo?.prepare(); exo?.play() } }) { Text("Retry") } },
                            dismissButton = { TextButton(onClick = { finish() }) { Text("Back to sources") } })
                    }
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                exo?.let { player ->
                    position = player.currentPosition
                    duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                    if (position > 0 && position / 1000 % 10 == 0L) save()
                }
                delay(1000)
            }
        }
        loadNextInfo()
    }
    override fun onStart() { super.onStart(); startPlayer() }
    override fun onStop() {
        exo?.let { resumePosition = it.currentPosition; resumePlay = it.playWhenReady; save(); it.release() }
        exo = null; playerView?.player = null
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", exo?.currentPosition ?: resumePosition)
        outState.putString("playback", Gson().toJson(playback))
        super.onSaveInstanceState(outState)
    }
    private fun startPlayer() {
        if (exo != null) return
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(playback.stream.headers)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val data = DefaultDataSource.Factory(this, http)
        exo = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerError(playbackError: PlaybackException) { error = "This stream couldn't play (${playbackError.errorCodeName}). Retry or return to choose another source." }
                override fun onPlaybackStateChanged(playbackState: Int) { finished = playbackState == Player.STATE_ENDED }
            })
            setMediaItem(MediaItem.fromUri(playback.stream.url))
            seekTo(resumePosition)
            prepare(); playWhenReady = resumePlay
        }
    }
    private fun save() { exo?.let { player -> store.saveProgress(playback, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0) } }
    private fun seek(target: Long) { exo?.seekTo(target.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }) }
    private fun loadNextInfo() {
        nextInfoJob?.cancel()
        next = null
        val season = playback.season ?: return
        val episode = playback.episode ?: return
        if (store.tmdbToken.isBlank()) return
        nextInfoJob = lifecycleScope.launch {
            try {
                val api = MediaRepository(store.tmdbToken)
                val current = api.episodes(playback.title.id, season)
                val following = followingEpisode(current, episode)
                next = if (following != null) season to following.number else {
                    val nextSeason = api.details(playback.title).seasons.filter { it.number > season }.minByOrNull { it.number }
                    nextSeason?.let { s -> api.episodes(playback.title.id, s.number).firstOrNull()?.let { s.number to it.number } }
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* Playback remains usable when catalog is offline. */ }
        }
    }
    private fun playNext() {
        if (loadingNext) return
        val target = next ?: return
        loadingNext = true; nextLoading = true
        lifecycleScope.launch {
            try {
                check(store.provider.isNotBlank()) { "Add a stream provider in Settings to play the next episode." }
                val streams = resolveConfiguredSources(store.provider, store.tmdbToken, StreamRequest(playback.title.id, "tv", target.first, target.second))
                check(streams.isNotEmpty()) { "No streams are available for the next episode. Return to the catalog to choose another source." }
                val nextStream = prepareStream(streams.first(), store.debridToken)
                save(); exo?.release(); exo = null
                playback = Playback(playback.title, nextStream, target.first, target.second)
                resumePosition = 0; resumePlay = true; position = 0; duration = 0; finished = false
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startPlayer()
                loadNextInfo()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Couldn't load the next episode." }
            finally { loadingNext = false; nextLoading = false }
        }
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { exo?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { exo?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(position + 10000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(position - 10000); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
}
