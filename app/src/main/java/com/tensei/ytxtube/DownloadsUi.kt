package com.tensei.ytxtube

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale

private data class Mp3QualityOption(
    val kbps: Int,
    val title: String,
    val subtitle: String,
)

private val mp3QualityOptions = listOf(
    Mp3QualityOption(320, "320 kbps · Ultra HD MP3", "Studio Master Quality · Best Audio"),
    Mp3QualityOption(256, "256 kbps · High Quality MP3", "Crystal Clear Stereo · Balanced"),
    Mp3QualityOption(128, "128 kbps · Standard MP3", "Fast Download · Good Quality"),
    Mp3QualityOption(64, "64 kbps · Data Saver MP3", "Smallest File Size · Fast"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(video: VideoItem, onDownloadStarted: ((String) -> Unit)? = null, onDismiss: () -> Unit) {
    val c = MaterialTheme.colorScheme
    var activeTab by remember(video.id) { mutableStateOf(0) } // 0 = Video, 1 = MP3 Audio
    var options by remember(video.id) { mutableStateOf<List<DownloadOption>?>(null) }
    var error by remember(video.id) { mutableStateOf<String?>(null) }
    var selectedVideoHeight by remember(video.id) { mutableStateOf<Int?>(null) }
    var selectedMp3Kbps by remember(video.id) { mutableStateOf(320) }

    LaunchedEffect(video.id) {
        runCatching { Downloads.options(video.id) }
            .onSuccess { list ->
                options = list
                selectedVideoHeight = list.firstOrNull()?.height
            }
            .onFailure { error = it.message ?: "Could not load the available qualities" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
        ) {
            Text(
                "Download",
                fontFamily = RoundedHeavy,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(video.thumbnail, Modifier.size(width = 112.dp, height = 63.dp), radius = 12, videoId = video.id)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        video.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        video.channel,
                        color = c.onSurfaceVariant,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(c.surfaceContainerHigh.copy(alpha = 0.65f))
                    .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("Video (MP4/WebM)" to 0, "MP3 Audio" to 1).forEach { (label, idx) ->
                    val isSelected = activeTab == idx
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) {
                                    Brush.horizontalGradient(
                                        listOf(c.primary, c.primary.copy(alpha = 0.85f))
                                    )
                                } else {
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color.Transparent)
                                    )
                                }
                            )
                            .pressable(pressedScale = 0.97f) { activeTab = idx }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (idx == 0) Icons.Rounded.Download else Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = if (isSelected) c.onPrimary else c.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSelected) c.onPrimary else c.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            if (activeTab == 0) {
                when {
                    error != null -> Text(
                        error!!,
                        color = c.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    options == null -> Box(
                        Modifier.fillMaxWidth().padding(vertical = 26.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    }
                    options!!.isEmpty() -> Text(
                        "No video qualities available.",
                        color = c.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            options!!.forEach { option ->
                                QualityChoiceRow(option, selectedVideoHeight == option.height) {
                                    selectedVideoHeight = option.height
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        val choice = options!!.firstOrNull { it.height == selectedVideoHeight }
                        Button(
                            onClick = {
                                if (choice != null) {
                                    val added = Downloads.enqueue(video, choice)
                                    onDownloadStarted?.invoke(
                                        if (added) "Downloading ${choice.label} • ${video.title}"
                                        else "Already in downloads"
                                    )
                                    onDismiss()
                                }
                            },
                            enabled = choice != null,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                        ) {
                            Icon(Icons.Rounded.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (choice == null) "Download Video"
                                else "Download ${choice.label}${sizeSuffix(choice.estimatedBytes)}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    mp3QualityOptions.forEach { mp3 ->
                        val isSelected = selectedMp3Kbps == mp3.kbps
                        val estBytes = if (video.durationSec > 0) (mp3.kbps * 1000L * video.durationSec) / 8L else -1L
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressable(pressedScale = 0.98f) { selectedMp3Kbps = mp3.kbps }
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    if (isSelected) c.primaryContainer.copy(alpha = 0.55f)
                                    else c.surfaceContainerHigh.copy(alpha = 0.35f)
                                )
                                .border(
                                    width = if (isSelected) 1.dp else 0.dp,
                                    color = if (isSelected) c.primary.copy(alpha = 0.45f) else Color.Transparent,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (isSelected) c.primary else c.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(mp3.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                val sub = buildString {
                                    append(mp3.subtitle)
                                    if (estBytes > 0) {
                                        append(" · ~")
                                        append(formatBytes(estBytes))
                                    }
                                    append(" · MP3")
                                }
                                Text(sub, color = c.onSurfaceVariant, fontSize = 11.5.sp)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(14.dp))
                val chosenMp3 = mp3QualityOptions.first { it.kbps == selectedMp3Kbps }
                Button(
                    onClick = {
                        val added = Downloads.enqueueAudio(video)
                        onDownloadStarted?.invoke(
                            if (added) "Downloading ${chosenMp3.kbps}kbps MP3 • ${video.title}"
                            else "MP3 already in downloads"
                        )
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary),
                ) {
                    Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Download ${chosenMp3.kbps} kbps MP3",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }
}
@Composable
private fun QualityChoiceRow(option: DownloadOption, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.98f, onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.primaryContainer.copy(alpha = 0.55f) else c.surfaceContainerHigh.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) c.primary else c.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(option.label, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
            val subtitle = buildString {
                qualityTag(option.height)?.let { append(it) }
                if (option.estimatedBytes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatBytes(option.estimatedBytes))
                }
                if (isNotEmpty()) append(" · ")
                append(option.containerLabel)
                append(if (option.needsMux) " · video + audio" else " · with audio")
            }
            Text(subtitle, color = c.onSurfaceVariant, fontSize = 11.5.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
fun DownloadsScreen(
    contentPadding: PaddingValues,
    onOpen: (VideoItem) -> Unit,
    onOpenMusic: (VideoItem) -> Unit,
    onBrowse: () -> Unit,
    onBrowseMusic: () -> Unit,
    onSearch: () -> Unit,
    onAccount: () -> Unit,
    scrollToTop: Int = 0,
) {
    val tasks = Downloads.tasks
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        Downloads.syncWithStorage()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Downloads.syncWithStorage()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var section by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(0) }
    val videos = tasks.filter { it.height > 0 }
    val music = tasks.filter { it.height == 0 }
    val shown = if (section == 0) videos else music
    CollapsingHeaderLayout(
        resetKey = scrollToTop to listOf(tasks.size, tasks.count { it.status == DownloadStatus.Complete }),
        header = { ScreenHeader(title = "Downloads") { HeaderActions(onSearch, onAccount) } },
    ) { top ->
        LazyColumn(contentPadding = contentPadding.withTop(top)) {
            item(key = "sections") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DownloadSectionPill("Videos", videos.size, section == 0) { section = 0 }
                    DownloadSectionPill("Music", music.size, section == 1) { section = 1 }
                }
            }
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 28.dp)) {
                        EmptyState(
                            icon = if (section == 0) Icons.Rounded.Download else Icons.Rounded.MusicNote,
                            title = if (section == 0) "No videos downloaded yet" else "No music downloaded yet",
                            body = if (section == 0) {
                                "Videos you download are saved here and play without a connection."
                            } else {
                                "Songs you download from Music are saved here for offline listening."
                            },
                            action = {
                                PillButton(
                                    if (section == 0) "Browse videos" else "Browse music",
                                    if (section == 0) onBrowse else onBrowseMusic,
                                )
                            },
                        )
                    }
                }
            } else {
                val active = shown.filter { it.status != DownloadStatus.Complete }
                val finished = shown.filter { it.status == DownloadStatus.Complete }
                if (active.isNotEmpty()) {
                    item(key = "activeTitle") { SectionTitle("Downloading") }
                    items(active, key = { "a-" + it.key }) { task ->
                        DownloadRow(task, onOpen = null)
                    }
                }
                if (finished.isNotEmpty()) {
                    item(key = "doneTitle") { SectionTitle(if (section == 0) "Videos" else "Music") }
                    items(finished, key = { "d-" + it.key }) { task ->
                        DownloadRow(
                            task,
                            onOpen = {
                                if (section == 0) onOpen(task.toVideoItem())
                                else onOpenMusic(task.toVideoItem())
                            },
                        )
                    }
                    item(key = "footer") { Spacer(Modifier.height(20.dp)) }
                }
            }
        }
    }
}

@Composable
private fun DownloadSectionPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) c.primary else c.surfaceContainerHigh)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) c.onPrimary else c.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                color = if (selected) c.onPrimary.copy(alpha = 0.8f) else c.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DownloadRow(task: DownloadTask, onOpen: (() -> Unit)?) {
    val c = MaterialTheme.colorScheme
    var menu by remember(task.key) { mutableStateOf(false) }
    val running = task.status == DownloadStatus.Downloading || task.status == DownloadStatus.Queued ||
        task.status == DownloadStatus.Processing

    Column(
        Modifier
            .fillMaxWidth()
            .pressable(enabled = onOpen != null, pressedScale = 0.985f) { onOpen?.invoke() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box {
                Thumbnail(
                    task.thumbnail,
                    Modifier.size(width = 132.dp, height = 74.dp),
                    radius = 12,
                    videoId = task.videoId,
                )
                DurationPill(task.durationSec, Modifier.align(Alignment.BottomEnd).padding(5.dp), small = true)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    task.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    if (task.height > 0) {
                        "${task.channel} · ${task.height}p${if (task.hdr) " HDR" else ""}"
                    } else {
                        "${task.channel} · Audio"
                    },
                    color = c.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    statusText(task),
                    color = if (task.status == DownloadStatus.Failed) c.error else c.onSurfaceVariant,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { menu = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.MoreVert, "More options", tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    when (task.status) {
                        DownloadStatus.Downloading, DownloadStatus.Queued, DownloadStatus.Processing -> DropdownMenuItem(
                            text = { Text("Pause") },
                            onClick = { menu = false; Downloads.pause(task) },
                        )
                        DownloadStatus.Paused, DownloadStatus.Failed -> DropdownMenuItem(
                            text = { Text("Resume") },
                            onClick = { menu = false; Downloads.resume(task) },
                        )
                        DownloadStatus.Complete -> DropdownMenuItem(
                            text = { Text("Play offline") },
                            onClick = { menu = false; onOpen?.invoke() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = c.error) },
                        onClick = { menu = false; Downloads.remove(task) },
                    )
                }
            }
        }

        if (running || task.status == DownloadStatus.Paused || task.status == DownloadStatus.Failed) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { task.progress },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = if (task.status == DownloadStatus.Failed) c.error else c.primary,
                trackColor = c.onSurfaceVariant.copy(alpha = 0.18f),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    progressText(task),
                    color = c.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when (task.status) {
                        DownloadStatus.Downloading -> "Downloading"
                        DownloadStatus.Queued -> "Waiting"
                        DownloadStatus.Processing -> "Finishing"
                        DownloadStatus.Paused -> "Paused"
                        DownloadStatus.Failed -> "Failed"
                        DownloadStatus.Complete -> "Downloaded"
                    },
                    color = c.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    DownloadStatus.Complete -> "Downloaded · ${formatBytes(task.file.length())}"
    DownloadStatus.Downloading -> "Downloading ${(task.progress * 100).toInt()}%"
    DownloadStatus.Processing -> "Combining video and audio…"
    DownloadStatus.Queued -> "Waiting to download"
    DownloadStatus.Paused -> "Paused · ${formatBytes(task.downloaded)} downloaded"
    DownloadStatus.Failed -> task.error ?: "Download failed"
}

private fun progressText(task: DownloadTask): String {
    val total = task.total
    return if (total > 0) {
        "${formatBytes(task.downloaded)} of ${formatBytes(total)}"
    } else {
        formatBytes(task.downloaded)
    }
}

private fun qualityTag(height: Int): String? = when {
    height >= 2160 -> "4K"
    height >= 1440 -> "2K"
    height >= 1080 -> "Full HD"
    height >= 720 -> "HD"
    height <= 240 -> "Data saver"
    else -> null
}

private fun sizeSuffix(bytes: Long): String =
    if (bytes > 0) " · ${formatBytes(bytes)}" else ""

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
        mb >= 10 -> String.format(Locale.US, "%.0f MB", mb)
        mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}
