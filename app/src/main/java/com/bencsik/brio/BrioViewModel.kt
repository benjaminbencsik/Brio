package com.bencsik.brio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bencsik.brio.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrioViewModel(application: Application) : AndroidViewModel(application) {
    val store = BrioStore(application)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var catalogJob: Job? = null
    private var categoryJob: Job? = null
    private var homeJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var sourceJob: Job? = null
    private var debridJob: Job? = null
    private var page = 1
    private var lastQuery = ""
    private var lastKind = "movie"
    private var lastGenre: String? = null
    val cloud = FirebaseLibrary.get(application)
    private val stopLibraryObserver = store.observeLibrary { refreshLibrary() }
    init { reload(); loadHomeRows() }
    override fun onCleared() { stopLibraryObserver(); super.onCleared() }
    fun resumeCloud(playback: Playback, callback: (Playback) -> Unit) {
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            update { it.copy(resolving = true, error = null) }
            attempt {
                val streams = resolveConfiguredSources(store.provider, store.tmdbToken,
                    StreamRequest(playback.title.id, playback.title.kind, playback.season, playback.episode, playback.title.imdbId))
                check(streams.isNotEmpty()) { "No source is available for this saved episode." }
                update { it.copy(resolving = false) }
                callback(playback.copy(stream = streams.first(), alternatives = streams))
            }
        }
    }
    private fun update(block: (UiState) -> UiState) { _state.value = block(_state.value) }
    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { update { it.copy(error = e.message ?: "Connection failed. Please try again.", loading = false, resolving = false, debridLoading = false) } }
    }
    fun dismissError() = update { it.copy(error = null) }
    fun reload() { refreshLibrary(); browse(lastKind, lastQuery, genre = lastGenre) }
    fun refreshLibrary() = update { it.copy(watchlist = store.watchlist(), history = store.history(),
        watchedEpisodes = (visibleCloudProgress(store.cloudRecords()).filter { r -> r.durationMs > 0 && r.positionMs >= r.durationMs * 0.9 }.map { r -> r.key } +
            store.history().filter { h -> h.durationMs > 0 && h.positionMs >= h.durationMs * 0.9 }.map { h -> h.playback.key }).toSet()) }
    fun browse(kind: String = "movie", query: String = "", more: Boolean = false, genre: String? = null) {
        catalogJob?.cancel()
        lastKind = kind; lastQuery = query; lastGenre = genre
        if (!more) page = 1 else page++
        catalogJob = viewModelScope.launch {
            var succeeded = false
            update { it.copy(loading = true, error = null, titles = if (more) it.titles else emptyList()) }
            attempt {
                val titles = BrioCatalog(store.tmdbToken).let {
                    if (query.isNotBlank()) it.search(query, page)
                    else if (genre != null) it.category(kind, genre, page)
                    else it.catalog(kind, page)
                }
                update { it.copy(titles = if (more) (it.titles + titles).distinctBy { t -> t.key } else titles, loading = false, canLoadMore = titles.size >= 20 && (store.tmdbToken.isNotBlank() || query.isBlank())) }
                succeeded = true
            }
            if (!succeeded && more) page--
        }
    }
    fun loadCategories(kind: String) {
        categoryJob?.cancel()
        val sections = if (kind == "movie") MOVIE_CATEGORIES else TV_CATEGORIES
        update { it.copy(categoryRows = emptyMap(), categoriesLoading = true, categoryError = null) }
        categoryJob = viewModelScope.launch {
            val slots = Semaphore(3)
            coroutineScope {
                sections.forEach { (label, genre) -> launch {
                    slots.withPermit {
                        try {
                            val titles = BrioCatalog(store.tmdbToken).category(kind, genre, limit = 12)
                            update { it.copy(categoryRows = it.categoryRows + (label to titles)) }
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { update { it.copy(categoryError = e.message ?: "Some categories could not load.") } }
                    }
                } }
            }
            update { it.copy(categoriesLoading = false) }
        }
    }
    fun loadHomeRows() {
        homeJob?.cancel()
        update { it.copy(homeRows = emptyMap(), homeLoading = true) }
        homeJob = viewModelScope.launch {
            val slots = Semaphore(3)
            coroutineScope {
                HOME_SHELVES.forEach { (label, kind, genre) -> launch {
                    slots.withPermit {
                        try {
                            val titles = BrioCatalog(store.tmdbToken).category(kind, genre, limit = 12)
                            update { it.copy(homeRows = it.homeRows + (label to titles)) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* Other rows remain usable if one catalog request fails. */ }
                    }
                } }
            }
            update { it.copy(homeLoading = false) }
        }
    }
    fun select(item: Title) {
        detailJob?.cancel(); episodeJob?.cancel(); sourceJob?.cancel()
        update { it.copy(selected = Details(item), episodes = emptyList(), season = null, episode = null, streams = emptyList(), error = null, resolving = false) }
        if (item.demoUrl != null || item.kind !in listOf("movie", "tv")) return
        detailJob = viewModelScope.launch {
            attempt {
                val details = BrioCatalog(store.tmdbToken).details(item)
                update { it.copy(selected = details) }
                details.seasons.firstOrNull { it.number > 0 }?.let { chooseSeason(it.number) }
            }
        }
    }
    fun closeDetails() {
        detailJob?.cancel(); episodeJob?.cancel(); sourceJob?.cancel()
        update { it.copy(selected = null, streams = emptyList(), resolving = false) }
    }
    fun chooseSeason(number: Int) {
        val selected = _state.value.selected ?: return
        episodeJob?.cancel(); sourceJob?.cancel()
        update { it.copy(season = number, episode = null, episodes = emptyList(), streams = emptyList(), resolving = false) }
        episodeJob = viewModelScope.launch {
            attempt {
                val episodes = BrioCatalog(store.tmdbToken).episodes(selected.title, number)
                update { it.copy(episodes = episodes, episode = episodes.firstOrNull()?.number) }
            }
        }
    }
    fun chooseEpisode(number: Int) { sourceJob?.cancel(); update { it.copy(episode = number, streams = emptyList(), resolving = false) } }
    fun toggle(item: Title) { store.toggle(item); refreshLibrary() }
    fun watch(item: Title, callback: (Playback) -> Unit) {
        select(item)
        if (item.kind != "tv") findSources(callback)
    }
    fun restartSeries(callback: (Playback) -> Unit) {
        val item = _state.value.selected?.title ?: return
        if (item.kind != "tv") return
        detailJob?.cancel(); episodeJob?.cancel(); sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            update { it.copy(resolving = true, error = null, streams = emptyList()) }
            attempt {
                val episodes = BrioCatalog(store.tmdbToken).episodes(item, 1)
                check(episodes.any { it.number == 1 }) { "Season 1, Episode 1 is not available for this show." }
                val streams = resolveConfiguredSources(store.provider, store.tmdbToken,
                    StreamRequest(item.id, "tv", 1, 1, item.imdbId))
                check(streams.isNotEmpty()) { "No source was found for Season 1, Episode 1. Your progress has been kept." }
                // Reset only after a playable source has been resolved.
                store.clearSeriesProgress(item)
                refreshLibrary()
                update { it.copy(season = 1, episode = 1, episodes = episodes, streams = streams, resolving = false) }
                callback(Playback(item, streams.first(), 1, 1, streams))
            }
        }
    }
    fun findSources(onReady: ((Playback) -> Unit)? = null) {
        val snapshot = _state.value
        val item = snapshot.selected?.title ?: return
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            update { it.copy(resolving = true, streams = emptyList(), error = null) }
            attempt {
                val streams = when {
                    item.demoUrl != null -> listOf(Stream(item.demoUrl!!, "Blender open movie", "HD"))
                    item.kind == "tv" && (snapshot.season == null || snapshot.episode == null) -> error("Choose a season and episode first.")
                    else -> resolveConfiguredSources(store.provider, store.tmdbToken, StreamRequest(item.id, item.kind, snapshot.season, snapshot.episode, item.imdbId))
                }
                check(streams.isNotEmpty()) { "No sources were found. Try a direct link or another provider." }
                update { it.copy(streams = streams, resolving = false) }
                if (onReady != null) {
                    onReady(Playback(item, streams.first(), snapshot.season, snapshot.episode, streams))
                }
            }
        }
    }
    fun playback(stream: Stream): Playback? = _state.value.let { s -> s.selected?.let { Playback(it.title, stream, s.season, s.episode, s.streams) } }
    fun playSource(stream: Stream, callback: (Playback) -> Unit) {
        val selected = playback(stream) ?: return
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            update { it.copy(resolving = true) }
            attempt {
                update { it.copy(resolving = false) }
                callback(selected)
            }
        }
    }
    fun saveSettings(tmdb: String, provider: String, debrid: String) {
        try {
            debridJob?.cancel()
            val normalizedProvider = if (provider.isNotBlank()) normalizeProviderEndpoint(provider) else ""
            store.tmdbToken = tmdb; store.provider = normalizedProvider; store.debridToken = debrid
            update { it.copy(account = null, downloads = emptyList(), torrents = emptyList(), torrentFiles = emptyList(), notice = "Settings saved.") }
            reload(); loadHomeRows()
        } catch (e: Exception) { update { it.copy(error = e.message) } }
    }
    fun connectDebrid(token: String) {
        debridJob?.cancel()
        debridJob = viewModelScope.launch {
            update { it.copy(debridLoading = true, error = null) }
            attempt {
                val account = RealDebrid(token.trim()).account()
                store.debridToken = token
                update { it.copy(account = account, debridLoading = false, notice = "Connected to Real-Debrid as ${account.username}.") }
            }
        }
    }
    fun loadDebrid(more: Boolean = false) {
        debridJob?.cancel()
        val nextPage = if (more) _state.value.debridPage + 1 else 1
        debridJob = viewModelScope.launch {
            update { it.copy(debridLoading = true, error = null, torrentFiles = emptyList()) }
            attempt {
                val api = RealDebrid(store.debridToken)
                val downloads = api.downloads(nextPage)
                val torrents = api.torrents(nextPage)
                update { it.copy(downloads = if (more) it.downloads + downloads else downloads, torrents = if (more) it.torrents + torrents else torrents,
                    debridLoading = false, debridPage = nextPage, debridMore = downloads.size == 50 || torrents.size == 50) }
            }
        }
    }
    fun openTorrent(id: String) {
        debridJob?.cancel()
        debridJob = viewModelScope.launch { update { it.copy(debridLoading = true) }; attempt {
            val files = RealDebrid(store.debridToken).torrentFiles(id)
            update { it.copy(torrentFiles = files, debridLoading = false) }
        } }
    }
    fun closeTorrent() = update { it.copy(torrentFiles = emptyList()) }
    fun openLink(url: String, name: String, debrid: Boolean, callback: (Playback) -> Unit) {
        sourceJob?.cancel()
        sourceJob = viewModelScope.launch {
            update { it.copy(resolving = true, error = null) }
            attempt {
                val safe = requireHttps(url)
                val stream = if (debrid) RealDebrid(store.debridToken).unrestrict(safe) else Stream(safe, "Direct link")
                val item = Title(safe.hashCode(), "link", name.ifBlank { "My stream" })
                update { it.copy(resolving = false) }
                callback(Playback(item, stream))
            }
        }
    }
}

