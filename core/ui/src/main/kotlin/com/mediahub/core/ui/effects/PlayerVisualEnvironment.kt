package com.mediahub.core.ui.effects

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat

/** Live Power Saver signal used by AUTO/BATTERY frame policy. */
@Composable
fun rememberPowerSaveMode(context: Context): Boolean {
    val appContext = context.applicationContext
    val powerManager = remember(appContext) {
        appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    }
    var enabled by remember(powerManager) { mutableStateOf(powerManager.isPowerSaveMode) }
    DisposableEffect(appContext, powerManager) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                enabled = powerManager.isPowerSaveMode
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { appContext.unregisterReceiver(receiver) } }
    }
    return enabled
}

/**
 * Treats a disabled or near-zero animator duration scale as a reduce-motion request and observes
 * live setting changes. Read failures are conservative (normal motion) and never break playback.
 */
@Composable
fun rememberReduceMotion(context: Context): Boolean {
    val resolver = context.contentResolver
    fun read(): Boolean = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) <= 0.1f
    }.getOrDefault(false)

    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
