package com.example.blockingapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * While blocking is on (VPN running and inside the zone), sends a blocked app to the home screen as soon as it comes to
 * the foreground, and drags it away if it ends up in a picture-in-picture window.
 * Must be enabled in the accessibility settings (or by [AccessibilityGuard]).
 */
class AppBlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var dismissingPip = false

    override fun onServiceConnected() {
        instance = this
        connected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!Zone.blocking) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (isBlocked(pkg)) closeApp(pkg)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                if (Build.VERSION.SDK_INT >= 26) dismissBlockedPip()
        }
    }

    private fun isBlocked(pkg: String) = pkg != packageName && pkg in Prefs.apps(this)

    /** Blocking just turned on (back in the zone): deal with a blocked app that is already open. */
    private fun checkForeground() {
        if (!Zone.blocking) return
        rootInActiveWindow?.packageName?.toString()?.let { if (isBlocked(it)) closeApp(it) }
        if (Build.VERSION.SDK_INT >= 26) dismissBlockedPip()
    }

    private fun closeApp(pkg: String) {
        // Video apps jump into picture-in-picture on Home while playing; pausing first avoids most of it.
        pauseMedia()
        performGlobalAction(GLOBAL_ACTION_HOME)
        Toast.makeText(this, "${InstalledApps.label(this, pkg)} is blocked", Toast.LENGTH_SHORT).show()
    }

    /**
     * A blocked app in a picture-in-picture window: drag the window onto the dismiss target at the
     * bottom of the screen, like a finger would. Samsung's PiP window has no accessibility action
     * to close it. Touching the screen cancels the drag, so it is retried until the window is gone.
     */
    @RequiresApi(26)
    private fun dismissBlockedPip() {
        if (dismissingPip) return
        val pip = windows.firstOrNull { it.isInPictureInPictureMode } ?: return
        val pkg = pip.root?.packageName?.toString() ?: return
        if (!isBlocked(pkg)) return
        pauseMedia()

        val bounds = Rect().also { pip.getBoundsInScreen(it) }
        val screen = screenSize()
        val path = Path().apply {
            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
            lineTo(screen.width() / 2f, screen.height() * 0.92f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, PIP_DRAG_MS))
            .build()
        dismissingPip = true
        val done = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = retry()

            override fun onCancelled(gestureDescription: GestureDescription) = retry()
        }
        if (!dispatchGesture(gesture, done, null)) retry()
    }

    /** Checks again shortly; stops by itself once no blocked app is in picture-in-picture. */
    @RequiresApi(26)
    private fun retry() {
        dismissingPip = false
        handler.postDelayed({ if (Zone.blocking) dismissBlockedPip() }, PIP_RETRY_MS)
    }

    private fun screenSize(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds
        } else {
            val m = resources.displayMetrics
            Rect(0, 0, m.widthPixels, m.heightPixels)
        }
    }

    private fun pauseMedia() {
        val audio = getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        connected = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val PIP_DRAG_MS = 700L
        private const val PIP_RETRY_MS = 300L

        private var instance: AppBlockerService? = null

        /** True while Android has the service enabled and bound. */
        var connected by mutableStateOf(false)
            private set

        fun recheckForeground() {
            instance?.checkForeground()
        }
    }
}
