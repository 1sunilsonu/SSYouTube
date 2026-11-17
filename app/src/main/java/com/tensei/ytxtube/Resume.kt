package com.tensei.ytxtube

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.common.Player

object ResumeStore {
    val positions = mutableStateMapOf<String, Pair<Long, Long>>()

    fun init(context: Context) {
        positions.clear()
        runCatching {
            context.getSharedPreferences("resume", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    fun startPosition(id: String): Long = 0L

    fun progress(id: String): Float? = null

    fun save(id: String, positionMs: Long, durationMs: Long) {
        // Disabled so every video always starts from 0:00
    }
}

class ResumeSaver(private val player: Player) : Player.Listener {
    fun saveNow() {}
}
