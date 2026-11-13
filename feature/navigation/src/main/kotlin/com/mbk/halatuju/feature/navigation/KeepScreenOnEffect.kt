package com.mbk.halatuju.feature.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Keeps the screen awake strictly using `FLAG_KEEP_SCREEN_ON` + view `keepScreenOn` while:
 * 1) active guidance is visible on screen (`keepScreenOn == true`), AND
 * 2) the hosting lifecycle is in the foreground (`ON_RESUME` .. before `ON_PAUSE`).
 *
 * Automatically releases immediately on End, on Arrival (`keepScreenOn = false`),
 * when backgrounded (`ON_PAUSE` / `ON_STOP`), or when disposed. Never holds a `PARTIAL_WAKE_LOCK`.
 */
@Composable
fun KeepScreenOnEffect(keepScreenOn: Boolean) {
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentKeepScreenOn = rememberUpdatedState(keepScreenOn)

    DisposableEffect(view, lifecycleOwner, keepScreenOn) {
        val window = view.context.findActivityOrNull()?.window

        fun applyWakeState(activeForeground: Boolean) {
            val shouldKeep = activeForeground && currentKeepScreenOn.value
            view.keepScreenOn = shouldKeep
            if (window != null) {
                if (shouldKeep) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        val initialActive = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        applyWakeState(initialActive)

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> applyWakeState(true)
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> applyWakeState(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view.keepScreenOn = false
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
