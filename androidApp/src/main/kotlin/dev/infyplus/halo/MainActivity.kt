package dev.infyplus.halo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import dev.infyplus.halo.ui.HaloState
import dev.infyplus.halo.ui.HaloTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidNotifier.attach(this)
        // Either this or the overlay service can be the first thing the user reaches, so both
        // attach the settings store and wire the timer. Both are idempotent.
        attachSettings(this)
        Config.load()
        // The shared settings screen offers the tile, but the tile service and its icon live in
        // this module — so the action is registered rather than implemented over there, the same
        // way `Notifications.impl` is. Application context: the prompt only needs the *app* to be
        // in the foreground, and holding an activity in a process-wide object would leak it.
        DeviceOptions.offerTile = { requestQuickSettingsTile(applicationContext) }
        // After attachSettings, which is what supplies the context this needs. Idempotent, so the
        // overlay service starting it too costs nothing.
        startNetworkWatch()
        wirePomodoro()
        openScopeFrom(intent)

        setContent {
            // Credentials, then permissions, then the app. That order matters: PermissionGate is
            // the only thing that starts OverlayService, and the sync loop lives in that service —
            // so gating it behind SetupGate keeps an unconfigured install off the network without
            // inventing a second stop condition.
            HaloTheme {
                SetupGate {
                    PermissionGate {
                        // No longer restarts the overlay service on a credentials change: the
                        // overlay builds its clients from Config inside its own composition, and
                        // Config is Compose state, so it follows along on its own. The stop/start
                        // that used to be here raced its own restart.
                        App()
                    }
                }
            }
        }
    }

    /**
     * A second tap while we are already running arrives here rather than through [onCreate],
     * which is why the activity is `singleTop` — otherwise each notification would stack another
     * copy of the app and the scope would be applied to an instance nobody is looking at.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openScopeFrom(intent)
    }

    /**
     * Hand the tapped notification's item to the overlay, which opens its panel scoped to it.
     *
     * Published as an id rather than resolved here: the panel already loads the check-in history
     * that contains the item, so this avoids a lookup and keeps one code path shared with tapping
     * a row in that same list.
     */
    private fun openScopeFrom(intent: Intent?) {
        intent?.getStringExtra(AndroidNotifier.EXTRA_ITEM_ID)?.let { HaloState.shared.requestScope(it) }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
