package com.bencsik.brio

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.media3.session.MediaSession
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
    private var mediaSession: MediaSession? = null
    private var playerView: PlayerView? = null
    private var pipMode by mutableStateOf(false)
    private var pipSupported = false
    private var television = false
    private var position by mutableLongStateOf(0)
    private var duration by mutableLongStateOf(0)
    private var showActions by mutableStateOf(true)
    private var controlsTimer: Job? = null
    private var error by mutableStateOf<String?>(null)
    private var next by mutableStateOf<Pair<Int, Int>?>(null)
    private var nextLoading by mutableStateOf(false)
    private var nextLookupFailed by mutableStateOf(false)
    private var retriedNextLookup = false
    private var finished by mutableStateOf(false)
    private var introSkipped by mutableStateOf(false)
    private val introFocusRequester = FocusRequester()
    private var focusIntroAction by mutableStateOf(false)
    private val playerActionFocusRequester = FocusRequester()
    private var focusPlayerAction by mutableStateOf(false)
    private var episodePicker by mutableStateOf(false)
    private var episodeSeasons by mutableStateOf<List<Season>>(emptyList())
    private var selectedEpisodeSeason by mutableIntStateOf(1)
    private var seasonEpisodes by mutableStateOf<List<Episode>>(emptyList())
    private var episodeCatalogLoading by mutableStateOf(false)
    private var episodeCatalogError by mutableStateOf<String?>(null)
    private var episodeCatalogGeneration = 0
    private var episodeInfoJob: Job? = null
    private var episodeSeasonsJob: Job? = null
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
    private var addServer by mutableStateOf(false)
    private var newServerUrl by mutableStateOf("")
    private var serverError by mutableStateOf<String?>(null)
    private val attemptedServers = mutableSetOf<Int>()
    private var servers: List<Stream> = emptyList()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrioStore(this)
        val parsed = runCatching { Gson().fromJson(intent.getStringExtra("playback"), Playback::class.java) }.getOrNull()
        if (parsed == null) { finish(); return }
        playback = savedInstanceState?.getString("playback")?.let { Gson().fromJson(it, Playback::class.java) } ?: parsed
        television = isTelevisionDevice()
        pipSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            (!television || Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        onBackPressedDispatcher.addCallback(this) {
            when {
                addServer -> addServer = false
                serverPicker -> serverPicker = false
                episodePicker -> closeEpisodePicker()
                error != null -> error = null
                !connecting && !showActions -> {
                    revealControls()
                    if (!television) playerView?.showController()
                }
                else -> finish()
            }
        }
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
                            useController = !pipMode && !television
                            controllerShowTimeoutMs = 4000
                            setShowSubtitleButton(true)
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            if (!television) setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                                if (visibility == android.view.View.VISIBLE && !pipMode) revealControls()
                                else { showActions = false; controlsTimer?.cancel() }
                            })
                            isFocusable = true
                            setOnKeyListener { _, _, event -> handleTvVideoKey(event) }
                            playerView = this
                            requestFocus()
                        }
                    }, update = {
                        it.player = player
                        it.useController = !pipMode && !television
                    }, modifier = Modifier.fillMaxSize())
                    if (showActions && !pipMode) TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(20.dp).focusRing()) { Text("← ${playback.label}", color = Color.White) }
                    val stream = playback.stream
                    val actions = skipActions(stream, position, duration,
                        isEpisode = playback.season != null && playback.episode != null,
                        introDismissed = introSkipped)
                    val intro = actions.introTargetMs != null
                    val credits = actions.inCredits
                    val nearEnd = duration > 0 && position > 0 && duration - position <= 120000
                    LaunchedEffect(stream.url, actions.introTargetMs, connecting) {
                        val target = actions.introTargetMs
                        if (store.autoSkipIntro && !introSkipped && !actions.introIsEstimated && target != null && !connecting) {
                            introSkipped = true
                            seek(target)
                        }
                    }
                    LaunchedEffect(intro, credits, nearEnd, finished, connecting, nextLookupFailed, next) {
                        if (!pipMode && !connecting && (intro || credits || (next != null && (nearEnd || finished)) ||
                            (finished && nextLookupFailed))) revealControls()
                    }
                    LaunchedEffect(focusIntroAction, intro, showActions, connecting) {
                        if (focusIntroAction && intro && showActions && !connecting) {
                            introFocusRequester.requestFocus()
                            focusIntroAction = false
                        }
                    }
                    LaunchedEffect(focusPlayerAction, showActions, connecting) {
                        if (focusPlayerAction && television && showActions && !connecting && !pipMode) {
                            playerActionFocusRequester.requestFocus()
                            focusPlayerAction = false
                        }
                    }
                    if (!pipMode && !connecting && showActions && (intro || credits || (next != null && (nearEnd || finished)) ||
                        (finished && nextLookupFailed))) Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 24.dp, bottom = 110.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (intro) Button(onClick = { introSkipped = true; seek(actions.introTargetMs!!) },
                            modifier = Modifier.focusRequester(introFocusRequester).focusRing()) {
                            Text(if (actions.introIsEstimated) "Skip intro · +90s" else "Skip intro")
                        }
                        if (credits && actions.creditsTargetMs != null) OutlinedButton(onClick = { seek(actions.creditsTargetMs!!) }, modifier = Modifier.focusRing()) { Text("Skip credits") }
                        if (next != null && (credits || nearEnd || finished)) Button(onClick = ::playNext, enabled = !nextLoading, modifier = Modifier.focusRing()) { Text(if (nextLoading) "Loading next…" else "Next episode ▶") }
                        if (finished && nextLookupFailed) OutlinedButton(onClick = {
                            retriedNextLookup = true
                            loadNextInfo()
                        }, modifier = Modifier.focusRing()) { Text("Retry next episode lookup") }
                    }
                    if (pipMode) {
                        // Only the video surface remains visible in the system PiP window.
                    } else if (connecting) {
                        Box(Modifier.fillMaxSize().background(Color.Black)) {
                            TextButton(onClick = { finish() }, modifier = Modifier.align(Alignment.TopStart).padding(20.dp).focusRing()) { Text("← Back", color = Color.White) }
                            if (countdown > 0) Text("Next server in ${countdown}s", color = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp))
                            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                CircularProgressIndicator()
                                Text(if (countdown > 0) "Trying another server…" else "Connecting to server ${serverIndex + 1}…", color = Color.White)
                                Text("Looking for a working server. You can also choose one below.", color = Color.LightGray)
                                OutlinedButton(onClick = { serverPicker = true }, modifier = Modifier.focusRing()) { Text("Try different server") }
                            }
                        }
                    } else if (showActions) {
                        Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(20.dp),
                            horizontalAlignment = Alignment.End) {
                            TextButton(onClick = { serverPicker = true }, modifier = Modifier.focusRing()) { Text("Servers · ${servers.size}") }
                            if (playback.season != null && playback.episode != null)
                                TextButton(onClick = ::openEpisodePicker, modifier = Modifier.focusRing()) { Text("Seasons & episodes") }
                            if (pipSupported && exo?.playbackState == Player.STATE_READY)
                                OutlinedButton(onClick = ::minimize, modifier = Modifier.focusRing()) { Text("▣ Minimize") }
                        }
                    }
                    if (television && showActions && !pipMode && !connecting) Row(
                        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { finish() }, modifier = Modifier.focusRing()) { Text("← Back") }
                        OutlinedButton(onClick = { seekRelative(-30_000); revealControls() },
                            modifier = Modifier.focusRequester(playerActionFocusRequester).focusRing()) { Text("−30s") }
                        Button(onClick = ::togglePlayback, modifier = Modifier.focusRing()) {
                            Text(if (player?.isPlaying == true) "Ⅱ Pause" else "▶ Play")
                        }
                        OutlinedButton(onClick = { seekRelative(30_000); revealControls() }, modifier = Modifier.focusRing()) { Text("+30s") }
                        if (playback.season != null && playback.episode != null)
                            OutlinedButton(onClick = ::openEpisodePicker, modifier = Modifier.focusRing()) { Text("Episodes") }
                    }
                    if (!pipMode && serverPicker) AlertDialog(onDismissRequest = { serverPicker = false }, title = { Text("Choose a server") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (servers.size == 1) Text("Only one source returned a video for this title. Add a direct link here or add provider endpoints in Settings → Sources.")
                            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(servers) { index, source ->
                                    TextButton(onClick = { serverPicker = false; attemptedServers.clear(); tryServer(index) }, modifier = Modifier.focusRing()) {
                                        Text("${index + 1}. ${source.name} · ${source.quality}")
                                    }
                                }
                            }
                        } }, confirmButton = { Row {
                            TextButton(onClick = { serverPicker = false; newServerUrl = ""; serverError = null; addServer = true },
                                modifier = Modifier.focusRing()) { Text("Add video link") }
                            TextButton(onClick = { serverPicker = false }, modifier = Modifier.focusRing()) { Text("Close") }
                        } })
                    if (!pipMode && addServer) AlertDialog(onDismissRequest = { addServer = false },
                        title = { Text("Add a video link") },
                        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Save a direct HTTPS video link for ${playback.label}. It will appear as another server on this device.")
                            OutlinedTextField(newServerUrl, { newServerUrl = it; serverError = null },
                                label = { Text("HTTPS video URL") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRing())
                            serverError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        } },
                        confirmButton = { Button(onClick = {
                            runCatching { store.addPersonalStream(playback.key, newServerUrl) }
                                .onSuccess { source ->
                                    servers = (servers + source).distinctBy { it.url to it.headers }
                                    addServer = false; attemptedServers.clear()
                                    tryServer(servers.indexOfLast { it.url == source.url && it.headers == source.headers })
                                }.onFailure { serverError = it.message ?: "Enter a direct HTTPS video link." }
                        }, enabled = newServerUrl.isNotBlank(), modifier = Modifier.focusRing()) { Text("Save & play") } },
                        dismissButton = { TextButton(onClick = { addServer = false }, modifier = Modifier.focusRing()) { Text("Cancel") } })
                    if (!pipMode && episodePicker) AlertDialog(
                        onDismissRequest = { if (!nextLoading) closeEpisodePicker() },
                        title = { Text("Seasons & episodes") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(episodeSeasons, key = { it.number }) { season ->
                                        OutlinedButton(onClick = { loadSeasonEpisodes(season.number) },
                                            enabled = !nextLoading,
                                            border = androidx.compose.foundation.BorderStroke(1.dp,
                                                if (selectedEpisodeSeason == season.number) MaterialTheme.colorScheme.primary else Color.Gray),
                                            modifier = Modifier.focusRing()) { Text(season.name) }
                                    }
                                }
                                if (episodeCatalogLoading) CircularProgressIndicator()
                                episodeCatalogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                LazyColumn(Modifier.heightIn(max = 360.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(seasonEpisodes, key = { it.number }) { episode ->
                                        OutlinedButton(
                                            onClick = { playEpisode(selectedEpisodeSeason to episode.number, fromPicker = true) },
                                            enabled = !nextLoading, modifier = Modifier.fillMaxWidth().focusRing()
                                        ) {
                                            Text("▶  E${episode.number} · ${episode.name}", modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                                if (nextLoading) Text("Loading episode…")
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = ::closeEpisodePicker, enabled = !nextLoading,
                                modifier = Modifier.focusRing()) { Text("Close") }
                        }
                    )
                    if (!pipMode) error?.let { message ->
                        AlertDialog(onDismissRequest = { error = null }, title = { Text("Playback needs attention") }, text = { Text(message) },
                            confirmButton = { Button(onClick = { error = null; attemptedServers.clear(); tryServer(serverIndex) }, modifier = Modifier.focusRing()) { Text("Retry") } },
                            dismissButton = { TextButton(onClick = { finish() }, modifier = Modifier.focusRing()) { Text("Back to sources") } })
                    }
                }
            }
        }
        lifecycleScope.launch {
            while (true) {
                exo?.let { player ->
                    position = player.currentPosition
                    duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
                    if (player.isPlaying && position > 0 && position / 1000 % 10 == 0L) save()
                }
                delay(1000)
            }
        }
        loadNextInfo()
    }
    override fun onStart() { super.onStart(); startPlayer() }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        pipMode = inPictureInPictureMode
        if (inPictureInPictureMode) {
            showActions = false; controlsTimer?.cancel(); playerView?.hideController()
        } else revealControls()
    }
    override fun onStop() {
        controlsTimer?.cancel()
        episodeInfoJob?.cancel(); episodeSeasonsJob?.cancel()
        connectionGeneration++
        connectionJob?.cancel(); timeoutJob?.cancel()
        exo?.let { resumePosition = it.currentPosition; resumePlay = it.playWhenReady; save() }
        releasePlayer()
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
        exo?.let { resumePosition = it.currentPosition; save() }
        releasePlayer()
        serverIndex = index; attemptedServers.add(index)
        connecting = true; countdown = 0; error = null; finished = false; introSkipped = false; focusIntroAction = false; focusPlayerAction = false
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
        exo?.let { resumePosition = it.currentPosition }; releasePlayer()
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
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .setSeekBackIncrementMs(30_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        exo = player
        mediaSession = MediaSession.Builder(this, player).build()
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
                            if (playbackState == Player.STATE_READY && !pipMode) revealControls()
                            if (playbackState == Player.STATE_ENDED) {
                                save()
                                maybeAutoplayNext()
                            }
                        }
                    }
                }
            })
            setMediaItem(MediaItem.Builder().setUri(playback.stream.url).setMimeType(playback.stream.mimeType).build())
            seekTo(resumePosition)
            armTimeout(generation)
            prepare(); playWhenReady = resumePlay
        }
    }
    private fun save() { exo?.let { player -> store.saveProgress(playback, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0) } }
    private fun releasePlayer() {
        mediaSession?.release(); mediaSession = null
        playerView?.player = null
        exo?.release(); exo = null
    }
    private fun minimize() {
        if (!pipSupported || connecting || error != null || exo?.playbackState != Player.STATE_READY) return
        val source = Rect()
        playerView?.getGlobalVisibleRect(source)
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).apply {
            if (!source.isEmpty) setSourceRectHint(source)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setTitle(playback.title.name)
                setSubtitle(playback.label)
            }
        }.build()
        pipMode = true
        showActions = false; controlsTimer?.cancel(); playerView?.hideController()
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) {
            pipMode = false; revealControls()
            Toast.makeText(this, "Picture-in-picture isn't available on this device.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun revealControls() {
        showActions = true
        controlsTimer?.cancel()
        controlsTimer = lifecycleScope.launch {
            delay(5000)
            if (exo?.isPlaying == true && !serverPicker && !episodePicker && !addServer && error == null) {
                showActions = false
                playerView?.hideController()
                playerView?.requestFocus()
            }
        }
    }
    private fun seek(target: Long) { exo?.seekTo(target.coerceAtLeast(0).let { if (duration > 0) it.coerceAtMost(duration) else it }) }
    private fun seekRelative(deltaMs: Long) { seek((exo?.currentPosition ?: position) + deltaMs) }
    private fun togglePlayback() {
        exo?.let { if (it.isPlaying) it.pause() else it.play() }
        revealControls()
        if (!television) playerView?.showController()
    }
    private fun openEpisodePicker() {
        val season = playback.season ?: return
        episodePicker = true
        episodeSeasons = listOf(Season(season, if (season == 0) "Specials" else "Season $season"))
        loadSeasonEpisodes(season)
        episodeSeasonsJob?.cancel()
        val key = playback.key
        val title = playback.title
        episodeSeasonsJob = lifecycleScope.launch {
            try {
                val available = BrioCatalog(store.tmdbToken).details(title).seasons
                if (episodePicker && playback.key == key && available.isNotEmpty()) {
                    episodeSeasons = (available + episodeSeasons).distinctBy { it.number }.sortedBy { it.number }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The current season remains selectable. */ }
        }
    }
    private fun closeEpisodePicker() {
        episodePicker = false
        revealControls()
    }
    private fun loadSeasonEpisodes(season: Int) {
        episodeInfoJob?.cancel()
        val generation = ++episodeCatalogGeneration
        selectedEpisodeSeason = season
        seasonEpisodes = emptyList()
        episodeCatalogLoading = true
        episodeCatalogError = null
        val title = playback.title
        episodeInfoJob = lifecycleScope.launch {
            try {
                seasonEpisodes = BrioCatalog(store.tmdbToken).episodes(title, season)
                if (seasonEpisodes.isEmpty()) episodeCatalogError = "No episodes are listed for this season."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { episodeCatalogError = e.message ?: "Could not load episodes. Try this season again." }
            finally { if (generation == episodeCatalogGeneration) episodeCatalogLoading = false }
        }
    }
    private fun loadNextInfo() {
        nextInfoJob?.cancel()
        next = null
        nextLookupFailed = false
        val season = playback.season ?: return
        val episode = playback.episode ?: return
        val title = playback.title
        nextInfoJob = lifecycleScope.launch {
            try {
                val api = BrioCatalog(store.tmdbToken)
                next = followingEpisodeTarget(season, episode,
                    episodes = { api.episodes(title, it) },
                    seasons = { api.details(title).seasons })
                // The video can end while the catalog request is still in flight.
                maybeAutoplayNext()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                nextLookupFailed = true
                maybeAutoplayNext()
            }
        }
    }
    private fun maybeAutoplayNext() {
        if (!finished || !store.autoplayNext || loadingNext) return
        if (next != null) {
            playNext()
        } else if (nextLookupFailed && !retriedNextLookup) {
            retriedNextLookup = true
            val episodeKey = playback.key
            lifecycleScope.launch {
                delay(1500)
                if (finished && playback.key == episodeKey && next == null && nextInfoJob?.isActive != true) loadNextInfo()
            }
        }
    }
    private fun playNext() {
        val target = next ?: return
        playEpisode(target)
    }
    private fun playEpisode(target: Pair<Int, Int>, fromPicker: Boolean = false) {
        if (loadingNext) return
        loadingNext = true; nextLoading = true
        lifecycleScope.launch {
            try {
                val streams = store.resolveSources(StreamRequest(playback.title.id, "tv", target.first, target.second, playback.title.imdbId))
                check(streams.isNotEmpty()) {
                    if (fromPicker) "No video source was found for Season ${target.first}, Episode ${target.second}. Choose another episode or source."
                    else "No streams are available for the next episode. Return to the catalog to choose another source."
                }
                connectionGeneration++; connectionJob?.cancel(); timeoutJob?.cancel()
                save(); releasePlayer()
                servers = streams.distinctBy { it.url to it.headers }; serverIndex = 0; attemptedServers.clear()
                playback = Playback(playback.title, servers.first(), target.first, target.second, servers)
                resumePosition = 0; resumePlay = true; position = 0; duration = 0; finished = false; introSkipped = false; focusIntroAction = false; focusPlayerAction = false
                retriedNextLookup = false
                episodePicker = false
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) startPlayer()
                loadNextInfo()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (fromPicker) episodeCatalogError = e.message ?: "Couldn't load this episode."
                else error = e.message ?: "Couldn't load the next episode."
            }
            finally { loadingNext = false; nextLoading = false }
        }
    }
    private fun handleTvVideoKey(event: KeyEvent): Boolean {
        val onVideo = playerView?.hasFocus() == true || !showActions
        if (television && onVideo && !pipMode && !connecting && !serverPicker &&
            !episodePicker && !addServer && error == null) {
            val key = event.keyCode
            if (key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_ESCAPE) {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                return true
            }
            if (key in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    when (key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { seekRelative(-30_000); revealControls() }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> { seekRelative(30_000); revealControls() }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            revealControls()
                            val intro = skipActions(playback.stream, position, duration,
                                isEpisode = playback.season != null && playback.episode != null,
                                introDismissed = introSkipped).introTargetMs != null
                            if (key == KeyEvent.KEYCODE_DPAD_UP && intro) focusIntroAction = true
                            else focusPlayerAction = true
                        }
                        else -> togglePlayback()
                    }
                }
                return true
            }
        }
        return false
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleTvVideoKey(event)) return true
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlayback(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { exo?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { exo?.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekRelative(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seekRelative(-30_000); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (handleTvVideoKey(event)) return true
        return super.onKeyUp(keyCode, event)
    }
}

