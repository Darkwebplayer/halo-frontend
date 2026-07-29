package dev.infyplus.halo

import android.view.View
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A ComposeView added straight to WindowManager has no Activity behind it, so nothing supplies
 * the lifecycle, ViewModelStore and SavedStateRegistry that Compose looks up through the view
 * tree. Without these the view inflates and then immediately crashes on first composition.
 *
 * This is the smallest thing that satisfies those three lookups for a service-owned window.
 */
class OverlayHost :
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /**
     * Back has to be handled, not inherited.
     *
     * While the panel is open the overlay window is focusable, which means it receives the back
     * key — and a window with nowhere to send it simply swallows it, leaving the user stuck with
     * a panel they cannot dismiss and a back button that does nothing.
     */
    override val onBackPressedDispatcher = OnBackPressedDispatcher()

    fun attach(view: View) {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
        view.setViewTreeOnBackPressedDispatcherOwner(this)
    }

    fun detach() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }
}
