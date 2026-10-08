package com.bencsik.brio

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.withTimeout

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
    private var connectionJob: Job? = null
    private var timeoutJob: Job? = null
    private var connectionGeneration = 0
    private var connecting by mutableStateOf(true)
    private var serverIndex by mutableIntStateOf(0)
    private var countdown by mutableIntStateOf(0)
    private var serverPicker by mutableStateOf(false)
    private val attemptedServers = mutableSetOf<Int>()
    private var servers: List<Stream> = emptyList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrioStore(this)
        val parsed = runCatching { Gson().fromJson(intent.getStringExtra("playback"), Playback::class.java) }.getOrNull()
        if (parsed == null) { finish(); return }
        playback = savedInstanceState?.getString("playback")?.let { Gson().fromJson(it, Playback::class.java) } ?: parsed
        servers = (listOf(playback.stream) + playback.alternatives.orEmpty()).distinctBy { it.url to it.headers }
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
                    if (!connecting && (intro || credits || showActions || finished)) Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = if (showActions) 110.dp else 36.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (intro) Button(onClick = { seek(actions.introTargetMs!!) }) { Text("Skip intro") }
                        else if (showActions && !credits && !finished) OutlinedButton(onClick = { seek(position + 90000) }) { Text("Skip 90 seconds") }
                        if (next != null && (credits || finished || showActions)) Button(onClick = ::playNext, enabled = !nextLoading) { Text(if (nextLoading) "Loading next…" else "Next episode ▶") }
                        else if (credits && actions.creditsTargetMs != null) Button(onClick = { seek(actions.creditsTargetMs!!) }) { Text("Skip credits") }
                    }
                    if (connecting) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart).padding(20.dp)) { Text("← Back", color = Color.White) }
                            if (countdown > 0) Text("Next server in ${countdown}s", color = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
                            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                CircularProgressIndicator()
                                Text(if (countdown > 0) "Trying another server…" else "Connecting to server ${serverIndex + 1}…", color = Color.White)
                                Text("Looking for a working server. You can also choose one below.", color = Color.LightGray)
                                OutlinedButton(onClick = { serverPicker = true }) { Text("Try different server") }
                            }
                        }
                    } else if (showActions) {
                        TextButton(onClick = { serverPicker = true }, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp)) { Text("Servers") }
                    }
                    if (serverPicker) AlertDialog(onDismissRequest = { serverPicker = false }, title = { Text("Choose a server") },
                        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsIndexed(servers) { index, source ->
                                TextButton(onClick = { serverPicker = false; attemptedServers.clear(); tryServer(index) }) {
                                    Text("${index + 1}. ${source.name} · ${source.quality}")
                                }
                            }
                        } }, confirmButton = { TextButton(onClick = { serverPicker = false }) { Text("Close") } })
                    error?.let { message ->
                        AlertDialog(onDismissRequest = { error = null }, title = { Text("Playback needs attention") }, text = { Text(message) },
                            confirmButton = { Button(onClick = { error = null; attemptedServers.clear(); tryServer(serverIndex) }) { Text("Retry") } },
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
        connectionGeneration++
        connectionJob?.cancel(); timeoutJob?.cancel()
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
        if (exo == null && connectionJob?.isActive != true) tryServer(serverIndex)
    }
    private fun tryServer(index: Int) {
        connectionJob?.cancel(); timeoutJob?.cancel()
        val generation = ++connectionGeneration
        exo?.let { resumePosition = it.currentPosition; save(); it.release() }
        exo = null; playerView?.player = null
        serverIndex = index; attemptedServers.add(index)
        connecting = true; countdown = 0; error = null; finished = false
        connectionJob = lifecycleScope.launch {
            try {
                val source = withTimeout(20000) { prepareStream(servers[index], store.debridToken) }
                if (generation != connectionGeneration) return@launch
                playback = playback.copy(stream = source, alternatives = servers)
                attachPlayer(generation)
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                serverFailed(generation)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { serverFailed(generation) }
        }
    }
    private fun armTimeout(generation: Int) {
        timeoutJob?.cancel()
        connecting = true
        timeoutJob = lifecycleScope.launch {
            delay(20000)
            if (generation == connectionGeneration) serverFailed(generation)
        }
    }
    private fun serverFailed(generation: Int) {
        if (generation != connectionGeneration) return
        timeoutJob?.cancel()
        // Invalidate callbacks from this failed player before releasing it.
        val failedGeneration = ++connectionGeneration
        exo?.let { resumePosition = it.currentPosition; it.release() }; exo = null; playerView?.player = null
        val nextServer = nextUntriedServer(servers.size, serverIndex, attemptedServers)
        if (nextServer == null) {
            connecting = false; countdown = 0
            error = "None of the available servers could play this video. Retry, choose a server, or return to browsing."
            return
        }
        connecting = true
        connectionJob = lifecycleScope.launch {
            for (seconds in 3 downTo 1) {
                countdown = seconds
                delay(1000)
            }
            if (failedGeneration == connectionGeneration) tryServer(nextServer)
        }
    }
    private fun attachPlayer(generation: Int) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(playback.stream.headers)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(20000)
        val data = DefaultDataSource.Factory(this, http)
        val player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(data)).build()
        exo = player
        player.apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlayerError(playbackError: PlaybackException) { serverFailed(generation) }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (generation != connectionGeneration) return
                    finished = playbackState == Player.STATE_ENDED
                    when (playbackState) {
                        Player.STATE_BUFFERING -> armTimeout(generation)
                        Player.STATE_READY, Player.STATE_ENDED -> {
                            timeoutJob?.cancel(); connecting = false; countdown = 0
                        }
                    }
                }
            })
            setMediaItem(MediaItem.fromUri(playback.stream.url))
            seekTo(resumePosition)
            armTimeout(generation)
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
        nextInfoJob = lifecycleScope.launch {
            try {
                val api = BrioCatalog(store.tmdbToken)
                val current = api.episodes(playback.title, season)
                val following = followingEpisode(current, episode)
                next = if (following != null) season to following.number else {
                    val nextSeason = api.details(playback.title).seasons.filter { it.number > season }.minByOrNull { it.number }
                    nextSeason?.let { s -> api.episodes(playback.title, s.number).firstOrNull()?.let { s.number to it.number } }
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
                val streams = resolveConfiguredSources(store.provider, store.tmdbToken, StreamRequest(playback.title.id, "tv", target.first, target.second, playback.title.imdbId))
                check(streams.isNotEmpty()) { "No streams are available for the next episode. Return to the catalog to choose another source." }
                connectionGeneration++; connectionJob?.cancel(); timeoutJob?.cancel()
                save(); exo?.release(); exo = null
                servers = streams.distinctBy { it.url to it.headers }; serverIndex = 0; attemptedServers.clear()
                playback = Playback(playback.title, servers.first(), target.first, target.second, servers)
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
