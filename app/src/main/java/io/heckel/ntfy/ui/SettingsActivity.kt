package io.heckel.ntfy.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.preference.Preference.OnPreferenceClickListener
import io.heckel.ntfy.BuildConfig
import io.heckel.ntfy.R
import io.heckel.ntfy.data.Repository
import io.heckel.ntfy.log.Log
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.formatBytes
import io.heckel.ntfy.util.formatDateShort
import io.heckel.ntfy.util.toPriorityString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SettingsActivity : AppCompatActivity() {
    private lateinit var fragment: SettingsFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        Log.d(TAG, "Create $this")

        if (savedInstanceState == null) {
            fragment = SettingsFragment(supportFragmentManager)
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_layout, fragment)
                .commit()
        }

        // Action bar
        title = getString(R.string.settings_title)

        // Show 'Back' button
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    class SettingsFragment(private val supportFragmentManager: FragmentManager) : PreferenceFragmentCompat() {
        private lateinit var repository: Repository
        private var autoDownloadSelection = AUTO_DOWNLOAD_SELECTION_NOT_SET

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.main_preferences, rootKey)

            // Dependencies (Fragments need a default constructor)
            repository = Repository.getInstance(requireActivity())
            autoDownloadSelection = repository.getAutoDownloadMaxSize() // Only used for <= Android P, due to permissions request

            // Important note: We do not use the default shared prefs to store settings. Every
            // preferenceDataStore is overridden to use the repository. This is convenient, because
            // everybody has access to the repository.

            // Notifications muted until (global)
            val mutedUntilPrefId = context?.getString(R.string.settings_notifications_muted_until_key) ?: return
            val mutedUntilSummary = { s: Long ->
                when (s) {
                    0L -> getString(R.string.settings_notifications_muted_until_enabled)
                    1L -> getString(R.string.settings_notifications_muted_until_disabled_forever)
                    else -> {
                        val formattedDate = formatDateShort(s)
                        getString(R.string.settings_notifications_muted_until_disabled_until, formattedDate)
                    }
                }
            }
            val mutedUntil: Preference? = findPreference(mutedUntilPrefId)
            mutedUntil?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            mutedUntil?.summary = mutedUntilSummary(repository.getGlobalMutedUntil())
            mutedUntil?.onPreferenceClickListener = OnPreferenceClickListener {
                if (repository.getGlobalMutedUntil() > 0) {
                    repository.setGlobalMutedUntil(0)
                    mutedUntil?.summary = mutedUntilSummary(0)
                } else {
                    val notificationFragment = NotificationFragment()
                    notificationFragment.settingsListener = object : NotificationFragment.NotificationSettingsListener {
                        override fun onNotificationMutedUntilChanged(mutedUntilTimestamp: Long) {
                            repository.setGlobalMutedUntil(mutedUntilTimestamp)
                            mutedUntil?.summary = mutedUntilSummary(mutedUntilTimestamp)
                        }
                    }
                    notificationFragment.show(supportFragmentManager, NotificationFragment.TAG)
                }
                true
            }

            // Minimum priority
            val minPriorityPrefId = context?.getString(R.string.settings_notifications_min_priority_key) ?: return
            val minPriority: ListPreference? = findPreference(minPriorityPrefId)
            minPriority?.value = repository.getMinPriority().toString()
            minPriority?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val minPriorityValue = value?.toIntOrNull() ?:return
                    repository.setMinPriority(minPriorityValue)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getMinPriority().toString()
                }
            }
            minPriority?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val minPriorityValue = pref.value.toIntOrNull() ?: 1 // 1/low means all priorities
                when (minPriorityValue) {
                    1 -> getString(R.string.settings_notifications_min_priority_summary_any)
                    5 -> getString(R.string.settings_notifications_min_priority_summary_max)
                    else -> {
                        val minPriorityString = toPriorityString(minPriorityValue)
                        getString(R.string.settings_notifications_min_priority_summary_x_or_higher, minPriorityValue, minPriorityString)
                    }
                }
            }

            // Auto download
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = repository.getAutoDownloadMaxSize().toString()
            autoDownload?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val maxSize = value?.toLongOrNull() ?:return
                    repository.setAutoDownloadMaxSize(maxSize)
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getAutoDownloadMaxSize().toString()
                }
            }
            autoDownload?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                val maxSize = pref.value.toLongOrNull() ?: repository.getAutoDownloadMaxSize()
                when (maxSize) {
                    Repository.AUTO_DOWNLOAD_NEVER -> getString(R.string.settings_notifications_auto_download_summary_never)
                    Repository.AUTO_DOWNLOAD_ALWAYS -> getString(R.string.settings_notifications_auto_download_summary_always)
                    else -> getString(R.string.settings_notifications_auto_download_summary_smaller_than_x, formatBytes(maxSize, decimals = 0))
                }
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                autoDownload?.setOnPreferenceChangeListener { _, v ->
                    if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                        ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD)
                        autoDownloadSelection = v.toString().toLongOrNull() ?: repository.getAutoDownloadMaxSize()
                        false // If permission is granted, auto-download will be enabled in onRequestPermissionsResult()
                    } else {
                        true
                    }
                }
            }

            // UnifiedPush enabled
            val upEnabledPrefId = context?.getString(R.string.settings_unified_push_enabled_key) ?: return
            val upEnabled: SwitchPreference? = findPreference(upEnabledPrefId)
            upEnabled?.isChecked = repository.getUnifiedPushEnabled()
            upEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setUnifiedPushEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getUnifiedPushEnabled()
                }
            }
            upEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_unified_push_enabled_summary_on)
                } else {
                    getString(R.string.settings_unified_push_enabled_summary_off)
                }
            }

            // UnifiedPush Base URL
            val appBaseUrl = context?.getString(R.string.app_base_url) ?: return
            val upBaseUrlPrefId = context?.getString(R.string.settings_unified_push_base_url_key) ?: return
            val upBaseUrl: EditTextPreference? = findPreference(upBaseUrlPrefId)
            upBaseUrl?.text = repository.getUnifiedPushBaseUrl() ?: ""
            upBaseUrl?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String, value: String?) {
                    val baseUrl = value ?: return
                    repository.setUnifiedPushBaseUrl(baseUrl)
                }
                override fun getString(key: String, defValue: String?): String? {
                    return repository.getUnifiedPushBaseUrl()
                }
            }
            upBaseUrl?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                if (TextUtils.isEmpty(pref.text)) {
                    getString(R.string.settings_unified_push_base_url_default_summary, appBaseUrl)
                } else {
                    pref.text
                }
            }

            // Broadcast enabled
            val broadcastEnabledPrefId = context?.getString(R.string.settings_advanced_broadcast_key) ?: return
            val broadcastEnabled: SwitchPreference? = findPreference(broadcastEnabledPrefId)
            broadcastEnabled?.isChecked = repository.getBroadcastEnabled()
            broadcastEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setBroadcastEnabled(value)
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getBroadcastEnabled()
                }
            }
            broadcastEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_broadcast_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_broadcast_summary_disabled)
                }
            }

            // Copy logs
            val copyLogsPrefId = context?.getString(R.string.settings_advanced_copy_logs_key) ?: return
            val copyLogs: Preference? = findPreference(copyLogsPrefId)
            copyLogs?.isVisible = Log.getRecord()
            copyLogs?.preferenceDataStore = object : PreferenceDataStore() { } // Dummy store to protect from accidentally overwriting
            copyLogs?.onPreferenceClickListener = OnPreferenceClickListener {
                copyLogsToClipboard()
                true
            }

            // Record logs
            val recordLogsPrefId = context?.getString(R.string.settings_advanced_record_logs_key) ?: return
            val recordLogsEnabled: SwitchPreference? = findPreference(recordLogsPrefId)
            recordLogsEnabled?.isChecked = Log.getRecord()
            recordLogsEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setRecordLogsEnabled(value)
                    Log.setRecord(value)
                    copyLogs?.isVisible = value
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return Log.getRecord()
                }
            }
            recordLogsEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_record_logs_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_record_logs_summary_disabled)
                }
            }
            recordLogsEnabled?.setOnPreferenceChangeListener { _, v ->
                val newValue = v as Boolean
                if (!newValue) {
                    val dialog = AlertDialog.Builder(activity)
                        .setMessage(R.string.settings_advanced_record_logs_delete_dialog_message)
                        .setPositiveButton(R.string.settings_advanced_record_logs_delete_dialog_button_delete) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                Log.deleteAll()
                            }                        }
                        .setNegativeButton(R.string.settings_advanced_record_logs_delete_dialog_button_keep) { _, _ ->
                            // Do nothing
                        }
                        .create()
                    dialog
                        .setOnShowListener {
                            dialog
                                .getButton(AlertDialog.BUTTON_POSITIVE)
                                .setTextColor(ContextCompat.getColor(requireContext(), R.color.primaryDangerButtonColor))
                        }
                        dialog.show()
                }
                true
            }

            // Connection protocol
            val connectionProtocolPrefId = context?.getString(R.string.settings_advanced_connection_protocol_key) ?: return
            val connectionProtocol: ListPreference? = findPreference(connectionProtocolPrefId)
            connectionProtocol?.value = repository.getConnectionProtocol()
            connectionProtocol?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putString(key: String?, value: String?) {
                    val proto = value ?: repository.getConnectionProtocol()
                    repository.setConnectionProtocol(proto)
                    restartService()
                }
                override fun getString(key: String?, defValue: String?): String {
                    return repository.getConnectionProtocol()
                }
            }
            connectionProtocol?.summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
                when (pref.value) {
                    Repository.CONNECTION_PROTOCOL_WS -> getString(R.string.settings_advanced_connection_protocol_summary_ws)
                    else -> getString(R.string.settings_advanced_connection_protocol_summary_jsonhttp)
                }
            }

            // Permanent wakelock enabled
            val wakelockEnabledPrefId = context?.getString(R.string.settings_advanced_wakelock_key) ?: return
            val wakelockEnabled: SwitchPreference? = findPreference(wakelockEnabledPrefId)
            wakelockEnabled?.isChecked = repository.getWakelockEnabled()
            wakelockEnabled?.preferenceDataStore = object : PreferenceDataStore() {
                override fun putBoolean(key: String?, value: Boolean) {
                    repository.setWakelockEnabled(value)
                    restartService()
                }
                override fun getBoolean(key: String?, defValue: Boolean): Boolean {
                    return repository.getWakelockEnabled()
                }
            }
            wakelockEnabled?.summaryProvider = Preference.SummaryProvider<SwitchPreference> { pref ->
                if (pref.isChecked) {
                    getString(R.string.settings_advanced_wakelock_summary_enabled)
                } else {
                    getString(R.string.settings_advanced_wakelock_summary_disabled)
                }
            }

            // Version
            val versionPrefId = context?.getString(R.string.settings_about_version_key) ?: return
            val versionPref: Preference? = findPreference(versionPrefId)
            val version = getString(R.string.settings_about_version_format, BuildConfig.VERSION_NAME, BuildConfig.FLAVOR)
            versionPref?.summary = version
            versionPref?.onPreferenceClickListener = OnPreferenceClickListener {
                val context = context ?: return@OnPreferenceClickListener false
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ntfy version", version)
                clipboard.setPrimaryClip(clip)
                Toast
                    .makeText(context, getString(R.string.settings_about_version_copied_to_clipboard_message), Toast.LENGTH_LONG)
                    .show()
                true
            }
        }

        fun setAutoDownload() {
            val autoDownloadSelectionCopy = autoDownloadSelection
            if (autoDownloadSelectionCopy == AUTO_DOWNLOAD_SELECTION_NOT_SET) return
            val autoDownloadPrefId = context?.getString(R.string.settings_notifications_auto_download_key) ?: return
            val autoDownload: ListPreference? = findPreference(autoDownloadPrefId)
            autoDownload?.value = autoDownloadSelectionCopy.toString()
            repository.setAutoDownloadMaxSize(autoDownloadSelectionCopy)
        }

        private fun restartService() {
            val context = this@SettingsFragment.context
            Intent(context, SubscriberService::class.java).also { intent ->
                context?.stopService(intent) // Service will auto-restart
            }
        }

        private fun copyLogsToClipboard() {
            lifecycleScope.launch(Dispatchers.IO) {
                val log = Log.getAll().joinToString(separator = "\n") { e ->
                    val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date(e.timestamp))
                    val level = when (e.level) {
                        android.util.Log.DEBUG -> "D"
                        android.util.Log.INFO -> "I"
                        android.util.Log.WARN -> "W"
                        android.util.Log.ERROR -> "E"
                        else -> "?"
                    }
                    val tag = e.tag.format("%-23s")
                    val prefix = "${e.timestamp} $date $level $tag"
                    val message = if (e.exception != null) {
                        "${e.message}\nException:\n${e.exception}"
                    } else {
                        e.message
                    }
                    "$prefix $message"
                }
                val context = context ?: return@launch
                requireActivity().runOnUiThread {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ntfy logs", log)
                    clipboard.setPrimaryClip(clip)
                    Toast
                        .makeText(context, getString(R.string.settings_advanced_copy_logs_copied), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setAutoDownload()
            }
        }
    }

    private fun setAutoDownload() {
        if (!this::fragment.isInitialized) return
        fragment.setAutoDownload()
    }

    companion object {
        private const val TAG = "NtfySettingsActivity"
        private const val REQUEST_CODE_WRITE_EXTERNAL_STORAGE_PERMISSION_FOR_AUTO_DOWNLOAD = 2586
        private const val AUTO_DOWNLOAD_SELECTION_NOT_SET = -99L
    }
}
