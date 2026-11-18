/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recognition

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.metrolist.shazamkit.models.RecognitionResult

/**
 * Hosts the recognizer card in a window above other apps. Compose needs the three owners an
 * Activity normally provides, so this supplies them itself. One instance per show: a
 * LifecycleRegistry cannot be restarted once it has reached ON_DESTROY.
 */
class RecognitionOverlay(
    private val service: RecognitionForegroundService,
) : LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val preferences =
        service.getSharedPreferences(RecognitionForegroundService.PREFS_NAME, Context.MODE_PRIVATE)
    private val dragAccumulator = DragAccumulator()

    private lateinit var view: ComposeView
    private lateinit var params: WindowManager.LayoutParams

    override val lifecycle get() = lifecycleRegistry
    override val viewModelStore get() = store
    override val savedStateRegistry get() = savedStateController.savedStateRegistry

    var source by mutableStateOf(readSource())
        private set

    fun show() {
        MusicRecognitionService.overlayActive = true
        savedStateController.performRestore(null)
        view = ComposeView(service)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setContent { RecognitionOverlayContent(this) }

        params =
            WindowManager
                .LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = preferences.getInt(RecognitionForegroundService.PREF_OVERLAY_X, 0)
                    y = preferences.getInt(RecognitionForegroundService.PREF_OVERLAY_Y, 0)
                }
        windowManager.addView(view, params)
        if (!preferences.contains(RecognitionForegroundService.PREF_OVERLAY_X)) {
            view.doOnLayout { placeFirstTime(it.width) }
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun hide() {
        MusicRecognitionService.overlayActive = false
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        windowManager.removeView(view)
        store.clear()
    }

    fun selectSource(value: RecognitionSource) {
        source = value
        preferences.edit().putString(RecognitionForegroundService.PREF_OVERLAY_SOURCE, value.name).apply()
    }

    fun identify() = service.startOverlayRecognition(source)

    fun cancel() = service.cancelOverlayRecognition()

    fun close() = service.hideOverlay()

    fun openResult(result: RecognitionResult) = service.openRecognitionResult(result)

    fun beginDrag() = dragAccumulator.reset()

    fun drag(
        dx: Float,
        dy: Float,
    ) {
        val (moveX, moveY) = dragAccumulator.step(dx, dy) ?: return
        moveTo(params.x + moveX, params.y + moveY)
    }

    fun endDrag() {
        preferences
            .edit()
            .putInt(RecognitionForegroundService.PREF_OVERLAY_X, params.x)
            .putInt(RecognitionForegroundService.PREF_OVERLAY_Y, params.y)
            .apply()
    }

    private fun placeFirstTime(windowWidth: Int) {
        val area = screenArea()
        moveTo((area.width - windowWidth) / 2, area.height / 5)
        endDrag()
    }

    private fun moveTo(
        x: Int,
        y: Int,
    ) {
        val area = screenArea()
        val (clampedX, clampedY) = area.clamp(x, y, view.width, view.height)
        if (clampedX == params.x && clampedY == params.y) return
        params.x = clampedX
        params.y = clampedY
        windowManager.updateViewLayout(view, params)
    }

    private fun screenArea(): OverlayArea {
        val metrics = service.resources.displayMetrics
        return OverlayArea(metrics.widthPixels, metrics.heightPixels)
    }

    private fun readSource(): RecognitionSource =
        preferences.getString(RecognitionForegroundService.PREF_OVERLAY_SOURCE, null)?.let {
            RecognitionSource.valueOf(it)
        } ?: RecognitionSource.MICROPHONE
}