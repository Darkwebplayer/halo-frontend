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
        wirePomodoro()
        openScopeFrom(intent)

        setContent {
            // Setup checklist first; it starts the overlay service once everything required
            // is in place, then gets out of the way. App() supplies HaloTheme itself; the gate
            // needs it too, so it is wrapped here.
            HaloTheme {
                PermissionGate { App() }
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
