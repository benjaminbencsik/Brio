package com.bencsik.brio

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.bencsik.brio.core.*
import com.google.gson.Gson

private val Lime = Color(0xFFB8F36A)
private val Night = Color(0xFF0D0D0F)
private val Slate = Color(0xFF242426)
private val Muted = Color(0xFF9BA8BA)
private val Tabs = listOf("Home", "Movies", "TV Shows", "My List", "Settings")

class MainActivity : ComponentActivity() {
    private val vm: BrioViewModel by viewModels()
    private var sharedLink by mutableStateOf("")
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
                BrioApp(vm, television, sharedLink, { sharedLink = "" }, ::play, { openFile.launch(arrayOf("video/*", "audio/*")) })
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); readSharedLink(intent) }
    private fun readSharedLink(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) sharedLink = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
    }
    override fun onResume() { super.onResume(); vm.refreshLibrary() }
    private fun play(playback: Playback) {
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("playback", Gson().toJson(playback)))
    }
}

@Composable
private fun BrioApp(vm: BrioViewModel, tv: Boolean, sharedLink: String, clearShared: () -> Unit, play: (Playback) -> Unit, local: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var kind by rememberSaveable { mutableStateOf("movie") }
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
            "Home", "Movies" -> { kind = "movie"; vm.browse(kind) }
            "TV Shows" -> { kind = "tv"; vm.browse(kind) }
            "Search" -> vm.browse(kind, query)
            "Debrid" -> vm.loadDebrid()
        }
    }
    BackHandler(state.selected != null || tab != "Home") {
        if (state.selected != null) vm.closeDetails() else navigate("Home")
    }
    Scaffold(containerColor = Night, snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!tv) NavigationBar(containerColor = Color(0xFF19191B)) {
                Tabs.forEachIndexed { index, label ->
                    NavigationBarItem(selected = tab == label, onClick = { navigate(label) },
                        icon = { Text(listOf("⌂", "▶", "▣", "♡", "⚙")[index], style = MaterialTheme.typography.titleLarge) },
                        label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White,
                            indicatorColor = Color.Transparent, unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth > 800.dp || tv
            Row(Modifier.fillMaxSize()) {
                if (tv) Column(Modifier.width(160.dp).fillMaxHeight().background(Slate).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Brand()
                    Tabs.forEach { label -> NavButton(label, tab == label) { navigate(label) } }
                    NavButton("Search", tab == "Search") { navigate("Search") }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().background(Color(0xFF151518)).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Box {
                            TextButton(onClick = { menu = true }) { Text("☰", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Continue watching") }, onClick = { menu = false; navigate("Continue") })
                                DropdownMenuItem(text = { Text("Real-Debrid") }, onClick = { menu = false; navigate("Debrid") })
                                DropdownMenuItem(text = { Text("Open link") }, onClick = { menu = false; showLink = true })
                                if (!tv) DropdownMenuItem(text = { Text("Local file") }, onClick = { menu = false; local() })
                            }
                        }
                        Brand()
                        TextButton(onClick = { navigate("Search") }) { Text("Search", color = Color.White) }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            state.selected != null -> Box(Modifier.padding(horizontal = 16.dp)) { DetailsScreen(state, vm, wide, play) { showLink = true } }
                            tab == "Home" -> HomeScreen(state, wide, vm::select, { vm.watch(it, play) }, play)
                            tab == "Settings" -> Box(Modifier.padding(horizontal = 16.dp)) { SettingsScreen(vm, state) }
                            tab == "Debrid" -> Box(Modifier.padding(horizontal = 16.dp)) { DebridScreen(vm, state, play) }
                            tab == "Continue" -> Box(Modifier.padding(horizontal = 16.dp)) { HistoryScreen(state, play, { vm.store.clearHistory(); vm.refreshLibrary() }) }
                            tab == "My List" -> CatalogGrid(state.watchlist, wide, false, false, "Your list is empty", "Save a movie or show to see it here.", vm::select, {})
                            else -> Column(Modifier.padding(horizontal = 16.dp)) {
                                if (tab == "Search") {
                                    OutlinedTextField(query, { query = it }, label = { Text("Search movies and shows") }, singleLine = true,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions(onSearch = { vm.browse(kind, query) }),
                                        trailingIcon = { TextButton(onClick = { vm.browse(kind, query) }) { Text("Go") } })
                                } else Text(tab, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                                CatalogGrid(state.titles, wide, state.loading, state.canLoadMore, "Nothing found",
                                    "Try another search or check Settings.", vm::select, { vm.browse(kind, if (tab == "Search") query else "", more = true) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (showLink) LinkDialog(sharedLink, vm, state.resolving, { showLink = false; clearShared() }, play)
}

@Composable private fun HomeScreen(state: UiState, wide: Boolean, info: (Title) -> Unit, watch: (Title) -> Unit, resume: (Playback) -> Unit) {
    if (state.loading && state.titles.isEmpty()) { Busy(); return }
    if (state.titles.isEmpty()) { EmptyState("Your next watch", "The public catalog could not load. Retry or check your connection."); return }
    var featuredIndex by rememberSaveable { mutableIntStateOf(0) }
    val featured = state.titles.take(6)
    val title = featured[featuredIndex.coerceAtMost(featured.lastIndex)]
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            Box(Modifier.fillMaxWidth().height(if (wide) 480.dp else 430.dp).background(Slate)) {
                (title.backdrop ?: title.poster)?.let { AsyncImage(it, title.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA0D0D0F), Night))))
                Column(Modifier.align(Alignment.BottomStart).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(title.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text(listOf(title.year, if (title.rating > 0) "★ %.1f".format(title.rating) else "", if (title.kind == "tv") "TV Show" else "Movie").filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFFCCCCCC))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        featured.forEachIndexed { index, _ ->
                            Box(Modifier.size(width = if (index == featuredIndex) 24.dp else 8.dp, height = 8.dp)
                                .clip(RoundedCornerShape(8.dp)).background(if (index == featuredIndex) Color.White else Muted)
                                .clickable { featuredIndex = index })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { watch(title) }, enabled = !state.resolving,
                            shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text(if (state.resolving) "Loading…" else "▶ Play", fontWeight = FontWeight.Bold) }
                        Button(onClick = { info(title) }, shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate, contentColor = Color.White)) { Text("ⓘ Info") }
                    }
                }
            }
        }
        if (state.history.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Continue watching", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.history, key = { it.playback.key }) { history ->
                            Column(Modifier.width(150.dp)) {
                                Poster(history.playback.title) { resume(history.playback) }
                                if (history.durationMs > 0) LinearProgressIndicator(progress = { (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        item { PosterRow("Popular right now", state.titles, info) }
        if (state.watchlist.isNotEmpty()) item { PosterRow("My List", state.watchlist, info) }
    }
}
@Composable private fun PosterRow(label: String, titles: List<Title>, click: (Title) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(titles, key = { it.key }) { item -> Box(Modifier.width(150.dp)) { Poster(item) { click(item) } } }
        }
    }
}

@Composable private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(Lime), contentAlignment = Alignment.Center) { Text("▶", color = Night, fontWeight = FontWeight.Black) }
        Text("brio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = Color.White)
    }
}
@Composable private fun NavButton(label: String, selected: Boolean, click: () -> Unit) {
    if (selected) Button(onClick = click, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(label) }
    else TextButton(onClick = click, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(label, color = Muted) }
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
        if (more) TextButton(onClick = loadMore, enabled = !loading, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(if (loading) "Loading…" else "Load more") }
    }
}
@Composable private fun Poster(item: Title, click: () -> Unit) {
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
                Text("BLENDER OPEN MOVIE", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 6.dp, end = 6.dp))
        Text(listOf(item.year, if (item.kind == "tv") "TV series" else "Movie").filter { it.isNotBlank() }.joinToString(" · "), color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp))
    }
}
@Composable private fun DetailsScreen(state: UiState, vm: BrioViewModel, wide: Boolean, play: (Playback) -> Unit, link: () -> Unit) {
    val details = state.selected ?: return
    val item = details.title
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            TextButton(onClick = vm::closeDetails) { Text("← Back to browsing") }
            Box(Modifier.fillMaxWidth().height(if (wide) 210.dp else 180.dp).clip(RoundedCornerShape(18.dp)).background(Slate)) {
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.findSources(play) }, enabled = !state.resolving && (item.kind != "tv" || state.episode != null), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) { Text(if (state.resolving) "Loading…" else "▶ Play") }
                if (state.streams.isNotEmpty()) TextButton(onClick = { vm.findSources() }) { Text("Servers") }
                OutlinedButton(onClick = { vm.toggle(item) }) { Text(if (state.watchlist.any { it.key == item.key }) "✓ Saved" else "+ Watchlist") }
            }
        }
        if (item.kind == "tv") {
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(details.seasons) { season -> NavButton(season.name, state.season == season.number) { vm.chooseSeason(season.number) } } } }
            items(state.episodes) { episode ->
                OutlinedButton(onClick = { vm.chooseEpisode(episode.number) }, modifier = Modifier.fillMaxWidth(), border = BorderStroke(1.dp, if (state.episode == episode.number) Lime else Slate)) {
                    Text("${episode.number}. ${episode.name}", modifier = Modifier.fillMaxWidth())
                }
            }
        }
        items(state.streams) { stream ->
            Button(onClick = { vm.playSource(stream, play) }, enabled = !state.resolving, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Slate, contentColor = Color.White)) {
                Text("▶  ${stream.name}   ·   ${stream.quality}", modifier = Modifier.fillMaxWidth().padding(8.dp))
            }
        }
        item { TextButton(onClick = link) { Text("Have a link? Open it directly →") } }
    }
}
@Composable private fun HistoryScreen(state: UiState, play: (Playback) -> Unit, clear: () -> Unit) {
    if (state.history.isEmpty()) { EmptyState("Pick up where you left off", "Your recent watches will appear here."); return }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = clear) { Text("Clear watch history") } }
        items(state.history, key = { it.playback.key }) { history ->
            Card(onClick = { play(history.playback) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Slate)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("▶  ${history.playback.label}", fontWeight = FontWeight.Bold)
                    Text("${history.positionMs / 60000} min watched", color = Muted)
                    if (history.durationMs > 0) LinearProgressIndicator(progress = { (history.positionMs.toFloat() / history.durationMs).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
@Composable private fun SettingsScreen(vm: BrioViewModel, state: UiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var tmdb by remember { mutableStateOf(vm.store.tmdbToken) }
    var provider by remember { mutableStateOf(vm.store.provider) }
    var debrid by remember { mutableStateOf(vm.store.debridToken) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { SectionTitle("Make Brio yours", "A public movie and TV catalog is built in. TMDB and Real-Debrid are optional.") }
        item { OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB read access token (optional)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Text("Leave this blank to use the public catalog. A TMDB token lets you use TMDB instead.", color = Muted)
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))) }) { Text("Get a TMDB token →") }
        }
        item { HorizontalDivider(color = Slate) }
        item { SectionTitle("Real-Debrid", "Optional. Play supported links and files in your account.") }
        item { OutlinedTextField(debrid, { debrid = it }, label = { Text("Real-Debrid API token") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connectDebrid(debrid) }, enabled = debrid.isNotBlank() && !state.debridLoading) { Text(if (state.debridLoading) "Connecting…" else "Verify account") }
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://real-debrid.com/apitoken"))) }) { Text("Get token →") }
            }
            state.account?.let { Text("Connected: ${it.username} · ${it.type}\nExpires: ${it.expiration.take(10)}", color = Lime, modifier = Modifier.padding(top = 12.dp)) }
            TextButton(onClick = { debrid = ""; vm.store.debridToken = ""; vm.saveSettings(tmdb, provider, "") }) { Text("Disconnect Real-Debrid") }
        }
        item { HorizontalDivider(color = Slate) }
        item { SectionTitle("Stream provider", "Vixsrc is built in. Leave the field blank to use it, or enter another provider.") }
        item { OutlinedTextField(provider, { provider = it }, label = { Text("Add-on manifest URL or HTTPS endpoint") }, placeholder = { Text("https://your-addon.example/manifest.json") }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
        item { Text("Built-in Vixsrc resolves movie and episode links automatically. Availability depends on the provider. Custom add-ons must supply direct HTTPS video links.", color = Muted) }
        item { Button(onClick = { vm.saveSettings(tmdb, provider, debrid) }, modifier = Modifier.fillMaxWidth()) { Text("Save settings") } }
        item {
            HorizontalDivider(color = Slate)
            Spacer(Modifier.height(16.dp))
            Text("Brio 0.3.0 · Made for your next great watch", color = Muted)
            Text("This product uses the TMDB API but is not endorsed or certified by TMDB.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org"))) }) { Text("The Movie Database →") }
        }
    }
}
@Composable private fun DebridScreen(vm: BrioViewModel, state: UiState, play: (Playback) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle("Your Real-Debrid library", "Stream files from your connected account.")
            TextButton(onClick = { vm.loadDebrid() }, enabled = !state.debridLoading) { Text(if (state.debridLoading) "Loading…" else "Refresh library") }
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
            if (state.debridMore) item { TextButton(onClick = { vm.loadDebrid(true) }, enabled = !state.debridLoading) { Text("Load more") } }
        }
    }
    if (state.torrentFiles.isNotEmpty()) AlertDialog(onDismissRequest = vm::closeTorrent,
        title = { Text("Choose a file") }, text = { LazyColumn { items(state.torrentFiles) { file ->
            TextButton(onClick = { vm.openLink(file.link, file.filename, true) { vm.closeTorrent(); play(it) } }, enabled = !state.resolving) { Text(file.filename) }
        } } }, confirmButton = { TextButton(onClick = vm::closeTorrent) { Text("Close") } })
}
@Composable private fun FileButton(name: String, subtitle: String, enabled: Boolean, click: () -> Unit) {
    OutlinedButton(onClick = click, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
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
        confirmButton = { Button(onClick = { vm.openLink(url, name, debrid) { dismiss(); play(it) } }, enabled = url.isNotBlank() && !busy) { Text(if (busy) "Resolving…" else "▶ Play") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
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
