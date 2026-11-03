package com.bencsik.brio

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bencsik.brio.core.*
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val Lime = Color(0xFFB8F36A)
private val Night = Color(0xFF080D15)
private val Slate = Color(0xFF1B222D)
private val Muted = Color(0xFF9DA7B5)
private val Gold = Color(0xFFFFC44D)
private val Tabs = listOf("Home", "Movies", "TV Shows", "My List", "Settings")


class MainActivity : ComponentActivity() {
    private val vm: BrioViewModel by viewModels()
    private var sharedLink by mutableStateOf("")
    private var pendingUpdate: File? = null
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) pendingUpdate?.let(::launchInstaller)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            play(Playback(Title(it.toString().hashCode(), "local", "Local video"), Stream(it.toString(), "Device")))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readSharedLink(intent)
        val television = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Lime, onPrimary = Night, background = Night, surface = Slate, onSurface = Color.White, secondary = Lime)) {
                TvScrollFocus(television) {
                    BrioApp(vm, television, sharedLink, { sharedLink = "" }, ::play,
                        { openFile.launch(arrayOf("video/*", "audio/*")) }, ::installUpdate)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readSharedLink(intent) }
    private fun readSharedLink(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }
    override fun onResume() { super.onResume(); vm.refreshLibrary() }
    private fun play(playback: Playback) {
        if (playback.stream.url.isBlank()) { vm.resumeCloud(playback, ::play); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("playback", Gson().toJson(playback)))
    }
    private fun installUpdate(apk: File) {
        pendingUpdate = apk
        if (!packageManager.canRequestPackageInstalls()) {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")))
        } else launchInstaller(apk)
    }
    private fun launchInstaller(apk: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
        startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        pendingUpdate = null
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvScrollFocus(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val focusPosition = remember {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (size >= containerSize) return 0f
                val target = (containerSize * 0.18f).coerceAtMost(containerSize - size)
                return offset - target
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides focusPosition, content = content)
}

@Composable
private fun BrioApp(vm: BrioViewModel, tv: Boolean, sharedLink: String, clearShared: () -> Unit, play: (Playback) -> Unit,
    local: () -> Unit, installUpdate: (File) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var kind by rememberSaveable { mutableStateOf("movie") }
    var categoryLabel by rememberSaveable { mutableStateOf("") }
    var categoryGenre by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryParent by rememberSaveable { mutableStateOf("Home") }
    var query by rememberSaveable { mutableStateOf("") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.error) { state.error?.let { snackbar.showSnackbar(it); vm.dismissError() } }
    LaunchedEffect(state.notice) { state.notice?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(sharedLink) { if (sharedLink.isNotBlank()) showLink = true }
    fun navigate(next: String) {
        vm.closeDetails(); tab = next
        when (next) {
            "Home" -> { kind = "movie"; vm.browse(kind); vm.loadHomeRows() }
            "Movies" -> { kind = "movie"; vm.loadCategories(kind) }
            "TV Shows" -> { kind = "tv"; vm.loadCategories(kind) }
            "Search" -> vm.browse(kind, query)
            "Debrid" -> vm.loadDebrid()
        }
    }
    BackHandler(state.selected != null || tab != "Home") {
        if (state.selected != null) vm.closeDetails()
        else if (tab == "Category") navigate(categoryParent)
        else navigate("Home")
    }
    Scaffold(containerColor = Night, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(Color(0xFF15191F)), contentAlignment = Alignment.Center) {
                Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().height(if (tv) 64.dp else 70.dp)
                    .padding(horizontal = if (tv) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tabs.forEachIndexed { index, label ->
                        val selected = tab == label
                        TextButton(onClick = { navigate(label) }, modifier = Modifier.weight(1f).fillMaxHeight()
                            .padding(vertical = 5.dp, horizontal = 2.dp).focusRing(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.textButtonColors(containerColor = if (selected) Slate else Color.Transparent,
                                contentColor = if (selected) Color.White else Muted),
                            contentPadding = PaddingValues(0.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(listOf("⌂", "▶", "▣", "♡", "⚙")[index],
                                    style = MaterialTheme.typography.titleMedium, color = if (selected) Lime else Muted)
                                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth > 600.dp
            val compact = tv || maxHeight < 580.dp
            Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(if (compact) 56.dp else 64.dp)
                        .background(Night).padding(horizontal = if (wide) 24.dp else 12.dp)) {
                        Box(Modifier.align(Alignment.CenterStart)) {
                            TextButton(onClick = { menu = true }, modifier = Modifier.focusRing()
                                .semantics { contentDescription = "More options" }) {
                                Text(if (wide) "☰  More" else "☰", color = Color.White)
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Continue watching") }, onClick = { menu = false; navigate("Continue") }, modifier = Modifier.focusRing())
                                DropdownMenuItem(text = { Text("Real-Debrid") }, onClick = { menu = false; navigate("Debrid") }, modifier = Modifier.focusRing())
                                DropdownMenuItem(text = { Text("Open link") }, onClick = { menu = false; showLink = true }, modifier = Modifier.focusRing())
                                if (!tv) DropdownMenuItem(text = { Text("Local file") }, onClick = { menu = false; local() }, modifier = Modifier.focusRing())
                            }
                        }
                        Box(Modifier.align(Alignment.Center)) { Brand() }
                        TextButton(onClick = { navigate("Search") }, modifier = Modifier.align(Alignment.CenterEnd).focusRing()
                            .semantics { contentDescription = "Search movies and shows" }) {
                            Text(if (wide) "⌕  Search" else "⌕", color = Color.White)
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.selected != null -> Box(Modifier.padding(horizontal = 16.dp)) { DetailsScreen(state, vm, wide, compact, play) { showLink = true } }
                            tab == "Home" -> HomeScreen(state, wide, compact, vm.store.showContinueOnHome, vm::select, { vm.watch(it, play) }, play) { label, shelfKind, genre ->
                                kind = shelfKind; categoryLabel = label; categoryGenre = genre; categoryParent = "Home"
                                tab = "Category"; vm.browse(kind, genre = genre)
                            }
                            tab == "Movies" || tab == "TV Shows" -> CategoryScreen(state, kind, vm::select) { label, genre ->
                                categoryLabel = label; categoryGenre = genre; categoryParent = tab
                                tab = "Category"; vm.browse(kind, genre = genre)
                            }
                            tab == "Settings" -> Box(Modifier.padding(horizontal = 16.dp)) { SettingsScreen(vm, state, installUpdate) }
                            tab == "Debrid" -> Box(Modifier.padding(horizontal = 16.dp)) { DebridScreen(vm, state, play) }
                            tab == "Continue" -> Box(Modifier.padding(horizontal = 16.dp)) { HistoryScreen(state, play, { vm.store.clearHistory(); vm.refreshLibrary() }) }
                            tab == "My List" -> CatalogGrid(state.watchlist, wide, false, false, "Your list is empty", "Save a movie or show to see it here.", vm::select, {})
                            else -> Column(Modifier.padding(horizontal = 16.dp)) {
                                if (tab == "Search") {
                                    OutlinedTextField(query, { query = it }, label = { Text("Search movies and shows") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { vm.browse(kind, query) }),
                                        trailingIcon = { TextButton(onClick = { vm.browse(kind, query) }, modifier = Modifier.focusRing()) { Text("Go") } })
                                } else Text(if (tab == "Category") categoryLabel else tab, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                                CatalogGrid(state.titles, wide, state.loading, state.canLoadMore, "Nothing found",
                                    "Try another search or check Settings.", vm::select, { vm.browse(kind, if (tab == "Search") query else "", more = true,
                                        genre = if (tab == "Category") categoryGenre else null) })
                            }
                        }
                    }
            }
        }
    }
    if (showLink) LinkDialog(sharedLink, vm, state.resolving, { showLink = false; clearShared() }, play)
}

@Composable private fun HomeScreen(state: UiState, wide: Boolean, compact: Boolean, showContinue: Boolean, info: (Title) -> Unit,
    watch: (Title) -> Unit, resume: (Playback) -> Unit, all: (String, String, String?) -> Unit) {
    if ((state.loading || state.homeLoading) && state.titles.isEmpty() && state.homeRows.isEmpty()) { Busy(); return }
    if (state.titles.isEmpty() && state.homeRows.values.all { it.isEmpty() }) {
        EmptyState("Your next watch", "The public catalog could not load. Retry or check your connection."); return
    }
    var featuredIndex by rememberSaveable { mutableIntStateOf(0) }
    val featured = state.titles.take(6)
    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(30.dp)) {
        if (featured.isNotEmpty()) item {
            val title = featured[featuredIndex.coerceAtMost(featured.lastIndex)]
            Box(Modifier.fillMaxWidth().padding(horizontal = if (wide) 16.dp else 0.dp)
                .height(if (compact) 260.dp else if (wide) 410.dp else 380.dp)
                .clip(RoundedCornerShape(if (wide) 20.dp else 0.dp)).background(Slate)) {
                (title.backdrop ?: title.poster)?.let { AsyncImage(it, title.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Night.copy(alpha = 0.92f), Night.copy(alpha = 0.47f), Color.Transparent))))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Night.copy(alpha = 0.05f), Night.copy(alpha = 0.86f)))))
                Column(Modifier.align(Alignment.BottomStart).widthIn(max = 590.dp)
                    .padding(if (compact) 16.dp else if (wide) 32.dp else 22.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 13.dp)) {
                    Text("FEATURED  •  ${if (title.kind == "tv") "SERIES" else "MOVIE"}", color = Lime,
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(title.name, style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(if (title.rating > 0) "★ %.1f".format(title.rating) else "", title.year,
                        if (title.kind == "tv") "TV series" else "Movie").filter { it.isNotBlank() }.joinToString("   ·   "), color = Gold)
                    if (!compact && title.overview.isNotBlank()) Text(title.overview, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = Color(0xFFE0E2E5), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        featured.forEachIndexed { index, _ ->
                            Box(Modifier.size(40.dp).focusRing()
                                .semantics { contentDescription = "Featured title ${index + 1} of ${featured.size}" }
                                .clickable { featuredIndex = index }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(width = if (index == featuredIndex) 22.dp else 7.dp, height = 7.dp)
                                    .clip(RoundedCornerShape(8.dp)).background(if (index == featuredIndex) Color.White else Muted))
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { watch(title) }, enabled = !state.resolving, modifier = Modifier.focusRing(),
                            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text(if (state.resolving) "Loading…" else "▶ Play", fontWeight = FontWeight.Bold) }
                        Button(onClick = { info(title) }, modifier = Modifier.focusRing(), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate, contentColor = Color.White)) { Text("ⓘ Info") }
                    }
                }
            }
        }
        if (showContinue && state.history.any { it.positionMs > 0 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95) }) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Continue watching", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.history.filter { it.positionMs > 0 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95) }, key = { it.playback.key }) { history ->
                            Column(Modifier.width(if (wide) 170.dp else 132.dp)) {
                                Poster(history.playback.title) { resume(history.playback) }
                                if (history.durationMs > 0) LinearProgressIndicator(progress = { (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if (state.titles.isNotEmpty()) item { PosterRow("Top 10 movies today", state.titles.take(10), info,
            ranked = true, all = { all("Popular Movies", "movie", null) }) }
        HOME_SHELVES.forEach { (label, shelfKind, genre) ->
            val titles = state.homeRows[label].orEmpty()
            if (titles.isNotEmpty()) item(key = label) { PosterRow(label, titles, info) { all(label, shelfKind, genre) } }
        }
        if (state.homeLoading && state.homeRows.isEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lime) }
        }
        if (state.watchlist.isNotEmpty()) item { PosterRow("My List", state.watchlist, info) }
    }
}
@Composable private fun CategoryScreen(state: UiState, kind: String, info: (Title) -> Unit, all: (String, String?) -> Unit) {
    val sections = if (kind == "tv") TV_CATEGORIES else MOVIE_CATEGORIES
    if (state.categoriesLoading && state.categoryRows.isEmpty()) { Busy(); return }
    if (state.categoryRows.values.all { it.isEmpty() }) {
        EmptyState("Categories unavailable", state.categoryError ?: "Check your connection and try this section again.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("BROWSE", color = Lime, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text(if (kind == "tv") "TV Shows" else "Movies", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                Text("Explore popular picks by genre.", color = Muted)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections, key = { it.first }) { (label, genre) ->
                    OutlinedButton(onClick = { all(label, genre) }, modifier = Modifier.focusRing(),
                        border = BorderStroke(1.dp, Color(0xFF3A4350)), shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 5.dp)) { Text(if (genre == null) "Most popular" else genre) }
                }
            }
        }
        sections.forEach { (label, genre) ->
            val titles = state.categoryRows[label].orEmpty()
            if (titles.isNotEmpty()) item(key = label) {
                PosterRow(label, titles, info) { all(label, genre) }
            }
        }
    }
}

