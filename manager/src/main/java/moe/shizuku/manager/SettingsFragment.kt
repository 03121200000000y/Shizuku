package moe.shizuku.manager

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.observe
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.app.ThemeHelper.KEY_BLACK_NIGHT_THEME
import moe.shizuku.manager.utils.BuildUtils
import moe.shizuku.manager.viewmodel.AppsViewModel
import moe.shizuku.manager.viewmodel.SharedViewModelProviders
import moe.shizuku.manager.viewmodel.Status
import moe.shizuku.preference.*
import rikka.core.util.ResourceUtils
import rikka.html.text.HtmlCompat
import rikka.material.app.DayNightDelegate
import rikka.material.app.LocaleDelegate
import rikka.material.widget.*
import rikka.recyclerview.RecyclerViewHelper
import java.util.*
import moe.shizuku.manager.ShizukuManagerSettings.KEEP_SU_CONTEXT as KEY_KEEP_SU_CONTEXT
import moe.shizuku.manager.ShizukuManagerSettings.LANGUAGE as KEY_LANGUAGE
import moe.shizuku.manager.ShizukuManagerSettings.NIGHT_MODE as KEY_NIGHT_MODE
import moe.shizuku.manager.ShizukuManagerSettings.NO_V2 as KEY_NO_V2

class SettingsFragment : PreferenceFragment() {

    companion object {
        init {
            SimpleMenuPreference.setLightFixEnabled(true)
        }
    }

    private lateinit var languagePreference: ListPreference
    private lateinit var nightModePreference: Preference
    private lateinit var blackNightThemePreference: SwitchPreference
    private lateinit var noV2Preference: SwitchPreference
    private lateinit var keepSuContextPreference: SwitchPreference
    private lateinit var startupPreference: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.setStorageDeviceProtected()
        preferenceManager.sharedPreferencesName = ShizukuManagerSettings.NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        languagePreference = findPreference(KEY_LANGUAGE) as ListPreference
        nightModePreference = findPreference(KEY_NIGHT_MODE)
        blackNightThemePreference = findPreference(KEY_BLACK_NIGHT_THEME) as SwitchPreference
        noV2Preference = findPreference(KEY_NO_V2) as SwitchPreference
        keepSuContextPreference = findPreference(KEY_KEEP_SU_CONTEXT) as SwitchPreference
        startupPreference = findPreference("startup") as PreferenceCategory

        noV2Preference.isVisible = !BuildUtils.atLeastR()
        keepSuContextPreference.isVisible = false
        startupPreference.isVisible = !BuildUtils.atLeastR()

        val viewModel = SharedViewModelProviders.of(this).get(AppsViewModel::class.java)
        viewModel.packages.observe(this) {
            if (it?.status == Status.SUCCESS) {
                updateData(it.data)
            }
        }
        if (viewModel.packages.value == null) {
            viewModel.load()
        }

        languagePreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, newValue: Any ->
            if (newValue is String) {
                val locale: Locale = if ("SYSTEM" == newValue) {
                    LocaleDelegate.systemLocale
                } else {
                    Locale.forLanguageTag(newValue)
                }
                LocaleDelegate.defaultLocale = locale
                activity?.recreate()
            }
            true
        }

        val tag = languagePreference.value
        val index = listOf(*languagePreference.entryValues).indexOf(tag)
        val localeName: MutableList<String> = ArrayList()
        val localeNameUser: MutableList<String> = ArrayList()
        val userLocale = ShizukuManagerSettings.getLocale()
        for (i in 1 until languagePreference.entries.size) {
            val locale = Locale.forLanguageTag(languagePreference.entries[i].toString())
            localeName.add(if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(locale) else locale.getDisplayName(locale))
            localeNameUser.add(if (!TextUtils.isEmpty(locale.script)) locale.getDisplayScript(userLocale) else locale.getDisplayName(userLocale))
        }

        for (i in 1 until languagePreference.entries.size) {
            if (index != i) {
                languagePreference.entries[i] = HtmlCompat.fromHtml(String.format("%s - %s",
                        localeName[i - 1],
                        localeNameUser[i - 1]
                ))
            } else {
                languagePreference.entries[i] = localeNameUser[i - 1]
            }
        }

        if (TextUtils.isEmpty(tag) || "SYSTEM" == tag) {
            languagePreference.summary = getString(R.string.follow_system)
        } else if (index != -1) {
            val name = localeNameUser[index - 1]
            languagePreference.summary = name
        }
        nightModePreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, value: Any? ->
            if (value is Int) {
                if (ShizukuManagerSettings.getNightMode() != value) {
                    DayNightDelegate.setDefaultNightMode(value)
                    activity?.recreate()
                }
            }
            true
        }
        blackNightThemePreference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _: Preference?, _: Any? ->
            if (ResourceUtils.isNightMode(requireContext().resources.configuration)) activity?.recreate()
            true
        }
    }

    override fun onCreateItemDecoration(): DividerDecoration? {
        return CategoryDivideDividerDecoration()
    }

    override fun onCreateRecyclerView(inflater: LayoutInflater, parent: ViewGroup, savedInstanceState: Bundle?): RecyclerView {
        val recyclerView = super.onCreateRecyclerView(inflater, parent, savedInstanceState) as BorderRecyclerView
        RecyclerViewHelper.fixOverScroll(recyclerView)
        val padding = (8 * recyclerView.context.resources.displayMetrics.density).toInt()
        recyclerView.setPaddingRelative(recyclerView.paddingStart, 0, recyclerView.paddingEnd, padding)
        val lp = recyclerView.layoutParams
        if (lp is FrameLayout.LayoutParams) {
            lp.rightMargin = recyclerView.context.resources.getDimension(R.dimen.rd_activity_horizontal_margin).toInt()
            lp.leftMargin = lp.rightMargin
        }
        val verticalPadding = resources.getDimension(R.dimen.list_vertical_padding).toInt()
        recyclerView.setInitialPadding(
                recyclerView.initialPaddingLeft,
                recyclerView.initialPaddingTop,
                recyclerView.initialPaddingRight,
                recyclerView.initialPaddingBottom + verticalPadding
        )
        recyclerView.borderViewDelegate.borderVisibilityChangedListener = BorderView.OnBorderVisibilityChangedListener { top: Boolean, _: Boolean, _: Boolean, _: Boolean -> (activity as SettingsActivity?)?.appBar?.setRaised(!top) }
        return recyclerView
    }

    private fun updateData(data: List<PackageInfo>?) {
        var count = 0
        if (data != null) {
            for (pi in data) {
                val ai = pi.applicationInfo
                if (ai.metaData == null || !ai.metaData.getBoolean("moe.shizuku.client.V3_SUPPORT")) {
                    count++
                }
            }
        }
        noV2Preference.summary = requireContext().resources.getQuantityString(R.plurals.start_legacy_service_summary, count, count)
    }
}