package com.imperialreader.ui.more

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.imperialreader.R
import com.imperialreader.data.backup.BackupManager
import com.imperialreader.data.backup.BackupRestoreWorker
import com.imperialreader.data.storage.LocalScanner
import com.imperialreader.data.update.AppUpdateManager
import com.imperialreader.ui.details.MangaDetailsActivity
import com.imperialreader.util.app
import com.imperialreader.util.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.io.File
import java.util.Date
import java.util.Locale

class MoreFragment : Fragment(R.layout.fragment_more) {
    private lateinit var storageSub: TextView
    private lateinit var scanSub: TextView
    private lateinit var autoBackupSub: TextView
    private lateinit var queueSummary: TextView
    private lateinit var queuePauseAll: Button
    private lateinit var queueEmpty: TextView
    private lateinit var queueRecycler: RecyclerView
    private lateinit var queueAdapter: DownloadQueueAdapter
    private lateinit var appUpdateSub: TextView
    private var queueItems: List<com.imperialreader.data.download.DownloadQueueItem> = emptyList()
    private var pendingUpdateApk: File? = null
    private var updateCheckInProgress = false

    private val allowUnknownSources = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (requireContext().packageManager.canRequestPackageInstalls()) {
            installUpdateApk(apk)
        } else {
            pendingUpdateApk = null
            requireContext().toast("Allow Imperial Reader to install apps, then check for updates again")
        }
    }

    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val n = BackupManager(requireContext()).export(uri)
                    requireContext().toast("Backed up $n library entries")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    requireContext().toast("Backup failed: ${e.message}")
                }
            }
        }
    }

    private val restoreBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                BackupRestoreWorker.enqueue(requireContext(), uri)
                requireContext().toast("Backup restore started. Progress is available in notifications.")
            } catch (e: Exception) {
                requireContext().toast("Could not start backup restore: ${e.message}")
            }
        }
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val ok = requireContext().app.storage.setRoot(uri)
            requireContext().toast(
                if (ok) "Folder ready: downloads/, local/ and autobackup/ created"
                else "Could not create the folders — pick a different location"
            )
            refreshStorageText()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val prefs = requireContext().app.prefs

        storageSub = view.findViewById(R.id.storageSub)
        scanSub = view.findViewById(R.id.scanSub)
        autoBackupSub = view.findViewById(R.id.autoBackupSub)
        appUpdateSub = view.findViewById(R.id.appUpdateSub)
        queueSummary = view.findViewById(R.id.queueSummary)
        queuePauseAll = view.findViewById(R.id.queuePauseAll)
        queueEmpty = view.findViewById(R.id.queueEmpty)
        queueRecycler = view.findViewById(R.id.downloadQueue)
        queueAdapter = DownloadQueueAdapter(
            onClick = { item ->
                item.mangaId?.let { startActivity(MangaDetailsActivity.intent(requireContext(), it)) }
            },
            onActions = ::showDownloadActions,
            onPauseAllForEntry = { mangaId, allPaused ->
                if (allPaused) requireContext().app.downloads.resumeManga(mangaId)
                else requireContext().app.downloads.pauseManga(mangaId)
            }
        )
        queueRecycler.layoutManager = LinearLayoutManager(requireContext())
        queueRecycler.adapter = queueAdapter
        queuePauseAll.setOnClickListener {
            val chapters = queueItems.filter { it.chapterId != null }
            if (chapters.isNotEmpty() && chapters.all { it.paused }) {
                requireContext().app.downloads.resumeAll()
            } else {
                requireContext().app.downloads.pauseAll()
            }
        }
        refreshAutomaticBackupText()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireContext().app.downloads.queueItems.collect { items ->
                    queueItems = items
                    val active = items.count { it.downloading && !it.paused }
                    val paused = items.count { it.paused }
                    val chapters = items.filter { it.chapterId != null }
                    val allPaused = chapters.isNotEmpty() && chapters.all { it.paused }
                    queueSummary.text = when {
                        items.isEmpty() -> "Nothing is queued"
                        active == 0 && paused > 0 -> "$paused download(s) paused"
                        active == 0 -> "${items.size} chapter(s) waiting"
                        items.size == 1 -> "1 chapter downloading"
                        else -> "$active downloading · ${items.size - active} queued"
                    }
                    queuePauseAll.visibility = if (chapters.isEmpty()) View.GONE else View.VISIBLE
                    queuePauseAll.text = if (allPaused) "Resume all" else "Pause all"
                    queueEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    queueRecycler.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                    queueAdapter.submitQueue(items)
                }
            }
        }

        val downloadedOnly = view.findViewById<MaterialSwitch>(R.id.switchDownloadedOnly)
        val incognito = view.findViewById<MaterialSwitch>(R.id.switchIncognito)
        downloadedOnly.isChecked = prefs.downloadedOnly
        incognito.isChecked = prefs.incognito
        downloadedOnly.setOnCheckedChangeListener { _, checked -> prefs.downloadedOnly = checked }
        incognito.setOnCheckedChangeListener { _, checked -> prefs.incognito = checked }

        view.findViewById<View>(R.id.rowStorage).setOnClickListener { pickFolder.launch(null) }
        view.findViewById<View>(R.id.rowScan).setOnClickListener { scanLocal() }
        view.findViewById<View>(R.id.rowAutoBackup).setOnClickListener { createAutomaticBackupNow() }
        view.findViewById<View>(R.id.rowSettings).setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.rowAppUpdate).setOnClickListener { checkForAppUpdate() }
        view.findViewById<View>(R.id.rowBackup).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Backup and restore")
                .setItems(arrayOf("Create backup", "Restore backup")) { _, which ->
                    if (which == 0) {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
                        createBackup.launch("imperial_reader_backup_$stamp.json")
                    } else {
                        restoreBackup.launch(arrayOf("application/json", "text/plain", "*/*"))
                    }
                }
                .show()
        }
        view.findViewById<View>(R.id.rowAbout).setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Imperial Reader")
                .setMessage("A reader app that combines novels and manga/Manhwa/manhua")
                .setPositiveButton("OK", null)
                .show()
        }
        refreshStorageText()
    }

    private fun checkForAppUpdate() {
        if (updateCheckInProgress) return
        updateCheckInProgress = true
        appUpdateSub.text = "Checking GitHub release V9 for the latest APK…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val apk = AppUpdateManager(requireContext()).downloadLatestApk()
                pendingUpdateApk = apk
                appUpdateSub.text = "Latest APK downloaded; opening installer"
                if (requireContext().packageManager.canRequestPackageInstalls()) {
                    installUpdateApk(apk)
                } else {
                    appUpdateSub.text = "Allow app installs to continue the update"
                    allowUnknownSources.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${requireContext().packageName}")
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appUpdateSub.text = "Download failed; tap to try again"
                requireContext().toast("App update failed: ${e.message}")
            } finally {
                updateCheckInProgress = false
            }
        }
    }

    private fun installUpdateApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                apk
            )
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            pendingUpdateApk = null
            appUpdateSub.text = "Could not open the installer; tap to try again"
            requireContext().toast("Could not start APK installer: ${e.message}")
        }
    }

    private fun showDownloadActions(item: com.imperialreader.data.download.DownloadQueueItem) {
        val mangaId = item.mangaId ?: return
        val chapterId = item.chapterId ?: return
        val actions = listOf(
            if (item.paused) "Resume" else "Pause",
            "Cancel chapter",
            "Cancel all downloads from this entry"
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(item.chapterName)
            .setItems(actions.toTypedArray()) { _, which ->
                when (which) {
                    0 -> if (item.paused) requireContext().app.downloads.resumeChapter(chapterId)
                    else requireContext().app.downloads.pauseChapter(chapterId)
                    1 -> requireContext().app.downloads.cancelChapter(chapterId)
                    2 -> MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Cancel all downloads from ${item.mangaTitle}?")
                        .setMessage("All queued and active chapter downloads for this entry will be canceled.")
                        .setNegativeButton("Keep downloads", null)
                        .setPositiveButton("Cancel all") { _, _ ->
                            requireContext().app.downloads.cancelAllForManga(mangaId)
                        }
                        .show()
                }
            }
            .show()
    }

    private fun refreshStorageText() {
        storageSub.text = requireContext().app.storage.displayPath()
        refreshAutomaticBackupText()
    }

    override fun onResume() {
        super.onResume()
        if (::autoBackupSub.isInitialized) refreshAutomaticBackupText()
        BackupRestoreWorker.consumeFallbackStatus(requireContext())?.let { status ->
            requireContext().toast(status)
        }
    }

    private fun refreshAutomaticBackupText() {
        if (!::autoBackupSub.isInitialized) return
        val prefs = requireContext().app.prefs
        val last = prefs.automaticBackupAt
        val status = prefs.automaticBackupStatus
        val time = if (last > 0L) {
            SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(last))
        } else {
            "not run yet"
        }
        autoBackupSub.text = buildString {
            append("Every 12 hours · keeps 5 · Last: ").append(time)
            if (!status.isNullOrBlank()) append(" · ").append(status)
        }
    }

    private fun createAutomaticBackupNow() {
        val context = requireContext().applicationContext
        autoBackupSub.text = "Creating backup…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val count = BackupManager(context).createAutomaticBackup()
                context.app.prefs.automaticBackupAt = System.currentTimeMillis()
                context.app.prefs.automaticBackupStatus = "Saved $count library entries"
                refreshAutomaticBackupText()
                context.toast("Automatic backup saved ($count entries)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                context.app.prefs.automaticBackupStatus = "Backup failed: ${e.message.orEmpty().take(100)}"
                refreshAutomaticBackupText()
                context.toast("Automatic backup failed: ${e.message}")
            }
        }
    }

    private fun scanLocal() {
        viewLifecycleOwner.lifecycleScope.launch {
            scanSub.text = "Scanning…"
            try {
                val n = LocalScanner(requireContext()).scan()
                scanSub.text = "Imported $n local entr${if (n == 1) "y" else "ies"}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scanSub.text = e.message ?: "Scan failed"
            }
        }
    }
}
