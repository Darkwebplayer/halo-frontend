package dev.infyplus.halo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.infyplus.halo.FocusScreen
import dev.infyplus.halo.Item
import dev.infyplus.halo.HaloApi
import dev.infyplus.halo.Pomodoro
import dev.infyplus.halo.SetupGate
import dev.infyplus.halo.PomodoroStrip
import java.io.File
import kotlin.test.Test

/**
 * Renders the avatars to PNGs so the drawings can actually be *looked at*.
 *
 * The unit tests pin every number in the expression sheet, but no assertion will tell you the
 * ears are upside down or the pupils sit outside the head. This writes frames to
 * `desktopApp/build/halo-sheet/` for eyeballing.
 *
 * It is a dev tool rather than a test — the only thing it asserts is that rendering does not
 * throw, which on its own exercises every path, brush and nested transform in `CatFace`.
 *
 * Lives in `desktopApp` because Skiko's native binary comes with `compose.desktop.currentOs`,
 * which this module already depends on; `:shared` has no renderer on its test classpath.
 */
class RenderSheet {

    private val out = File("build/halo-sheet").apply { mkdirs() }

    /** Rendered at 2x so the one line weight stays crisp when zoomed. */
    private val DENSITY = 2f

    /** Settle past the entry squash and the 300ms pose tween so a frame shows the resting pose. */
    private fun ImageComposeScene.settled(frames: Int = 40): org.jetbrains.skia.Image {
        var t = 0L
        repeat(frames) { render(t); t += 16_000_000L }
        return render(t)
    }

    private fun write(name: String, width: Int, height: Int, content: @androidx.compose.runtime.Composable () -> Unit) {
        val scene = ImageComposeScene(width, height, density = Density(DENSITY)) {
            Box(Modifier.fillMaxSize().background(HaloPalette.cream)) { content() }
        }
        try {
            File(out, "$name.png").writeBytes(scene.settled().encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    @Test
    fun rendersEveryExpression() {
        for ((id, _) in AVATARS) {
            for (expression in Expression.entries) {
                write("$id-${expression.name.lowercase()}", 520, 520) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AvatarFace(expression, Modifier.size(CatGeometry.CANVAS_DP * 2), avatar = id)
                    }
                }
            }

            // One contact sheet per face, so all eight can be compared side by side in a single
            // look — and against the reference art, which is laid out the same way.
            //
            // Sized to fit 4 x 2 at their natural size, because Row hands out its width in order:
            // too narrow and the first children take their full size while the rest are clamped to
            // whatever is left, which is nothing.
            val cell = (CatGeometry.CANVAS_DP.value * DENSITY).toInt()
            write("_sheet-$id", cell * 4 + 32, cell * 2 + 32) {
                Column(
                    Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Expression.entries.chunked(4).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            row.forEach { AvatarFace(it, Modifier.size(CatGeometry.CANVAS_DP), avatar = id) }
                        }
                    }
                }
            }
        }
        println("wrote ${(Expression.entries.size + 1) * AVATARS.size} frames to ${out.absolutePath}")
    }

    @Test
    fun rendersTheComposedOrb() {
        write("orb-idle-badge", 320, 320) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Orb(Expression.Idle, say = "Due now", unread = 3)
            }
        }
        write("orb-timer", 320, 320) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Orb(Expression.Work, progress = 0.85f, showDial = true, late = true, say = "3:12")
            }
        }
    }

    @Test
    fun rendersTheDialAroundTheOrb() {
        listOf(0.25f to false, 0.9f to true).forEach { (progress, late) ->
            write("dial-${(progress * 100).toInt()}", 260, 260) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ProgressDial(progress, Modifier.size(CatGeometry.ORB_DP), late = late)
                    AvatarFace(Expression.Work, Modifier.size(CatGeometry.CANVAS_DP))
                }
            }
        }
    }

    /** The promoted primitives, side by side — the direct check on the HaloControls extraction. */
    @Test
    fun rendersTheControlKit() {
        // Wide enough that Row never clamps a later child to nothing — same trap as the cat sheet.
        write("kit", 1000, 400) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HaloTab("Chat", selected = true) {}
                    HaloTab("Alerts 3", selected = false) {}
                    Mono("Close")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HaloChip("Snooze 30m") {}
                    HaloChip("Mark done") {}
                    HaloButton("Capture") {}
                    HaloButton("Ask", filled = false) {}
                }
                HaloCard(Modifier.fillMaxWidth()) { Mono("WORKING ON"); HaloField("", "Ask or tell me anything", {}) }
            }
        }
    }

    /**
     * The quick-control strip at exactly the panel's inner width (420dp panel − 2×16dp padding),
     * which is the width it actually has to survive. Driven by a pinned clock so every frame is
     * deterministic — the strip's own 1s loop would otherwise render whatever time it is.
     */
    @Test
    fun rendersTheQuickControlStrip() {
        // 420dp panel less its 2x16dp padding, at 2x — the width the strip actually has to survive.
        val inner = (388 * DENSITY).toInt()

        write("strip-idle", inner, 160) {
            Box(Modifier.padding(16.dp)) { PomodoroStrip(Pomodoro { 1_000_000L }) }
        }

        write("strip-focus", inner, 160) {
            val t = pinnedMidFocus()
            t.attach(Item("a1", "task", "Ship the quarterly deck to the leadership team", createdAt = ""))
            Box(Modifier.padding(16.dp)) { PomodoroStrip(t) }
        }

        write("strip-break", inner, 160) {
            Box(Modifier.padding(16.dp)) { PomodoroStrip(pinnedOnBreak()) }
        }
    }

    /**
     * The first thing a new install shows. Rendered at the desktop setup window's own size, since
     * that is the smallest surface it has to fit.
     */
    @Test
    fun rendersTheSetupScreen() {
        write("setup-screen", (560 * DENSITY).toInt(), (520 * DENSITY).toInt()) {
            HaloTheme { SetupGate {} }
        }
    }

    /**
     * The whole Focus screen at phone width — the layout most likely to overflow.
     *
     * Wrapped in the same `verticalScroll` the app puts it in, deliberately. That is what makes
     * this frame a real check: anything scrollable inside the screen gets measured with unbounded
     * height there and throws, which is exactly how the task picker shipped broken once.
     */
    @Test
    fun rendersTheFocusScreen() {
        val api = HaloApi("http://127.0.0.1:1", "none")
        write("focus-screen", (420 * DENSITY).toInt(), (860 * DENSITY).toInt()) {
            val t = pinnedMidFocus()
            t.attach(Item("a1", "task", "Ship the deck", createdAt = ""))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                FocusScreen(api, t)
            }
        }
    }

    /**
     * A timer stopped dead 7m30s into a focus session.
     *
     * The clock closes over a local that stops moving once this returns, so neither the strip's
     * own one-second loop nor [Pomodoro.catchUp] can change the frame between renders. Without
     * that, these PNGs would show whatever time it happened to be.
     */
    private fun pinnedMidFocus(): Pomodoro {
        var now = 1_000_000L
        val p = Pomodoro { now }
        p.start()
        now += 7 * 60_000L + 30_000L
        return p
    }

    /** One minute into the short break that auto-started when the focus session ran out. */
    private fun pinnedOnBreak(): Pomodoro {
        var now = 1_000_000L
        val p = Pomodoro { now }
        p.start()
        now += 26 * 60_000L
        p.catchUp()
        return p
    }
}