data class UiState(
    val titles: List<Title> = emptyList(), val loading: Boolean = false, val canLoadMore: Boolean = false,
    val error: String? = null, val notice: String? = null,
    val selected: Details? = null, val season: Int? = null, val episode: Int? = null, val episodes: List<Episode> = emptyList(),
    val streams: List<Stream> = emptyList(), val resolving: Boolean = false,
    val watchlist: List<Title> = emptyList(), val history: List<History> = emptyList(), val watchedEpisodes: Set<String> = emptySet(),
    val categoryRows: Map<String, List<Title>> = emptyMap(), val categoriesLoading: Boolean = false, val categoryError: String? = null,
    val homeRows: Map<String, List<Title>> = emptyMap(), val homeLoading: Boolean = false,
    val account: DebridAccount? = null, val debridLoading: Boolean = false,
    val downloads: List<DebridFile> = emptyList(), val torrents: List<DebridTorrent> = emptyList(),
    val torrentFiles: List<DebridTorrentFile> = emptyList(), val debridPage: Int = 1, val debridMore: Boolean = false
)

val MOVIE_CATEGORIES = listOf("Popular Movies" to null, "Action" to "Action", "Comedy" to "Comedy",
    "Drama" to "Drama", "Horror" to "Horror", "Sci-Fi" to "Sci-Fi", "Animation" to "Animation")
val TV_CATEGORIES = listOf("Popular TV Shows" to null, "Action" to "Action", "Comedy" to "Comedy",
    "Drama" to "Drama", "Crime" to "Crime", "Sci-Fi" to "Sci-Fi", "Animation" to "Animation", "Documentary" to "Documentary")
val HOME_SHELVES = listOf(
    Triple("Trending series", "tv", null), Triple("Action Movies", "movie", "Action"),
    Triple("Comedy TV Shows", "tv", "Comedy"), Triple("Drama Movies", "movie", "Drama"),
    Triple("Sci-Fi TV Shows", "tv", "Sci-Fi"), Triple("Horror Movies", "movie", "Horror"),
    Triple("Crime series", "tv", "Crime"), Triple("Animated movies", "movie", "Animation"))

