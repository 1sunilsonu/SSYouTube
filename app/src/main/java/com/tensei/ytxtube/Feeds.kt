package com.tensei.ytxtube

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private object SeenFeedStore {
    private const val PREFS_NAME = "ss_seen_feed_prefs"
    private const val KEY_SEEN_IDS = "seen_video_ids"
    private const val MAX_SEEN = 350

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val seenIds = LinkedHashSet<String>()

    fun ensureInit(context: Context? = null) {
        synchronized(lock) {
            if (prefs != null) return
            val appCtx = context?.applicationContext ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Application
            }.getOrNull() ?: return

            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val saved = p.getString(KEY_SEEN_IDS, null).orEmpty()
            if (saved.isNotBlank()) {
                saved.split(',').filter { it.isNotBlank() }.forEach { seenIds.add(it) }
            }
        }
    }

    fun isSeen(id: String): Boolean {
        ensureInit()
        synchronized(lock) {
            return id in seenIds
        }
    }

    fun markSeen(ids: Iterable<String>) {
        ensureInit()
        synchronized(lock) {
            var changed = false
            for (id in ids) {
                if (id.isNotBlank()) {
                    seenIds.remove(id)
                    seenIds.add(id)
                    changed = true
                }
            }
            if (!changed) return
            while (seenIds.size > MAX_SEEN) {
                val oldest = seenIds.firstOrNull() ?: break
                seenIds.remove(oldest)
            }
            prefs?.edit()?.putString(KEY_SEEN_IDS, seenIds.joinToString(","))?.apply()
        }
    }
}

internal object UserTasteStore {
    private const val PREFS_NAME = "ss_user_taste_prefs"
    private const val KEY_WATCHED = "watched_history_json"
    private const val MAX_WATCHED = 25

    data class WatchedEntry(val id: String, val title: String, val channel: String, val topic: String)

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val entries = ArrayList<WatchedEntry>()

    fun ensureInit(context: Context? = null) {
        synchronized(lock) {
            if (prefs != null) return
            val appCtx = context?.applicationContext ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null) as? Application
            }.getOrNull() ?: return

