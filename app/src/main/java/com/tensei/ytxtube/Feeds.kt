package com.tensei.ytxtube

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Stable
class FeedState(
    private val scope: CoroutineScope,
    private val freshOnRefresh: Boolean = false,
    private val loader: suspend (next: String?) -> FeedPage,
) {
    var videos by mutableStateOf<List<VideoItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    var generation by mutableStateOf(0)
        private set

    var refreshing by mutableStateOf(false)
        private set
    private var next: String? = null
    private var loaded = false

    private val shown = HashSet<String>()
    private var job: Job? = null
    val hasMore get() = next != null

    fun ensureLoaded() { if (!loaded && !loading) refresh() }

    fun refresh() {
        loaded = true
        loading = true
        refreshing = videos.isNotEmpty()
        error = null
        job?.cancel()
        job = scope.launch {
            runCatching { if (freshOnRefresh && shown.isNotEmpty()) loadFresh() else loader(null) }
                .onSuccess {
                    videos = it.videos
                    next = it.next
                    generation++
                    if (freshOnRefresh) it.videos.forEach { v -> shown += v.id }
                }
                .onFailure { error = friendly(it) }
            loading = false
            refreshing = false
        }
    }

    fun loadMore() {
        val token = next ?: return
        if (loading) return
        loading = true
        job = scope.launch {
            runCatching { loader(token) }
                .onSuccess { page ->
                    val seen = videos.mapTo(HashSet()) { it.id }
                    val added = page.videos.filter { seen.add(it.id) }
                    videos = videos + added
                    if (freshOnRefresh) added.forEach { v -> shown += v.id }
                    next = page.next
                }
                .onFailure { error = friendly(it) }
            loading = false
        }
    }

    private suspend fun loadFresh(): FeedPage {
        val first = loader(null)
        val fresh = LinkedHashMap<String, VideoItem>()
        var token = first.next
        first.videos.filter { it.id !in shown }.forEach { fresh[it.id] = it }
        var pages = 1
        while (fresh.size < FRESH_TARGET && token != null && pages < FRESH_PAGES) {
            val page = loader(token)
            page.videos.filter { it.id !in shown }.forEach { fresh.putIfAbsent(it.id, it) }
            token = page.next
            pages++
        }
        if (fresh.isEmpty()) return first
        return FeedPage(fresh.values.toList(), token)
    }

    fun reset() {
        job?.cancel()
        shown.clear()
        videos = emptyList(); next = null; loaded = false; loading = false; error = null
    }

    private companion object {
        const val FRESH_TARGET = 16
        const val FRESH_PAGES = 5
    }

    private fun friendly(t: Throwable) = when (t) {
        is NotSignedInException -> "You're signed out."
        else -> t.message?.takeIf { it.startsWith("YouTube") } ?: YouTube.friendlyError(t)
    }
}

@Stable
class LibraryData(scope: CoroutineScope) {

    val home = FeedState(scope, freshOnRefresh = false) { loadIndianPersonalizedHome(it) }
    val subscriptions = FeedState(scope) { InnerTube.subscriptionsFeed(it) }
    val history = FeedState(scope) { InnerTube.history(it) }
    var channels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope

    private suspend fun loadIndianPersonalizedHome(next: String?): FeedPage {
        if (next != null) {
            return when {
                next.startsWith("search:") -> {
                    val payload = next.removePrefix("search:")
                    val sep = payload.indexOf('|')
                    if (sep <= 0) FeedPage(emptyList(), null)
                    else {
                        val q = payload.substring(0, sep)
                        val token = payload.substring(sep + 1)
                        val page = YouTube.searchPage(q, token)
                        FeedPage(page.videos, page.next?.let { "search:$q|$it" })
                    }
                }
                next.startsWith("indian_songs|") -> {
                    val token = next.removePrefix("indian_songs|")
                    val page = YouTube.searchPage("latest hindi punjabi songs official video", token)
                    FeedPage(page.videos, page.next?.let { "indian_songs|$it" })
                }
                else -> InnerTube.homeFeed(next)
            }
        }

        val recent = SearchHistory.queries.take(3)
        if (recent.isNotEmpty()) {
            return coroutineScope {
                val primaryDef = async { YouTube.searchPage(recent[0], null) }
                val secondaryDefs = recent.drop(1).map { q ->
                    async { runCatching { YouTube.searchPage(q, null).videos.take(6) }.getOrDefault(emptyList()) }
                }
                val primary = primaryDef.await()
                val secondaryLists = secondaryDefs.map { it.await() }

                val merged = ArrayList<VideoItem>()
                val seen = HashSet<String>()
                fun addUnique(items: List<VideoItem>) {
                    for (v in items) {
                        if (seen.add(v.id)) merged.add(v)
                    }
                }

                addUnique(primary.videos.take(8))
                for (sec in secondaryLists) {
                    addUnique(sec)
                }
                addUnique(primary.videos.drop(8))

                val nextToken = primary.next?.let { "search:${recent[0]}|$it" }
                FeedPage(merged, nextToken)
            }
        }

        return coroutineScope {
            val hindiSongsDef = async {
                runCatching { YouTube.searchPage("latest hindi punjabi songs official video", null) }
                    .getOrDefault(FeedPage(emptyList(), null))
            }
            val bollywoodDef = async {
                runCatching { YouTube.searchPage("new bollywood hit songs official video", null).videos.take(10) }
                    .getOrDefault(emptyList())
            }
            val indiaTrendingDef = async {
                runCatching {
                    if (Account.signedIn) InnerTube.homeFeed(null).videos.take(8)
                    else YouTube.trending(null).videos.take(8)
                }.getOrDefault(emptyList())
            }

            val hindiPage = hindiSongsDef.await()
            val bollywood = bollywoodDef.await()
            val trending = indiaTrendingDef.await()

            val merged = ArrayList<VideoItem>()
            val seen = HashSet<String>()
            fun addUnique(v: VideoItem) {
                if (seen.add(v.id)) merged.add(v)
            }

            val maxLen = maxOf(hindiPage.videos.size, bollywood.size, trending.size)
            for (i in 0 until maxLen) {
                hindiPage.videos.getOrNull(i * 2)?.let(::addUnique)
                hindiPage.videos.getOrNull(i * 2 + 1)?.let(::addUnique)
                bollywood.getOrNull(i)?.let(::addUnique)
                if (i % 2 == 0) trending.getOrNull(i / 2)?.let(::addUnique)
            }

            val nextToken = hindiPage.next?.let { "indian_songs|$it" }
            FeedPage(merged, nextToken)
        }
    }

