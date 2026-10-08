package com.bencsik.brio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bencsik.brio.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrioViewModel(application: Application) : AndroidViewModel(application) {
    val store = BrioStore(application)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var catalogJob: Job? = null
    private var detailJob: Job? = null
    private var episodeJob: Job? = null
    private var sourceJob: Job? = null
    private var debridJob: Job? = null
    private var page = 1
    private var lastQuery = ""
    private var lastKind = "movie"
    init { reload() }
    private fun update(block: (UiState) -> UiState) { _state.value = block(_state.value) }
    private suspend fun attempt(block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { update { it.copy(error = e.message ?: "Connection failed. Please try again.", loading = false, resolving = false, debridLoading = false) } }
    }
    fun dismissError() = update { it.copy(error = null) }
    fun reload() { refreshLibrary(); browse(lastKind, lastQuery) }
    fun refreshLibrary() = update { it.copy(watchlist = store.watchlist(), history = store.history()) }
    fun browse(kind: String = "movie", query: String = "", more: Boolean = false) {
        catalogJob?.cancel()
        lastKind = kind; lastQuery = query
        if (!more) page = 1 else page++
        catalogJob = viewModelScope.launch {
            var succeeded = false
            update { it.copy(loading = true, error = null, titles = if (more) it.titles else emptyList()) }
            attempt {
                val titles = BrioCatalog(store.tmdbToken).let { if (query.isBlank()) it.catalog(kind, page) else it.search(query, page) }
                update { it.copy(titles = if (more) (it.titles + titles).distinctBy { t -> t.key } else titles, loading = false, canLoadMore = titles.size >= 20 && (store.tmdbToken.isNotBlank() || query.isBlank())) }
                succeeded = true
            }
            if (!succeeded && more) page--
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
            reload()
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
    val watchlist: List<Title> = emptyList(), val history: List<History> = emptyList(),
    val account: DebridAccount? = null, val debridLoading: Boolean = false,
    val downloads: List<DebridFile> = emptyList(), val torrents: List<DebridTorrent> = emptyList(),
    val torrentFiles: List<DebridTorrentFile> = emptyList(), val debridPage: Int = 1, val debridMore: Boolean = false
)
