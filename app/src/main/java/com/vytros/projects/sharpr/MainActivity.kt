package com.vytros.projects.sharpr

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vytros.projects.sharpr.data.Accent
import com.vytros.projects.sharpr.data.AppFont
import com.vytros.projects.sharpr.data.AppSettings
import com.vytros.projects.sharpr.databinding.ActivityMainBinding
import com.vytros.projects.sharpr.engine.JobState
import com.vytros.projects.sharpr.ui.CreateFragment
import com.vytros.projects.sharpr.ui.DetailFragment
import com.vytros.projects.sharpr.ui.Dialogs
import com.vytros.projects.sharpr.ui.EditFragment
import com.vytros.projects.sharpr.ui.LibraryFragment
import com.vytros.projects.sharpr.ui.Navigator
import com.vytros.projects.sharpr.ui.SettingsFragment
import com.vytros.projects.sharpr.ui.widgets.NavSpec
import com.vytros.projects.sharpr.ui.widgets.dpi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), Navigator {
    private lateinit var b: ActivityMainBinding
    private lateinit var graph: Graph
    private var appliedLook = ""
    private var tab = 0

    private val tabTags = listOf("tab_create", "tab_library", "tab_settings")

    /** Everything that needs a recreate() when it changes. */
    private fun lookKey(s: AppSettings) = "${s.themeMode}|${s.accent}|${s.font}|${s.amoled}"

    override fun onCreate(savedInstanceState: Bundle?) {
        graph = (application as SharprApp).graph
        val settings = graph.settings.state.value
        applyLook(settings)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        tab = savedInstanceState?.getInt("tab") ?: 0

        setupInsets()
        setupTabs(savedInstanceState == null)
        setupProgressOverlay()

        supportFragmentManager.addOnBackStackChangedListener { updateNavVisibility() }
        updateNavVisibility()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    graph.settings.state.collect { s ->
                        if (lookKey(s) != appliedLook) {
                            SharprApp.applyNightMode(s.themeMode)
                            recreate()
                        }
                    }
                }
                launch { graph.manager.state.collect(::onJobState) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab)
    }

    private fun applyLook(s: AppSettings) {
        setTheme(R.style.Theme_Sharpr)
        theme.applyStyle(
            when (s.accent) {
                Accent.Violet -> R.style.ThemeOverlay_Sharpr_Accent_Violet
                Accent.Orchid -> R.style.ThemeOverlay_Sharpr_Accent_Orchid
                Accent.Indigo -> R.style.ThemeOverlay_Sharpr_Accent_Indigo
                Accent.Lilac -> R.style.ThemeOverlay_Sharpr_Accent_Lilac
            }, true,
        )
        theme.applyStyle(
            when (s.font) {
                AppFont.Outfit -> R.style.ThemeOverlay_Sharpr_Font_Outfit
                AppFont.Jakarta -> R.style.ThemeOverlay_Sharpr_Font_Jakarta
                AppFont.Grotesk -> R.style.ThemeOverlay_Sharpr_Font_Grotesk
            }, true,
        )
        if (s.amoled) theme.applyStyle(R.style.ThemeOverlay_Sharpr_Amoled, true)
        appliedLook = lookKey(s)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.tabContainer.updatePadding(top = bars.top)
            b.overlayContainer.updatePadding(top = bars.top, bottom = bars.bottom)
            b.nav.updateLayoutParams<android.widget.FrameLayout.LayoutParams> { bottomMargin = bars.bottom + dpi(14) }
            insets
        }
    }

    private fun setupTabs(fresh: Boolean) {
        if (fresh) {
            supportFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.tab_container, CreateFragment(), tabTags[0])
                .add(R.id.tab_container, LibraryFragment(), tabTags[1])
                .add(R.id.tab_container, SettingsFragment(), tabTags[2])
                .commitNow()
        }
        b.nav.setItems(
            listOf(
                NavSpec(R.drawable.ic_sparkle, "Create"),
                NavSpec(R.drawable.ic_library, "Library"),
                NavSpec(R.drawable.ic_settings, "Settings"),
            ),
            tab,
        )
        b.nav.onSelect = { goTab(it) }
        showTab(tab)
    }

    private fun showTab(i: Int) {
        tab = i
        val tx = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        tabTags.forEachIndexed { idx, tag ->
            supportFragmentManager.findFragmentByTag(tag)?.let { f -> if (idx == i) tx.show(f) else tx.hide(f) }
        }
        tx.commitNowAllowingStateLoss()
    }

    private fun updateNavVisibility() {
        val overlay = supportFragmentManager.backStackEntryCount > 0
        b.nav.visibility = if (overlay) View.GONE else View.VISIBLE
    }

    // ---- Navigator ------------------------------------------------------------------------------

    override fun goTab(index: Int) {
        popOverlays()
        b.nav.select(index, false)
        showTab(index)
    }

    override fun openDetail(id: String) = pushOverlay(DetailFragment.newInstance(id), "detail")
    override fun openEdit(id: String) = pushOverlay(EditFragment.newInstance(id), "edit")

    private fun pushOverlay(f: Fragment, tag: String) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.overlay_enter, R.anim.hold, R.anim.hold, R.anim.overlay_exit)
            .add(R.id.overlay_container, f, tag)
            .addToBackStack(tag)
            .commit()
    }

    private fun popOverlays() {
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    // ---- Job progress overlay ---------------------------------------------------------------------

    private fun setupProgressOverlay() {
        b.progressOverlay.cancel.setOnClickListener { graph.manager.cancel() }
    }

    private fun onJobState(state: JobState) {
        val o = b.progressOverlay
        when (state) {
            is JobState.Idle -> o.overlayRoot.visibility = View.GONE
            is JobState.Running -> {
                o.overlayRoot.visibility = View.VISIBLE
                o.ring.progress = state.fraction
                o.percent.text = "${(state.fraction * 100).toInt()}%"
                o.stage.text = state.stage
                o.tiles.text = if (state.tilesTotal > 0) "Tile ${state.tilesDone} of ${state.tilesTotal}" else ""
                o.backend.visibility = if (state.backend != null) View.VISIBLE else View.GONE
                o.backend.text = state.backend
                o.fallbackNote.visibility = if (state.fellBack) View.VISIBLE else View.GONE
            }
            is JobState.Done -> {
                o.overlayRoot.visibility = View.GONE
                graph.manager.acknowledge()
                val d = graph.draft.value
                if (d != null && d.image.file.absolutePath == state.record.originalFile) graph.draft.value = null
                popOverlays()
                openDetail(state.record.id)
            }
            is JobState.Failed -> {
                o.overlayRoot.visibility = View.GONE
                graph.manager.acknowledge()
                Dialogs.message(this, "Couldn't upscale", state.message)
            }
        }
    }
}