    fun loadChannels() = scope.launch { runCatching { InnerTube.channels() }.onSuccess { channels = it } }
    fun loadPlaylists() = scope.launch { runCatching { InnerTube.playlists() }.onSuccess { playlists = it } }

    fun playlistFeed(id: String) = FeedState(scope) {
        if (id == "MDL") {
            val items = Downloads.musicDownloads.map { it.toVideoItem() }
            FeedPage(items, null)
        } else if (id == "DL") {
            FeedPage(emptyList(), null)
        } else {
            InnerTube.playlist(id, it)
        }
    }

    fun reset() {
        home.reset(); subscriptions.reset(); history.reset()
        channels = emptyList(); playlists = emptyList()
    }
}

@Composable
fun rememberScrollToTop(signal: Int): LazyListState {
    val state = rememberLazyListState()
    val initial = remember { signal }
    LaunchedEffect(signal) { if (signal != initial) state.animateScrollToItem(0) }
    return state
}

@Composable
fun FeedList(
    feed: FeedState,
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    header: LazyListScope.() -> Unit = {},
    scrollToTop: Int = 0,
    pullToRefresh: Boolean = true,
    indicatorTop: Dp = 0.dp,
    videos: List<VideoItem>? = null,
    filterScrollKey: Any? = null,
    channelAvatarSeed: Map<String, String?> = emptyMap(),
) {
    val c = MaterialTheme.colorScheme
    val listVideos = videos ?: feed.videos
    val scope = rememberCoroutineScope()
    LaunchedEffect(listVideos, channelAvatarSeed) {
        ChannelAvatars.resolve(scope, listVideos, channelAvatarSeed)
    }
    val list = rememberScrollToTop(scrollToTop)

    LaunchedEffect(feed.generation, filterScrollKey) { list.scrollToItem(0) }

    LaunchedEffect(list.isScrollInProgress, listVideos) {
        if (!list.isScrollInProgress && listVideos.isNotEmpty()) {
            delay(350L)
            val validIds = listVideos.mapTo(HashSet()) { it.id }
            val visibleIds = list.layoutInfo.visibleItemsInfo
                .mapNotNull { it.key as? String }
                .filter { it in validIds }
                .take(2)
            visibleIds.forEach { videoId ->
                launch(Dispatchers.IO) {
                    val info = runCatching { StreamCache.getOrLoad(videoId) }.getOrNull() ?: return@launch
                    val streamUrl = info.videoOnlyStreams.firstOrNull { it.isUrl }?.content
                        ?: info.videoStreams.firstOrNull { it.isUrl }?.content
                        ?: info.audioStreams.firstOrNull { it.isUrl }?.content
                    Net.noteMediaHost(streamUrl)
                }
            }
        }
    }

    val nearEnd by remember {
        derivedStateOf {
            val last = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= list.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, listVideos.size) { if (nearEnd && listVideos.isNotEmpty()) feed.loadMore() }
    PullToRefresh(enabled = pullToRefresh, refreshing = feed.refreshing, onRefresh = feed::refresh, topPadding = indicatorTop) {
        LazyColumn(state = list, contentPadding = contentPadding) {
            header()
            items(listVideos, key = { it.id }) { video ->
                VideoCard(
                    video,
                    onClick = { onOpen(video) },
                    modifier = Modifier.animateItem(),
                )
            }
            when {
                feed.error != null && listVideos.isEmpty() -> item {
                    EmptyState(
                        icon = Icons.Rounded.CloudOff,
                        title = "Couldn't load this",
                        body = feed.error.orEmpty(),
                        action = { PillButton("Try again", { feed.refresh() }) },
                    )
                }
                feed.loading || feed.hasMore -> item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), Alignment.Center) {
                        CircularProgressIndicator(color = c.primary, strokeWidth = 4.dp, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PullToRefresh(enabled: Boolean, refreshing: Boolean, onRefresh: () -> Unit, topPadding: Dp = 0.dp, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val c = MaterialTheme.colorScheme
    val pull = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        indicator = {
            androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                state = pull, isRefreshing = refreshing,
                containerColor = c.primaryContainer, color = c.onPrimaryContainer,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = topPadding),
            )
        },
    ) { content() }
}
