package com.tensei.ytxtube

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.math.roundToInt

object PlayerControlSettings {
    private const val KEY_ENABLED = "show_quick_speed_quality"
    private var prefs: SharedPreferences? = null

    var showQuickControls by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        showQuickControls = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    }

    fun set(on: Boolean) {
        showQuickControls = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }
}

object PipSettings {
    private const val KEY_ENABLED = "app_pip_enabled"
    private var prefs: SharedPreferences? = null

    var enabled by mutableStateOf(true)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    }

    fun set(on: Boolean) {
        enabled = on
        prefs?.edit()?.putBoolean(KEY_ENABLED, on)?.apply()
    }
}

object MiniPlayerSettings {
    private const val KEY_USE_LIQUID = "use_liquid_mini_player"
    private var prefs: SharedPreferences? = null

    var useLiquidMiniPlayer by mutableStateOf(false)
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
        useLiquidMiniPlayer = prefs?.getBoolean(KEY_USE_LIQUID, false) ?: false
    }

    fun set(on: Boolean) {
        useLiquidMiniPlayer = on
        prefs?.edit()?.putBoolean(KEY_USE_LIQUID, on)?.apply()
    }
}

object RefreshRateSettings {
    private const val PREFS_NAME = "ss_display_prefs"
    private const val KEY_HZ = "target_refresh_rate_hz"

    // 0 = Auto (device max), 60 = 60Hz, 90 = 90Hz, 120 = 120Hz
    var targetHz by mutableIntStateOf(loadSavedHz())
        private set

    private fun prefs(): SharedPreferences? = runCatching {
        YTXTubeApplication.appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }.getOrNull()

    private fun loadSavedHz(): Int = prefs()?.getInt(KEY_HZ, 0) ?: 0

    val displayLabel: String
        get() = when (targetHz) {
            0 -> "Auto (Max Smooth)"
            else -> "$targetHz Hz"
        }

    fun set(hz: Int) {
        targetHz = hz
        prefs()?.edit()?.putInt(KEY_HZ, hz)?.apply()
    }

    fun supportedRates(activity: Activity?): List<Int> {
        if (activity == null) return listOf(60, 90, 120)
        return runCatching {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            } ?: return listOf(60, 90, 120)

            val current = disp.mode
            val sameRes = disp.supportedModes.filter {
                it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
            }.ifEmpty { disp.supportedModes.toList() }

            sameRes.map { it.refreshRate.roundToInt() }
                .distinct()
                .sorted()
                .ifEmpty { listOf(60, 90, 120) }
        }.getOrDefault(listOf(60, 90, 120))
    }

    fun applyToActivity(activity: Activity) {
        runCatching {
            val disp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.display
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                activity.windowManager.defaultDisplay
            } else null ?: return

            val currentMode = disp.mode
            val modes = disp.supportedModes
            val sameResModes = modes.filter {
                it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
            }.ifEmpty { modes.toList() }

            val desired = targetHz
            val chosenMode = if (desired <= 0) {
                sameResModes.maxByOrNull { it.refreshRate }
            } else {
                sameResModes.minByOrNull { abs(it.refreshRate - desired.toFloat()) }
                    ?.takeIf { abs(it.refreshRate - desired.toFloat()) <= 5f }
                    ?: sameResModes.filter { it.refreshRate <= desired + 2f }.maxByOrNull { it.refreshRate }
                    ?: sameResModes.maxByOrNull { it.refreshRate }
            }

            if (chosenMode != null) {
                val lp = activity.window.attributes
                lp.preferredDisplayModeId = chosenMode.modeId
                lp.preferredRefreshRate = chosenMode.refreshRate
                activity.window.attributes = lp
            }
        }
    }
}
