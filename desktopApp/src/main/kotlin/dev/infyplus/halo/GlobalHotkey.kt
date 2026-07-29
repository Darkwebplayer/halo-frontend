package dev.infyplus.halo

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Summons the window with Ctrl/Cmd + Shift + J from inside any other app.
 *
 * This needs an OS-level hook — a JVM app cannot see keystrokes it does not have focus for.
 * On macOS that means the user must tick Halo under System Settings › Privacy & Security ›
 * Accessibility, and nothing happens until they do.
 *
 * Registration failure is deliberately non-fatal: the tray icon already summons the window, so
 * a missing permission should cost the shortcut, not the app.
 */
object GlobalHotkey {

    /** True once the native hook is actually installed, not merely attempted. */
    @Volatile
    var registered: Boolean = false
        private set

    /** Set when registration failed, so the UI can explain rather than silently do nothing. */
    @Volatile
    var failure: String? = null
        private set

    fun register(onTrigger: () -> Unit) {
        if (registered) return

        // JNativeHook logs every keystroke at INFO by default — noisy, and it would put the
        // user's typing in our logs.
        Logger.getLogger(GlobalScreen::class.java.packageName).apply {
            level = Level.OFF
            useParentHandlers = false
        }

        try {
            GlobalScreen.registerNativeHook()
        } catch (e: NativeHookException) {
            failure = e.message ?: "could not install the keyboard hook"
            return
        }

        GlobalScreen.addNativeKeyListener(object : NativeKeyListener {
            override fun nativeKeyPressed(e: NativeKeyEvent) {
                val modifiers = e.modifiers
                val ctrlOrMeta =
                    (modifiers and NativeKeyEvent.CTRL_MASK) != 0 ||
                        (modifiers and NativeKeyEvent.META_MASK) != 0
                val shift = (modifiers and NativeKeyEvent.SHIFT_MASK) != 0

                if (ctrlOrMeta && shift && e.keyCode == NativeKeyEvent.VC_J) {
                    onTrigger()
                }
            }
        })
        registered = true
    }

    fun unregister() {
        if (!registered) return
        runCatching { GlobalScreen.unregisterNativeHook() }
        registered = false
    }
}