            val p = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
            val raw = p.getString(KEY_WATCHED, null).orEmpty()
            if (raw.isNotBlank()) {
                runCatching {
                    val arr = JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        val title = obj.optString("title")
                        val channel = obj.optString("channel")
                        val topic = obj.optString("topic").ifBlank { extractTopic(title, channel) }
                        if (id.isNotBlank()) {
                            entries.add(WatchedEntry(id, title, channel, topic))
                        }
                    }
                }
            }
        }
    }

    fun recordWatch(video: VideoItem) {
        if (video.id.isBlank()) return
        ensureInit()
        synchronized(lock) {
            entries.removeAll { it.id == video.id }
            val topic = extractTopic(video.title, video.channel)
            entries.add(0, WatchedEntry(video.id, video.title, video.channel, topic))
            while (entries.size > MAX_WATCHED) {
                entries.removeAt(entries.lastIndex)
            }
            saveLocked()
        }
    }

    fun snapshot(): List<WatchedEntry> {
        ensureInit()
        synchronized(lock) {
            return entries.toList()
        }
    }

    private fun saveLocked() {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("title", e.title)
                    .put("channel", e.channel)
                    .put("topic", e.topic)
            )
        }
        prefs?.edit()?.putString(KEY_WATCHED, arr.toString())?.apply()
    }

    private fun extractTopic(title: String, channel: String): String {
        val cleaned = title
            .replace(Regex("\\[[^\\]]*\\]"), " ")
            .replace(Regex("\\([^)]*\\)"), " ")
            .substringBefore('|')
            .substringBefore(" - ")
            .replace(Regex("#\\S+"), " ")
            .replace(Regex("\\b(official|video|audio|lyrical|lyrics|full|song|hd|4k|8k|2024|2025|2026|new|latest|ft|feat)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val words = cleaned.split(' ').filter { it.length > 1 }.take(5)
        val phrase = words.joinToString(" ")
        return when {
            phrase.length >= 4 -> phrase
            channel.isNotBlank() -> channel.trim()
            else -> title.take(35).trim()
        }
    }
}

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

    val home = FeedState(scope, freshOnRefresh = false) { loadYouTubeStyleHome(it) }
    val subscriptions = FeedState(scope) { InnerTube.subscriptionsFeed(it) }
    val history = FeedState(scope) { InnerTube.history(it) }
    var channels by mutableStateOf<List<ChannelRef>>(emptyList())
        private set
    var playlists by mutableStateOf<List<PlaylistRef>>(emptyList())
        private set
    private val scope = scope

    private val indianDiscoveryQueries = listOf(
        "latest hindi songs official video",
        "new punjabi songs official video",
        "bollywood hit songs official video",
        "trending indian songs official music video",
        "new rajasthani dj song official video",
        "latest hindi romantic songs official video",
        "new indian pop songs official video",
        "trending music india official video",
        "arijit singh latest songs official",
        "indian viral trending videos today",
    )

    private suspend fun fetchFreshQueryVideos(query: String, targetCount: Int): List<VideoItem> {
        val page1 = runCatching { YouTube.searchPage(query, null) }.getOrNull() ?: return emptyList()
        val unseen1 = page1.videos.filterNot { SeenFeedStore.isSeen(it.id) }
        if (unseen1.size >= targetCount || page1.next == null) {
            return if (unseen1.isNotEmpty()) unseen1.shuffled() else page1.videos.shuffled()
        }
        val page2 = runCatching { YouTube.searchPage(query, page1.next) }.getOrNull()
        val unseen2 = page2?.videos?.filterNot { SeenFeedStore.isSeen(it.id) }.orEmpty()
        val combined = (unseen1 + unseen2)
        return if (combined.isNotEmpty()) combined.shuffled() else (page1.videos + page2?.videos.orEmpty()).shuffled()
    }

    private suspend fun fetchRelatedForWatched(videoId: String): List<VideoItem> {
        return runCatching {
            val related = YouTube.details(videoId).related.filter { !it.isLive }
            val unseen = related.filterNot { SeenFeedStore.isSeen(it.id) }
            if (unseen.isNotEmpty()) unseen.shuffled() else related.shuffled()
        }.getOrDefault(emptyList())
    }

    private suspend fun loadYouTubeStyleHome(next: String?): FeedPage {
        SeenFeedStore.ensureInit()
        UserTasteStore.ensureInit()

        val pageNum = next?.removePrefix("yt_page:")?.toIntOrNull() ?: 1
        val watched = UserTasteStore.snapshot()
        val searches = SearchHistory.queries

        return coroutineScope {
            val pickedWatched = if (watched.isNotEmpty()) {
                val latest = watched.first()
                val older = watched.drop(1).shuffled().take(1)
                (listOf(latest) + older).distinctBy { it.id }
            } else emptyList()

            val relatedDefs = pickedWatched.map { entry ->
                async { fetchRelatedForWatched(entry.id).take(10) }
            }

            val watchTopicQuery = watched.shuffled().firstOrNull()?.let { entry ->
                if (pageNum % 2 == 0 && entry.channel.isNotBlank()) entry.channel else entry.topic
            }
            val watchTopicDef = async {
                if (!watchTopicQuery.isNullOrBlank()) fetchFreshQueryVideos(watchTopicQuery, 8).take(8)
                else emptyList()
            }

            val searchQuery = when {
                searches.isEmpty() -> null
                pageNum == 1 && searches.size > 1 -> listOf(searches[0], searches.drop(1).random()).random()
                else -> searches.take(8).random()
            }
            val searchDef = async {
                if (!searchQuery.isNullOrBlank()) fetchFreshQueryVideos(searchQuery, 8).take(8)
                else emptyList()
            }

            val trendingDef = async {
                runCatching {
                    val raw = if (Account.signedIn && pageNum == 1) {
                        InnerTube.homeFeed(null).videos
                    } else {
                        YouTube.trending(null).videos
                    }
                    val unseen = raw.filterNot { SeenFeedStore.isSeen(it.id) }
                    (if (unseen.isNotEmpty()) unseen else raw).shuffled().take(8)
                }.getOrDefault(emptyList())
            }

            val discoveryQueries = indianDiscoveryQueries.shuffled().take(if (watched.isEmpty() && searches.isEmpty()) 3 else 1)
            val discoveryDefs = discoveryQueries.map { q ->
                async { fetchFreshQueryVideos(q, 8).take(8) }
            }

            val relatedLists = relatedDefs.map { it.await() }
            val watchTopicVideos = watchTopicDef.await()
            val searchVideos = searchDef.await()
            val trendingVideos = trendingDef.await()
            val discoveryLists = discoveryDefs.map { it.await() }

            val merged = ArrayList<VideoItem>()
            val seenInBatch = HashSet<String>()
            fun addUnique(v: VideoItem?) {
                if (v != null && seenInBatch.add(v.id)) {
                    merged.add(v)
                }
            }

            val maxRows = 12
            for (i in 0 until maxRows) {
                for (rel in relatedLists) {
                    addUnique(rel.getOrNull(i))
                }
                addUnique(watchTopicVideos.getOrNull(i))
                addUnique(searchVideos.getOrNull(i))
                addUnique(trendingVideos.getOrNull(i))
                for (disc in discoveryLists) {
                    addUnique(disc.getOrNull(i))
                }
            }

            SeenFeedStore.markSeen(merged.take(18).map { it.id })
            val nextToken = if (merged.isNotEmpty() && pageNum < 15) "yt_page:${pageNum + 1}" else null
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
    val context = LocalContext.current
    remember(context) {
        SeenFeedStore.ensureInit(context)
        UserTasteStore.ensureInit(context)
        true
    }

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
                    onClick = {
                        SeenFeedStore.markSeen(listOf(video.id))
                        UserTasteStore.recordWatch(video)
                        onOpen(video)
                    },
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