@Composable private fun PosterRow(label: String, titles: List<Title>, click: (Title) -> Unit,
    ranked: Boolean = false, all: (() -> Unit)? = null) {
    BoxWithConstraints {
    val cardWidth = when {
        maxWidth >= 900.dp -> 178.dp
        maxWidth >= 600.dp -> 158.dp
        else -> 132.dp
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(3.dp).height(24.dp).background(Lime))
                Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (all != null) OutlinedButton(onClick = all, modifier = Modifier.focusRing(),
                border = BorderStroke(1.dp, Color(0xFF3A4350)), shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("All  ›") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.key }) { item ->
                Box(Modifier.width(cardWidth)) { Poster(item, rank = if (ranked) titles.indexOf(item) + 1 else null) { click(item) } }
            }
        }
    }
    }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Lime), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(width = 13.dp, height = 15.dp).offset(x = 1.dp)) {
                val shape = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(shape, Night)
            }
        }
        Text("brio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White)
    }
}
@Composable private fun NavButton(label: String, selected: Boolean, click: () -> Unit) {
    if (selected) Button(onClick = click, modifier = Modifier.focusRing(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(label) }
    else TextButton(onClick = click, modifier = Modifier.focusRing(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(label, color = Muted) }
}
@Composable private fun Hero(wide: Boolean, demo: Boolean, click: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(if (wide) 190.dp else 160.dp).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF27394A), Color(0xFF172427), Color(0xFF18202C))))) {
        Column(Modifier.padding(24.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(if (demo) "WELCOME TO BRIO" else "MAKE TONIGHT A MOVIE NIGHT", color = Lime, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("Less waiting.\nMore watching.", style = if (wide) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (demo) "Start with an open movie below." else "Movies, shows, and your own streams.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = click) { Text(if (demo) "Set up catalog →" else "Open a stream →") }
            }
        }
    }
}
@Composable private fun CatalogGrid(titles: List<Title>, wide: Boolean, loading: Boolean, more: Boolean, emptyTitle: String, emptyBody: String, select: (Title) -> Unit, loadMore: () -> Unit) {
    if (loading && titles.isEmpty()) { Busy(); return }
    if (titles.isEmpty()) { EmptyState(emptyTitle, emptyBody); return }
    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(if (wide) 142.dp else 118.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(8.dp), modifier = Modifier.weight(1f)) {
            items(titles, key = { it.key }) { item -> Poster(item) { select(item) } }
        }
        if (more) TextButton(onClick = loadMore, enabled = !loading, modifier = Modifier.align(Alignment.CenterHorizontally).focusRing()) { Text(if (loading) "Loading…" else "Load more") }
    }
}
@Composable private fun Poster(item: Title, rank: Int? = null, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.045f else 1f, label = "posterFocus")
    Column(Modifier.scale(scale).clip(RoundedCornerShape(12.dp)).border(if (focused) 3.dp else 0.dp, if (focused) Lime else Color.Transparent, RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, onClick = click)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFF34475B), Slate, Color(0xFF25342C))))) {
            if (item.poster != null) AsyncImage(item.poster, item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                Text("▶", color = Lime, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(16.dp))
                Text(item.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text(if (item.kind == "tv") "TV SERIES" else if (item.demoUrl != null) "BLENDER OPEN MOVIE" else "MOVIE", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
            if (rank != null) Text("%02d".format(rank), color = Color.White, fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp))
            if (item.rating > 0 || item.year.isNotBlank()) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Night.copy(alpha = 0.82f)).padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (item.rating > 0) "★ %.1f".format(item.rating) else "", color = Color.White,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Text(item.year, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 2,
            minLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
    }
}
@Composable private fun DetailsScreen(state: UiState, vm: BrioViewModel, wide: Boolean, compact: Boolean, play: (Playback) -> Unit, link: () -> Unit) {
    val details = state.selected ?: return
    val item = details.title
    var confirmRestart by remember(item.key) { mutableStateOf(false) }
    if (confirmRestart) AlertDialog(
        onDismissRequest = { confirmRestart = false },
        title = { Text("Restart ${item.name}?") },
        text = { Text("Clear this show’s saved progress and play Season 1, Episode 1. Your watchlist is kept.") },
        confirmButton = { TextButton(onClick = { confirmRestart = false; vm.restartSeries(play) }, modifier = Modifier.focusRing()) { Text("Restart series") } },
        dismissButton = { TextButton(onClick = { confirmRestart = false }, modifier = Modifier.focusRing()) { Text("Cancel") } }
    )
    LazyColumn(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            TextButton(onClick = vm::closeDetails, modifier = Modifier.focusRing()) { Text("← Back to browsing") }
            Box(Modifier.fillMaxWidth().height(if (compact) 200.dp else if (wide) 300.dp else 260.dp)
                .clip(RoundedCornerShape(18.dp)).background(Slate)) {
                item.backdrop?.let { AsyncImage(it, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Night))))
                Column(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                    Text(item.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(listOf(item.year, if (item.kind == "tv") "TV series" else "Movie", if (item.rating > 0) "★ %.1f".format(item.rating) else "").filter { it.isNotBlank() }.joinToString(" · "), color = Muted)
                }
            }
        }
        item { Text(item.overview.ifBlank { "No description available." }, color = Muted, style = MaterialTheme.typography.bodyLarge) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { vm.findSources(play) },
                    enabled = !state.resolving && (item.kind != "tv" || state.episode != null),
                    modifier = Modifier.weight(1f).focusRing(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                    Text(if (state.resolving) "Loading…" else "▶ Play")
                }
                OutlinedButton(onClick = { vm.toggle(item) }, modifier = Modifier.weight(1f).focusRing()) {
                    Text(if (state.watchlist.any { it.key == item.key }) "✓ Saved" else "+ My List", maxLines = 1)
                }
            }
            if (state.streams.isNotEmpty()) TextButton(onClick = { vm.findSources() },
                modifier = Modifier.focusRing()) { Text("Choose source · ${state.streams.size}") }
            else if (item.kind == "tv" && state.episode == null) Text("Choose an episode to play.", color = Muted)
        }
        if (item.kind == "tv") {
            item { OutlinedButton(onClick = { confirmRestart = true }, enabled = !state.resolving, modifier = Modifier.focusRing()) { Text("↺ Restart series") } }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(details.seasons) { season -> NavButton(season.name, state.season == season.number) { vm.chooseSeason(season.number) } } } }
            items(state.episodes) { episode ->
                OutlinedButton(onClick = { vm.chooseEpisode(episode.number) }, modifier = Modifier.fillMaxWidth().focusRing(), border = BorderStroke(1.dp, if (state.episode == episode.number) Lime else Slate)) {
                    val watched = "${item.key}:${state.season ?: 0}:${episode.number}" in state.watchedEpisodes
                    Text("${if (watched) "✓ " else ""}${episode.number}. ${episode.name}", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        items(state.streams) { stream ->
            Button(onClick = { vm.playSource(stream, play) }, enabled = !state.resolving, modifier = Modifier.fillMaxWidth().focusRing(), colors = ButtonDefaults.buttonColors(containerColor = Slate, contentColor = Color.White)) {
                Text("▶  ${stream.name}   ·   ${stream.quality}", modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
        }
        item { TextButton(onClick = link, modifier = Modifier.focusRing()) { Text("Have a link? Open it directly →") } }
    }
}
@Composable private fun HistoryScreen(state: UiState, play: (Playback) -> Unit, clear: () -> Unit) {
    val inProgress = state.history.filter { it.positionMs > 0 && (it.durationMs <= 0 || it.positionMs < it.durationMs * 0.95) }
    if (inProgress.isEmpty()) { EmptyState("Nothing to continue yet", "Start watching a video, then return here to resume it."); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = clear, modifier = Modifier.focusRing()) { Text("Clear watch history") } }
        items(inProgress, key = { it.playback.key }) { history ->
            Card(onClick = { play(history.playback) }, modifier = Modifier.fillMaxWidth().focusRing(), colors = CardDefaults.cardColors(containerColor = Slate)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("▶  ${history.playback.label}", fontWeight = FontWeight.Bold)
                    Text("${history.positionMs / 60000} min watched", color = Muted)
                    if (history.durationMs > 0) LinearProgressIndicator(progress = { (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
@Composable private fun SettingsScreen(vm: BrioViewModel, state: UiState, installUpdate: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pairing by vm.pairing.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val updater = remember { UpdateClient(context.applicationContext) }
    var tokenInput by remember { mutableStateOf("") }
    var savedUpdateToken by remember { mutableStateOf(updater.hasToken()) }
    var release by remember { mutableStateOf<UpdateClient.Release?>(null) }
    var updateStatus by remember { mutableStateOf("") }
    var updateBusy by remember { mutableStateOf(false) }
    var downloadPercent by remember { mutableIntStateOf(0) }
    var codeInput by rememberSaveable { mutableStateOf("") }
    var addressInput by rememberSaveable { mutableStateOf("") }
    var tmdb by remember { mutableStateOf(vm.store.tmdbToken) }
    var provider by remember { mutableStateOf(vm.store.provider) }
    var newSource by rememberSaveable { mutableStateOf("") }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var debrid by remember { mutableStateOf(vm.store.debridToken) }
    var section by rememberSaveable { mutableStateOf("Experience") }
    var showContinue by remember { mutableStateOf(vm.store.showContinueOnHome) }
    var autoplay by remember { mutableStateOf(vm.store.autoplayNext) }
    var autoIntro by remember { mutableStateOf(vm.store.autoSkipIntro) }
    var confirmClear by remember { mutableStateOf(false) }
    val sections = listOf("Experience", "Playback", "Devices", "Sources", "Your data", "About")
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear viewing history?") },
        text = { Text("This removes saved progress and Continue Watching entries from your library.") },
        confirmButton = { TextButton(onClick = { vm.store.clearHistory(); vm.refreshLibrary(); confirmClear = false }, modifier = Modifier.focusRing()) { Text("Clear history") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }, modifier = Modifier.focusRing()) { Text("Cancel") } })
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val sideRail = maxWidth >= 720.dp
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
    if (sideRail) Column(Modifier.width(160.dp).padding(top = 80.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sections.forEach { choice ->
            TextButton(onClick = { section = choice }, modifier = Modifier.fillMaxWidth().focusRing()
                .background(if (section == choice) Slate else Color.Transparent, RoundedCornerShape(10.dp)),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                Text(choice, modifier = Modifier.fillMaxWidth(), color = if (section == choice) Color.White else Muted)
            }
        }
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black,
                modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))
            HorizontalDivider(color = Color(0xFF303947))
        }
        if (!sideRail) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(sections) { choice ->
                    val selected = section == choice
                    Button(onClick = { section = choice }, modifier = Modifier.focusRing(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = if (selected) Slate else Color.Transparent,
                            contentColor = if (selected) Color.White else Muted),
                        border = BorderStroke(1.dp, if (selected) Lime else Color(0xFF303947))) { Text(choice) }
                }
            }
        }
        if (section == "Experience") {
            item { SettingsPanel("Home", "Choose what appears on your home screen.") {
                SettingsSwitch("Continue Watching on Home", "Show your in-progress movies and episodes.", showContinue) {
                    showContinue = it; vm.store.showContinueOnHome = it
                }
            } }
            item { SettingsPanel("Display", "A calm, cinema-first layout for phone, tablet, and TV.") {
                Text("Posters show ratings, while your remote focus stays visible on every control.", color = Muted)
            } }
        }
        if (section == "Playback") {
            item { SettingsPanel("Playback", "These choices are stored on this device.") {
                SettingsSwitch("Autoplay next episode", "Start the following episode when this one ends.", autoplay) {
                    autoplay = it; vm.store.autoplayNext = it
                }
                HorizontalDivider(color = Color(0xFF303947))
                SettingsSwitch("Auto-skip intros", "Skip automatically when a source supplies intro timing.", autoIntro) {
                    autoIntro = it; vm.store.autoSkipIntro = it
                }
                Text("Skip credits, next episode, and server selection remain available in the player.", color = Muted)
            } }
        }
        if (section == "Devices") item {
            SettingsPanel("Sync devices", "Transfer your watchlist and viewing progress between Brio devices on the same Wi-Fi.") {
                Text("On the device with your library, show a code. On the other device, enter that code below. Keep both apps open until sync finishes.", color = Muted)
                if (pairing.code == null) {
                    Button(onClick = { vm.pairing.startOffer() }, modifier = Modifier.focusRing()) { Text("Show pairing code") }
                } else {
                    Text(pairing.code.orEmpty(), style = MaterialTheme.typography.headlineLarge, color = Lime, fontWeight = FontWeight.Black)
                    pairing.address?.let { Text("If the code alone can't find this device, enter $it on the other device too.", color = Muted) }
                    TextButton(onClick = { vm.pairing.stopOffer() }, modifier = Modifier.focusRing()) { Text("Cancel code") }
                }
                HorizontalDivider(color = Slate)
                OutlinedTextField(codeInput, { codeInput = it.filter(Char::isDigit).take(6) },
                    label = { Text("Code from your other device") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRing(),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        imeAction = ImeAction.Done))
                OutlinedTextField(addressInput, { addressInput = it },
                    label = { Text("IP:port (only if discovery fails)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRing())
                Button(onClick = { scope.launch { vm.pairing.connect(codeInput, addressInput) } },
                    enabled = !pairing.busy && codeInput.length == 6, modifier = Modifier.focusRing()) {
                    Text(if (pairing.busy) "Connecting…" else "Sync with code")
                }
                Text(pairing.status, color = Muted)
                Text("This is a manual transfer, not an ongoing sign-in. Repeat when you want to sync again. Provider keys and video links stay on each device.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (section == "Sources") {
        item { SectionTitle("Catalog", "A public movie and TV catalog is built in. TMDB and Real-Debrid are optional.") }
        item { OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB read access token (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().focusRing(), singleLine = true) }
        item {
            Text("Leave this blank to use the public catalog. A TMDB token lets you use TMDB instead.", color = Muted)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))) }, modifier = Modifier.focusRing()) { Text("Get a TMDB token →") }
        }
        item { HorizontalDivider(color = Slate) }
        item { SectionTitle("Real-Debrid", "Optional. Play supported links and files in your account.") }
        item { OutlinedTextField(debrid, { debrid = it }, label = { Text("Real-Debrid API token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().focusRing(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connectDebrid(debrid) }, enabled = debrid.isNotBlank() && !state.debridLoading, modifier = Modifier.focusRing()) { Text(if (state.debridLoading) "Connecting…" else "Verify account") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://real-debrid.com/apitoken"))) }, modifier = Modifier.focusRing()) { Text("Get token →") }
            }
            state.account?.let { Text("Connected: ${it.username} · ${it.type}\nExpires: ${it.expiration.take(10)}", color = Lime, modifier = Modifier.padding(top = 12.dp)) }
            TextButton(onClick = { debrid = ""; vm.store.debridToken = ""; vm.saveSettings(tmdb, provider, "") }, modifier = Modifier.focusRing()) { Text("Disconnect Real-Debrid") }
        }
        item { HorizontalDivider(color = Slate) }
        item { SectionTitle("Playback sources", "Brio checks its built-in source and the sources you add, then shows available videos together.") }
        item { SettingsPanel("Built-in source", "Vixsrc · always checked during playback") {
            Text("Availability varies by title and network. Other sources can still appear when this one fails.", color = Muted)
        } }
        item { SettingsPanel("Your sources", "Add up to six HTTPS add-on manifests or Brio JSON endpoints.") {
            val entries = provider.lines().map(String::trim).filter(String::isNotBlank)
            if (entries.isEmpty()) Text("No extra sources added yet.", color = Muted)
            entries.forEachIndexed { index, endpoint ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(endpoint, modifier = Modifier.weight(1f), maxLines = 2,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { provider = entries.filterIndexed { i, _ -> i != index }.joinToString("\n") },
                        modifier = Modifier.focusRing()) { Text("Remove") }
                }
            }
            OutlinedTextField(newSource, { newSource = it; sourceError = null },
                label = { Text("Add source URL") }, placeholder = { Text("https://example.org/manifest.json") },
                modifier = Modifier.fillMaxWidth().focusRing(), singleLine = true)
            sourceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = {
                try {
                    val added = normalizeProviderEndpoint(newSource)
                    provider = normalizeProviderEndpoints((entries + added).joinToString("\n")).joinToString("\n")
                    newSource = ""; sourceError = null
                } catch (e: Exception) { sourceError = e.message ?: "Enter a valid HTTPS source URL." }
            }, enabled = newSource.isNotBlank(), modifier = Modifier.focusRing()) { Text("Add source") }
            Text("Add-ons must return direct HTTPS video URLs. Sources and any keys in their URLs are saved on this device.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        } }
        item { Button(onClick = { vm.saveSettings(tmdb, provider, debrid) }, modifier = Modifier.fillMaxWidth().focusRing()) { Text("Save settings") } }
        item {
            HorizontalDivider(color = Slate)
            Spacer(Modifier.height(16.dp))
        }
        }
        if (section == "Your data") item { SettingsPanel("Viewing activity", "Manage progress saved in Brio.") {
            Text("${state.history.size} saved plays · ${state.watchlist.size} titles in My List", color = Muted)
            OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.focusRing()) { Text("Clear viewing history") }
            Text("Use Devices to sync your library with another device on your Wi-Fi.", color = Muted,
                style = MaterialTheme.typography.bodySmall)
        } }
        if (section == "About") item {
            Text("Brio 0.5.5 · Made for your next great watch", color = Muted)
            SettingsPanel("App updates", "Check the private Brio GitHub release and install a newer APK directly on this device.") {
                if (!savedUpdateToken) {
                    Text("One-time setup: create a fine-grained GitHub token restricted to the Brio repository with Contents: read-only. Paste it here on your device. You never need to send it to us.", color = Muted)
                    OutlinedTextField(tokenInput, { tokenInput = it }, label = { Text("GitHub read-only token") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        modifier = Modifier.fillMaxWidth().focusRing())
                    Button(onClick = {
                        runCatching { updater.saveToken(tokenInput) }.onSuccess {
                            savedUpdateToken = true; tokenInput = ""; updateStatus = "Token saved on this device."
                        }.onFailure { updateStatus = it.message ?: "Could not save token." }
                    }, enabled = tokenInput.isNotBlank(), modifier = Modifier.focusRing()) { Text("Save token") }
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/settings/personal-access-tokens/new"))) }, modifier = Modifier.focusRing()) {
                        Text("Create GitHub token →")
                    }
                } else {
                    Text("GitHub token saved on this device.", color = Muted)
                    Button(onClick = {
                        scope.launch {
                            updateBusy = true; release = null; updateStatus = "Checking GitHub…"
                            try {
                                release = withContext(Dispatchers.IO) { updater.check() }
                                updateStatus = release?.let { "Brio ${it.version} is ready to download." } ?: "You're up to date."
                            } catch (e: Exception) { updateStatus = e.message ?: "Could not check for updates." }
                            finally { updateBusy = false }
                        }
                    }, enabled = !updateBusy, modifier = Modifier.focusRing()) { Text("Check for updates") }
                    release?.let { latest ->
                        Button(onClick = {
                            scope.launch {
                                updateBusy = true; downloadPercent = 0; updateStatus = "Downloading update…"
                                try {
                                    val apk = withContext(Dispatchers.IO) {
                                        updater.download(latest) { progress -> scope.launch { downloadPercent = progress } }
                                    }
                                    updateStatus = "Follow the Android installer to finish updating."
                                    installUpdate(apk)
                                } catch (e: Exception) { updateStatus = e.message ?: "Could not download update." }
                                finally { updateBusy = false }
                            }
                        }, enabled = !updateBusy, modifier = Modifier.focusRing()) {
                            Text(if (updateBusy) "Downloading $downloadPercent%" else "Download and install ${latest.version}")
                        }
                    }
                    TextButton(onClick = { updater.clearToken(); savedUpdateToken = false; release = null; updateStatus = "Token removed." },
                        modifier = Modifier.focusRing()) { Text("Remove update token") }
                }
                if (updateStatus.isNotBlank()) Text(updateStatus, color = Muted)
                Text("Android may ask you to allow installs from Brio once. It will show the system installer for each update.",
                    color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org"))) }, modifier = Modifier.focusRing()) { Text("The Movie Database →") }
        }
    }
    }
    }
}
@Composable private fun SettingsPanel(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFF121A24))
        .border(1.dp, Color(0xFF303947), RoundedCornerShape(16.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(description, color = Muted)
        HorizontalDivider(color = Color(0xFF303947))
        content()
    }
}
@Composable private fun SettingsSwitch(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = change, modifier = Modifier.focusRing())
    }
}
@Composable private fun DebridScreen(vm: BrioViewModel, state: UiState, play: (Playback) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle("Your Real-Debrid library", "Stream files from your connected account.")
            TextButton(onClick = { vm.loadDebrid() }, enabled = !state.debridLoading, modifier = Modifier.focusRing()) { Text(if (state.debridLoading) "Loading…" else "Refresh library") }
        }
        if (vm.store.debridToken.isBlank()) item { EmptyState("Connect Real-Debrid", "Add your API token in Settings to use your account.") }
        else {
            item { Text("Downloads", style = MaterialTheme.typography.titleLarge) }
            if (state.downloads.isEmpty() && !state.debridLoading) item { Text("No downloads found.", color = Muted) }
            items(state.downloads, key = { "download:${it.id}" }) { file ->
                FileButton(file.filename, "Play through Real-Debrid", enabled = !state.resolving) { vm.openLink(file.originalLink, file.filename, true, play) }
            }
            item { Spacer(Modifier.height(10.dp)); Text("Torrents", style = MaterialTheme.typography.titleLarge) }
            if (state.torrents.isEmpty() && !state.debridLoading) item { Text("No torrents found.", color = Muted) }
            items(state.torrents, key = { "torrent:${it.id}" }) { torrent ->
                FileButton(torrent.filename, "${torrent.status.replace('_', ' ')} · ${torrent.progress}%", torrent.status == "downloaded" && !state.debridLoading) { vm.openTorrent(torrent.id) }
            }
            if (state.debridMore) item { TextButton(onClick = { vm.loadDebrid(true) }, enabled = !state.debridLoading, modifier = Modifier.focusRing()) { Text("Load more") } }
        }
    }
    if (state.torrentFiles.isNotEmpty()) AlertDialog(onDismissRequest = vm::closeTorrent,
        title = { Text("Choose a file") }, text = { LazyColumn { items(state.torrentFiles) { file ->
            TextButton(onClick = { vm.openLink(file.link, file.filename, true) { vm.closeTorrent(); play(it) } }, enabled = !state.resolving, modifier = Modifier.focusRing()) { Text(file.filename) }
        } } }, confirmButton = { TextButton(onClick = vm::closeTorrent, modifier = Modifier.focusRing()) { Text("Close") } })
}
@Composable private fun FileButton(name: String, subtitle: String, enabled: Boolean, click: () -> Unit) {
    OutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth().focusRing(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(name, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun LinkDialog(initial: String, vm: BrioViewModel, busy: Boolean, dismiss: () -> Unit, play: (Playback) -> Unit) {
    var url by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var debrid by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Open a stream") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(url, { url = it }, label = { Text("HTTPS video or host link") }, singleLine = true)
            OutlinedTextField(name, { name = it }, label = { Text("Title (optional)") }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(debrid, { debrid = it }, enabled = vm.store.debridToken.isNotBlank())
                Text("Resolve with Real-Debrid")
            }
            Text("Direct MP4, HLS, and DASH streams are supported.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = { vm.openLink(url, name, debrid) { dismiss(); play(it) } }, enabled = url.isNotBlank() && !busy, modifier = Modifier.focusRing()) { Text(if (busy) "Resolving…" else "▶ Play") } },
        dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.focusRing()) { Text("Cancel") } })
}
@Composable private fun SectionTitle(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Muted, modifier = Modifier.padding(top = 8.dp))
}
@Composable private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(body, color = Muted)
    }
}
@Composable private fun Busy() { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Lime) } }

