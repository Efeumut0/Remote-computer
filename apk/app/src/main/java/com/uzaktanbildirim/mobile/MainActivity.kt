package com.uzaktanbildirim.mobile

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.provider.Settings
import android.provider.OpenableColumns
import android.util.Base64
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.android.material.tabs.TabLayout
import com.uzaktanbildirim.mobile.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.DateFormat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

private const val MAX_R2_UPLOAD_BYTES = 25 * 1024 * 1024
private const val MAX_LEGACY_FILE_BYTES = 256 * 1024
private const val TOUCHPAD_SEND_INTERVAL_MS = 35L
private const val TOUCHPAD_MOVE_SCALE = 1.25f
private const val LIVE_PREVIEW_INTERVAL_MS = 600L
private const val CLIPBOARD_POLL_INTERVAL_MS = 1500L
private const val DEFAULT_NOTIFICATION_DISPLAY_LIMIT = 5
private const val MIN_NOTIFICATION_DISPLAY_LIMIT = 5
private const val MAX_NOTIFICATION_DISPLAY_LIMIT = 50
private const val PROCESS_ICON_SIZE_DP = 24
private const val SHORTCUT_ICON_SIZE_DP = 38
private const val SHORTCUT_TILE_HEIGHT_DP = 118
private const val SHORTCUT_TILE_MARGIN_DP = 6
private const val EXTRA_SYSTEM_INSET_DP = 18
private const val MAX_PREVIEW_BYTES = 8 * 1024 * 1024
private const val BACKGROUND_PERMISSION_RECHECK_MS = 3L * 24 * 60 * 60 * 1000
private const val TAB_INDEX_SHORTCUTS = 1
private const val TAB_INDEX_FILES = 2
private const val TAB_INDEX_NOTIFICATIONS = 4
private const val TAB_INDEX_SETTINGS = 5
private const val WORKER_R2_STATE_UNKNOWN = -1
private const val WORKER_R2_STATE_LEGACY = 0
private const val WORKER_R2_STATE_SUPPORTED = 1
private const val DEFAULT_SHORTCUT_ACCENT_ID = "violet"
private const val LIVE_PREVIEW_MODE_ORIGINAL = "original"
private const val LIVE_PREVIEW_MODE_HD_1080 = "hd_1080"

private data class LivePreviewProfile(
    val modeId: String,
    val label: String,
    val quality: Int,
    val maxWidth: Int,
    val maxHeight: Int,
)

private val LIVE_PREVIEW_PROFILE_ORIGINAL =
    LivePreviewProfile(
        modeId = LIVE_PREVIEW_MODE_ORIGINAL,
        label = "Orijinal",
        quality = 30,
        maxWidth = 0,
        maxHeight = 0,
    )

private val LIVE_PREVIEW_PROFILE_HD_1080 =
    LivePreviewProfile(
        modeId = LIVE_PREVIEW_MODE_HD_1080,
        label = "1080p",
        quality = 34,
        maxWidth = 1920,
        maxHeight = 1080,
    )

private fun resolveLivePreviewProfile(modeId: String?): LivePreviewProfile =
    when (modeId?.trim()?.lowercase()) {
        LIVE_PREVIEW_MODE_HD_1080 -> LIVE_PREVIEW_PROFILE_HD_1080
        else -> LIVE_PREVIEW_PROFILE_ORIGINAL
    }

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: DeviceStore
    private lateinit var clipboardManager: ClipboardManager
    private val api = WorkerApi()
    private val executor = Executors.newSingleThreadExecutor()
    private val inputExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentRemotePath = ""
    private var currentRemoteParentPath: String? = null
    private var selectedRemoteFilePath: String? = null
    private var selectedRemoteEntry: RemoteEntry? = null
    private val selectedRemoteEntries = linkedMapOf<String, RemoteEntry>()
    private var lastRemoteEntries: List<RemoteEntry> = emptyList()
    private var selectedProcessName: String? = null
    private var selectedProcessId: Int? = null
    private var pendingDownloadBytes: ByteArray? = null
    private var pendingDownloadObjectKey: String? = null
    private var pendingDownloadFileName = "download.bin"
    private var lastScreenshotBytes: ByteArray? = null
    private var lastScreenshotFileName = "pc-screenshot.jpg"
    private var isDragModeEnabled = false
    private var touchpadLastX = 0f
    private var touchpadLastY = 0f
    private var touchpadLastSentAt = 0L
    private var isTouchpadDragging = false
    private var touchpadPendingDx = 0
    private var touchpadPendingDy = 0
    private var isTouchpadCommandInFlight = false
    private var pendingTouchpadRelease = false
    private var availablePcs: List<RemotePcSummary> = emptyList()
    private var isLivePreviewRunning = false
    private var isLivePreviewInFlight = false
    private var activeLivePreviewProfile = LIVE_PREVIEW_PROFILE_ORIGINAL
    private var isActivityResumed = false
    private var suppressClipboardCallback = false
    private var suppressClipboardSwitchCallback = false
    private var lastLocalClipboardSignature = ""
    private var unreadNotificationCount = 0
    private var currentSectionTabIndex = 0
    private var workerSupportsR2: Boolean? = null
    private var activeShortcutId: String? = null
    private var isShortcutReorderMode = false
    private var draggingShortcutId: String? = null
    private var shortcutDropHandled = false
    private var lastRenderedNotifications: List<RemoteNotificationItem> = emptyList()
    private val shortcutItems = mutableListOf<ShortcutItem>()
    private val shortcutTypeOptions = listOf(
        ShortcutTypeOption(
            id = "application",
            label = "Uygulama / kisayol",
            targetHint = "Ornek: chrome veya C:\\Program Files\\App\\app.exe",
            helperText = "EXE, LNK, BAT, CMD, COM veya chrome / opera / spotify / discord gibi kisayollar icin kullan.",
            supportsArguments = true,
            pickerMode = ShortcutPickerMode.APPLICATION,
        ),
        ShortcutTypeOption(
            id = "folder",
            label = "Klasor",
            targetHint = "Ornek: C:\\Users\\Efe\\Desktop",
            helperText = "Tiklaninca secilen klasor Windows Gezgini ile acilir.",
            supportsArguments = false,
            pickerMode = ShortcutPickerMode.FOLDER,
        ),
        ShortcutTypeOption(
            id = "url",
            label = "Link / URL",
            targetHint = "Ornek: https://example.com veya discord://",
            helperText = "HTTP linkleri varsayilan tarayicida, ozel protokoller destekleyen uygulamada acilir.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "cmd",
            label = "CMD komutu",
            targetHint = "Ornek: ipconfig /all",
            helperText = "Komut Istemi penceresi acar ve yazdigin komutu /K ile calistirir.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "powershell",
            label = "PowerShell komutu",
            targetHint = "Ornek: Get-Process | Select-Object -First 10",
            helperText = "PowerShell penceresi acar ve komutu -NoExit ile calistirir.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "run",
            label = "Calistir komutu",
            targetHint = "Ornek: shell:startup, control veya ms-settings:display",
            helperText = "Windows Calistir kutusuna yazilabilecek shell ve sistem komutlari icin kullan.",
            supportsArguments = true,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "hotkey",
            label = "Tus kombinasyonu",
            targetHint = "",
            helperText = "Secili PC'de tek vurusluk klavye kombinasyonu calistirir. Ctrl+Alt+Delete gibi guvenli dikkat kombinasyonlari desteklenmez.",
            supportsArguments = false,
            pickerMode = null,
        ),
    )
    private val shortcutAccentOptions = listOf(
        ShortcutAccentOption("violet", "Mor", R.color.shortcut_accent_violet_fill, R.color.shortcut_accent_violet_stroke),
        ShortcutAccentOption("aqua", "Aqua", R.color.shortcut_accent_aqua_fill, R.color.shortcut_accent_aqua_stroke),
        ShortcutAccentOption("emerald", "Zumrut", R.color.shortcut_accent_emerald_fill, R.color.shortcut_accent_emerald_stroke),
        ShortcutAccentOption("coral", "Coral", R.color.shortcut_accent_coral_fill, R.color.shortcut_accent_coral_stroke),
        ShortcutAccentOption("amber", "Amber", R.color.shortcut_accent_amber_fill, R.color.shortcut_accent_amber_stroke),
        ShortcutAccentOption("slate", "Slate", R.color.shortcut_accent_slate_fill, R.color.shortcut_accent_slate_stroke),
    )
    private val hotkeyModifierOptions = listOf(
        HotkeyKeyOption("LCTRL", "Left Ctrl", isModifier = true),
        HotkeyKeyOption("RCTRL", "Right Ctrl", isModifier = true),
        HotkeyKeyOption("LSHIFT", "Left Shift", isModifier = true),
        HotkeyKeyOption("RSHIFT", "Right Shift", isModifier = true),
        HotkeyKeyOption("LALT", "Left Alt", isModifier = true),
        HotkeyKeyOption("RALT", "Right Alt", isModifier = true),
        HotkeyKeyOption("LWIN", "Left Win", isModifier = true),
        HotkeyKeyOption("RWIN", "Right Win", isModifier = true),
    )
    private val hotkeyPrimaryOptions = buildList {
        ('A'..'Z').forEach { add(HotkeyKeyOption(it.toString(), it.toString())) }
        ('0'..'9').forEach { add(HotkeyKeyOption("DIGIT_$it", it.toString())) }
        (1..24).forEach { add(HotkeyKeyOption("F$it", "F$it")) }
        addAll(
            listOf(
                HotkeyKeyOption("SPACE", "Space"),
                HotkeyKeyOption("TAB", "Tab"),
                HotkeyKeyOption("ENTER", "Enter"),
                HotkeyKeyOption("ESC", "Esc"),
                HotkeyKeyOption("BACKSPACE", "Backspace"),
                HotkeyKeyOption("DELETE", "Delete"),
                HotkeyKeyOption("INSERT", "Insert"),
                HotkeyKeyOption("HOME", "Home"),
                HotkeyKeyOption("END", "End"),
                HotkeyKeyOption("PAGE_UP", "Page Up"),
                HotkeyKeyOption("PAGE_DOWN", "Page Down"),
                HotkeyKeyOption("ARROW_UP", "Arrow Up"),
                HotkeyKeyOption("ARROW_DOWN", "Arrow Down"),
                HotkeyKeyOption("ARROW_LEFT", "Arrow Left"),
                HotkeyKeyOption("ARROW_RIGHT", "Arrow Right"),
                HotkeyKeyOption("PRINT_SCREEN", "Print Screen"),
                HotkeyKeyOption("PAUSE", "Pause"),
                HotkeyKeyOption("CAPS_LOCK", "Caps Lock"),
                HotkeyKeyOption("NUM_LOCK", "Num Lock"),
                HotkeyKeyOption("SCROLL_LOCK", "Scroll Lock"),
                HotkeyKeyOption("APPS", "Apps / Menu"),
                HotkeyKeyOption("MINUS", "-"),
                HotkeyKeyOption("EQUALS", "="),
                HotkeyKeyOption("LBRACKET", "["),
                HotkeyKeyOption("RBRACKET", "]"),
                HotkeyKeyOption("BACKSLASH", "\\"),
                HotkeyKeyOption("SEMICOLON", ";"),
                HotkeyKeyOption("QUOTE", "'"),
                HotkeyKeyOption("BACKQUOTE", "`"),
                HotkeyKeyOption("COMMA", ","),
                HotkeyKeyOption("PERIOD", "."),
                HotkeyKeyOption("SLASH", "/"),
                HotkeyKeyOption("NUMPAD_0", "Numpad 0"),
                HotkeyKeyOption("NUMPAD_1", "Numpad 1"),
                HotkeyKeyOption("NUMPAD_2", "Numpad 2"),
                HotkeyKeyOption("NUMPAD_3", "Numpad 3"),
                HotkeyKeyOption("NUMPAD_4", "Numpad 4"),
                HotkeyKeyOption("NUMPAD_5", "Numpad 5"),
                HotkeyKeyOption("NUMPAD_6", "Numpad 6"),
                HotkeyKeyOption("NUMPAD_7", "Numpad 7"),
                HotkeyKeyOption("NUMPAD_8", "Numpad 8"),
                HotkeyKeyOption("NUMPAD_9", "Numpad 9"),
                HotkeyKeyOption("NUMPAD_ADD", "Numpad +"),
                HotkeyKeyOption("NUMPAD_SUBTRACT", "Numpad -"),
                HotkeyKeyOption("NUMPAD_MULTIPLY", "Numpad *"),
                HotkeyKeyOption("NUMPAD_DIVIDE", "Numpad /"),
                HotkeyKeyOption("NUMPAD_DECIMAL", "Numpad ."),
                HotkeyKeyOption("NUMPAD_ENTER", "Numpad Enter"),
            ),
        )
    }
    private val hotkeyModifierIdSet = hotkeyModifierOptions.map { it.id }.toSet()
    private val hotkeyOptionMap = (hotkeyModifierOptions + hotkeyPrimaryOptions).associateBy { it.id }

    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressClipboardCallback || enabledClipboardSyncPcIds().isEmpty()) {
            return@OnPrimaryClipChangedListener
        }

        processLocalClipboardChange()
    }

    private val livePreviewRunnable = Runnable { pollLivePreview() }
    private val clipboardPollRunnable = object : Runnable {
        override fun run() {
            if (!isActivityResumed) {
                return
            }

            processLocalClipboardChange()
            if (enabledClipboardSyncPcIds().isNotEmpty()) {
                mainHandler.postDelayed(this, CLIPBOARD_POLL_INTERVAL_MS)
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    private val uploadFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            uploadSelectedPhoneFile(uri)
        }
    }

    private val uploadImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            uploadSelectedPhoneFile(uri)
        }
    }

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        if (uri == null) {
            val objectKey = pendingDownloadObjectKey
            pendingDownloadBytes = null
            pendingDownloadObjectKey = null
            appendLog("Dosya kaydetme iptal edildi.")
            if (!objectKey.isNullOrBlank()) {
                runInBackground {
                    runCatching {
                        api.deleteReservedFile(store.workerUrl, store.ownerToken, objectKey)
                    }
                }
            }
            return@registerForActivityResult
        }

        saveDownloadedFile(uri)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets()
        val retainedState = lastCustomNonConfigurationInstance as? RetainedUiState

        store = DeviceStore(this)
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        lastLocalClipboardSignature = store.lastLocalClipboardSignature
        workerSupportsR2 = readStoredWorkerCapability()
        sanitizeStoredSetup()
        BackgroundPermissionMonitorWorker.schedule(this)
        requestNotificationPermissionIfNeeded()

        binding.workerUrlInput.setText(store.workerUrl)
        binding.deviceNameInput.setText(store.deviceName)
        binding.pairingCodeInput.setText("")
        binding.filesPathInput.setText("")
        binding.notificationDisplayLimitInput.setText(store.notificationDisplayLimit.toString())
        updateSelectedPcDisplay(store.pairedPcName.takeIf { it.isNotBlank() }, null)
        binding.unpairButton.isEnabled = store.pairedPcId.isNotBlank()
        binding.notificationSettingsText.text = "Bildirim tercihlerini buradan yonetebilirsin."
        binding.clipboardSyncStatusText.text = "Clipboard senkronizasyonu icin once bir PC sec."
        binding.livePreviewStatusText.text = "Canli ekran onizlemesi icin once bir PC sec."

        updateSelectedRemoteFile(null)
        updateSelectedProcess(null)
        updateScreenshotActions()
        updateDragButton()
        updateLivePreviewButtons()
        renderUsageSummaryPlaceholder()
        renderPcSummary(emptyList())
        renderTransferIdleState()
        renderFilePreviewPlaceholder()
        updateWorkerCapabilityStatusViews()
        updateNotificationLimitLabels()
        setupSectionTabs(initialTabIndex = retainedState?.selectedTabIndex ?: 0)
        setupButtons()
        applySelectedPcScopedState(restoreLivePreview = false)
        restoreRetainedUiState(retainedState)
        refreshBackgroundPermissionState(promptIfDue = shouldCheckBackgroundPermission())
        handleBackgroundPermissionIntent(intent)
        if (store.workerUrl.isNotBlank()) {
            refreshWorkerCapabilities(showFeedback = false)
        }
        binding.root.postDelayed({ maybeShowFirstRunPrompt() }, 350L)

        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            store.fcmToken = token
            appendLog("FCM token alindi.")
            registerTokenIfPossible()
        }.addOnFailureListener { error ->
            appendLog("FCM token alinamadi: ${friendlyErrorMessage(error.message, "FCM token alinamadi.")}")
        }

        if (store.workerUrl.isNotBlank() && store.ownerToken.isNotBlank()) {
            refreshPcState()
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        registerTokenIfPossible()
        refreshUsageSummary()
        refreshBackgroundPermissionState(promptIfDue = shouldCheckBackgroundPermission())
        if (store.workerUrl.isNotBlank() && workerSupportsR2 == null) {
            refreshWorkerCapabilities(showFeedback = false)
        }
        if (enabledClipboardSyncPcIds().isNotEmpty()) {
            startClipboardSync()
        }
        if (store.getLivePreviewEnabled(selectedPcIdOrNull()) && !isLivePreviewRunning) {
            startLivePreview(persistPreference = false)
        }
    }

    override fun onPause() {
        isActivityResumed = false
        pauseClipboardForegroundMonitoring()
        super.onPause()
    }

    override fun onDestroy() {
        stopLivePreview(updateStoredPreference = false)
        stopClipboardSync()
        executor.shutdownNow()
        inputExecutor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any {
        return RetainedUiState(
            selectedTabIndex = binding.mainTabLayout.selectedTabPosition.coerceAtLeast(0),
            screenshotBytes = lastScreenshotBytes,
            screenshotFileName = lastScreenshotFileName,
            notifications = lastRenderedNotifications,
            unreadNotificationCount = unreadNotificationCount,
            availablePcs = availablePcs,
            statusText = binding.statusText.text?.toString().orEmpty(),
            resultText = binding.resultText.text?.toString().orEmpty(),
            logText = binding.logText.text?.toString().orEmpty(),
        )
    }

    private fun applySystemBarInsets() {
        val initialLeft = binding.root.paddingLeft
        val initialTop = binding.root.paddingTop
        val initialRight = binding.root.paddingRight
        val initialBottom = binding.root.paddingBottom
        val extraInset = (resources.displayMetrics.density * EXTRA_SYSTEM_INSET_DP).roundToInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft,
                initialTop + systemBars.top,
                initialRight,
                initialBottom + systemBars.bottom + extraInset,
            )
            insets
        }

        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupSectionTabs(initialTabIndex: Int = 0) {
        val root = binding.contentRootLayout
        val sectionSpecs = listOf(
            SectionSpec(
                title = "Ana Panel",
                container = binding.homeSection,
                anchorIds = listOf(
                    R.id.refreshStatusButton,
                    R.id.selectedPcText,
                    R.id.selectedPcCapabilityText,
                    R.id.selectPcButton,
                    R.id.statusText,
                    R.id.pcListSummaryText,
                    R.id.securityHeading,
                    R.id.lockButton,
                    R.id.restartButton,
                    R.id.mediaHeading,
                    R.id.playPauseButton,
                    R.id.previousTrackButton,
                    R.id.muteButton,
                    R.id.appLaunchHeading,
                    R.id.launchChromeButton,
                    R.id.launchSpotifyButton,
                    R.id.launchCoreAppsButton,
                    R.id.customAppPathInput,
                    R.id.customAppArgumentsInput,
                    R.id.launchCustomAppButton,
                    R.id.screenshotHeading,
                    R.id.screenshotButton,
                    R.id.saveScreenshotButton,
                    R.id.startLivePreviewButton,
                    R.id.startHdLivePreviewButton,
                    R.id.livePreviewStatusText,
                    R.id.screenshotPreview,
                    R.id.resultHeading,
                    R.id.resultText,
                ),
            ),
            SectionSpec(
                title = "Kisayollar",
                container = binding.shortcutsSection,
                anchorIds = emptyList(),
            ),
            SectionSpec(
                title = "Dosyalar",
                container = binding.filesSection,
                anchorIds = listOf(
                    R.id.filesHeading,
                    R.id.filesPathInput,
                    R.id.listRootsButton,
                    R.id.goUpButton,
                    R.id.selectedRemoteEntryText,
                    R.id.fileSelectionSummaryText,
                    R.id.newFolderNameInput,
                    R.id.createFolderButton,
                    R.id.renameTargetInput,
                    R.id.launchSelectedFileButton,
                    R.id.previewSelectedFileButton,
                    R.id.renameSelectedFileButton,
                    R.id.deleteMultiSelectedFilesButton,
                    R.id.transferProgressBar,
                    R.id.transferStatusText,
                    R.id.filesHintText,
                    R.id.remoteFilesContainer,
                    R.id.filePreviewContainer,
                ),
            ),
            SectionSpec(
                title = "Sistem",
                container = binding.systemSection,
                anchorIds = listOf(
                    R.id.processHeading,
                    R.id.loadForegroundProcessesButton,
                    R.id.killProcessInput,
                    R.id.killSelectedProcessButton,
                    R.id.processListContainer,
                    R.id.systemInfoHeading,
                    R.id.refreshSystemInfoButton,
                    R.id.systemInfoText,
                    R.id.clipboardHeading,
                    R.id.clipboardInput,
                    R.id.clipboardSetButton,
                    R.id.clipboardSyncSwitch,
                    R.id.clipboardSyncStatusText,
                    R.id.keyboardInput,
                    R.id.sendKeyboardButton,
                    R.id.sendEnterButton,
                    R.id.ctrlAButton,
                    R.id.mouseHeading,
                    R.id.mouseHintText,
                    R.id.touchpadView,
                    R.id.dragModeButton,
                    R.id.mouseUpButton,
                    R.id.mouseLeftButton,
                    R.id.leftClickButton,
                    R.id.scrollUpButton,
                ),
            ),
            SectionSpec(
                title = "Bildirimler",
                container = binding.notificationsSection,
                anchorIds = listOf(
                    R.id.notificationSettingsHeading,
                    R.id.notificationSettingsText,
                    R.id.loadNotificationSettingsButton,
                    R.id.startupNotificationSwitch,
                    R.id.shutdownNotificationSwitch,
                    R.id.agentStopNotificationSwitch,
                    R.id.sleepNotificationSwitch,
                    R.id.wakeNotificationSwitch,
                    R.id.offlineNotificationSwitch,
                    R.id.commandFailedNotificationSwitch,
                    R.id.notificationDisplayLimitHeading,
                    R.id.notificationDisplayLimitInput,
                    R.id.notificationDisplayLimitHintText,
                    R.id.notificationCenterHeading,
                    R.id.notificationStatusText,
                    R.id.refreshNotificationCenterButton,
                    R.id.notificationCenterContainer,
                ),
            ),
            SectionSpec(
                title = "Ayarlar",
                container = binding.settingsSection,
                anchorIds = listOf(
                    R.id.openGettingStartedGuideButton,
                    R.id.workerUrlInput,
                    R.id.deviceNameInput,
                    R.id.saveConfigButton,
                    R.id.workerCapabilityStatusText,
                    R.id.backgroundPermissionHeading,
                    R.id.backgroundPermissionStatusText,
                    R.id.backgroundPermissionActions,
                    R.id.cleanInstallHintText,
                    R.id.pairingCodeInput,
                    R.id.pairButton,
                    R.id.logHeading,
                    R.id.logText,
                    R.id.usageSummaryText,
                ),
            ),
        )

        sectionSpecs.forEach { spec ->
            spec.anchorIds.forEach { anchorId ->
                moveAnchoredView(root, spec.container, anchorId)
            }
        }

        binding.mainTabLayout.removeAllTabs()
        sectionSpecs.forEach { spec ->
            binding.mainTabLayout.addTab(binding.mainTabLayout.newTab().setText(spec.title), false)
        }

        val allSections = sectionSpecs.map { it.container }
        val resolvedTabIndex = initialTabIndex.coerceIn(0, sectionSpecs.lastIndex)
        currentSectionTabIndex = resolvedTabIndex
        showSection(sectionSpecs[resolvedTabIndex].container, allSections)
        updateFilesTabBadge()
        updateNotificationsTabBadge(0)
        updateSettingsTabBadge()
        binding.mainTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val selected = sectionSpecs.getOrNull(tab.position) ?: return
                currentSectionTabIndex = tab.position
                showSection(selected.container, allSections)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit

            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.mainTabLayout.getTabAt(resolvedTabIndex)?.select()
    }

    private fun moveAnchoredView(root: LinearLayout, target: LinearLayout, anchorId: Int) {
        val anchor = root.findViewById<View>(anchorId) ?: return
        var movable = anchor
        while (movable.parent is ViewGroup && movable.parent != root) {
            movable = movable.parent as View
        }

        if (movable.parent == root && movable !== target && movable !in listOf(
                binding.headerCard,
                binding.mainTabLayout,
                binding.homeSection,
                binding.shortcutsSection,
                binding.filesSection,
                binding.systemSection,
                binding.notificationsSection,
                binding.settingsSection,
            )
        ) {
            root.removeView(movable)
            target.addView(movable)
        }
    }

    private fun showSection(selected: View, allSections: List<View>) {
        allSections.forEach { section ->
            section.visibility = if (section === selected) View.VISIBLE else View.GONE
        }
        binding.root.post { binding.root.fullScroll(View.FOCUS_UP) }
    }

    private fun sanitizeStoredSetup() {
        if (store.ownerToken.isBlank()) {
            store.pairedPcId = ""
            store.pairedPcName = ""
        }
    }

    private fun selectedPcIdOrNull(): String? = store.pairedPcId.takeIf { it.isNotBlank() }

    private fun setClipboardSyncSwitchChecked(isChecked: Boolean) {
        suppressClipboardSwitchCallback = true
        binding.clipboardSyncSwitch.isChecked = isChecked
        suppressClipboardSwitchCallback = false
    }

    private fun applySelectedPcScopedState(restoreLivePreview: Boolean) {
        val scopedPcId = selectedPcIdOrNull()
        val selectedPcName = store.pairedPcName.ifBlank { "Secili PC" }
        store.migrateLegacyPcScopedStateIfNeeded(scopedPcId)

        stopClipboardSync()
        stopLivePreview(updateStoredPreference = false)

        val clipboardEnabled = scopedPcId != null && store.getClipboardSyncEnabled(scopedPcId)
        val livePreviewEnabled = scopedPcId != null && store.getLivePreviewEnabled(scopedPcId)
        activeLivePreviewProfile =
            scopedPcId?.let { resolveLivePreviewProfile(store.getLivePreviewMode(it)) }
                ?: LIVE_PREVIEW_PROFILE_ORIGINAL
        currentRemotePath = scopedPcId?.let { store.getRemotePath(it) }.orEmpty()
        currentRemoteParentPath = null
        binding.filesPathInput.setText(currentRemotePath)

        setClipboardSyncSwitchChecked(clipboardEnabled)
        binding.clipboardSyncStatusText.text = when {
            scopedPcId == null -> "Clipboard senkronizasyonu icin once bir PC sec."
            clipboardEnabled -> "$selectedPcName icin clipboard senkronizasyonu hazir. Telefonda kopyalanan yazi uygulama acikken gonderilir."
            else -> "$selectedPcName icin clipboard senkronizasyonu kapali."
        }
        binding.livePreviewStatusText.text = when {
            scopedPcId == null -> "Canli ekran onizlemesi icin once bir PC sec."
            livePreviewEnabled -> "$selectedPcName icin canli ekran onizlemesi hazir (${activeLivePreviewProfile.label})."
            else -> "$selectedPcName icin canli ekran onizlemesi kapali."
        }

        shortcutItems.clear()
        activeShortcutId = null
        clearShortcutDragState(refreshGrid = false)
        shortcutItems += readStoredShortcutItems(scopedPcId)
        renderShortcutGrid()
        updateLivePreviewButtons()

        if (enabledClipboardSyncPcIds().isNotEmpty()) {
            startClipboardSync()
        }
        if (restoreLivePreview && livePreviewEnabled) {
            startLivePreview(activeLivePreviewProfile, persistPreference = false)
        }
    }

    private fun restoreRetainedUiState(state: RetainedUiState?) {
        if (state == null) {
            return
        }

        currentSectionTabIndex = state.selectedTabIndex
        lastScreenshotBytes = state.screenshotBytes
        lastScreenshotFileName = state.screenshotFileName
        availablePcs = state.availablePcs
        if (state.availablePcs.isNotEmpty()) {
            renderPcSummary(state.availablePcs)
        }
        if (state.statusText.isNotBlank()) {
            binding.statusText.text = state.statusText
        }
        if (state.resultText.isNotBlank()) {
            binding.resultText.text = state.resultText
        }
        if (state.logText.isNotBlank()) {
            binding.logText.text = state.logText
        }
        renderCurrentScreenshotPreview()
        if (state.notifications.isNotEmpty() || state.unreadNotificationCount > 0) {
            renderNotificationCenter(state.notifications, state.unreadNotificationCount)
        } else {
            updateNotificationsTabBadge(0)
            updateNotificationLimitLabels()
        }
    }

    private fun renderCurrentScreenshotPreview() {
        val bytes = lastScreenshotBytes
        if (bytes == null) {
            binding.screenshotPreview.setImageDrawable(null)
            updateScreenshotActions()
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            binding.screenshotPreview.setImageDrawable(null)
            lastScreenshotBytes = null
        } else {
            binding.screenshotPreview.setImageBitmap(bitmap)
        }
        updateScreenshotActions()
    }

    private fun currentNotificationDisplayLimit(): Int = store.notificationDisplayLimit

    private fun updateNotificationLimitLabels() {
        val limit = currentNotificationDisplayLimit()
        binding.notificationCenterHeading.text = "Son $limit bildirim"
        if (lastRenderedNotifications.isEmpty()) {
            binding.notificationStatusText.text = "Son $limit bildirim burada gorunecek."
        }
    }

    private fun parseNotificationDisplayLimit(): Int? {
        val rawValue = binding.notificationDisplayLimitInput.text?.toString()?.trim().orEmpty()
        val parsed = rawValue.toIntOrNull()
        if (parsed == null || parsed !in MIN_NOTIFICATION_DISPLAY_LIMIT..MAX_NOTIFICATION_DISPLAY_LIMIT) {
            binding.notificationDisplayLimitInput.error =
                "$MIN_NOTIFICATION_DISPLAY_LIMIT ile $MAX_NOTIFICATION_DISPLAY_LIMIT arasinda bir sayi gir."
            return null
        }

        binding.notificationDisplayLimitInput.error = null
        return parsed
    }

    private fun shouldCheckBackgroundPermission(): Boolean {
        val lastCheckedAt = store.backgroundPermissionLastCheckedAt
        return lastCheckedAt == 0L || System.currentTimeMillis() - lastCheckedAt >= BACKGROUND_PERMISSION_RECHECK_MS
    }

    private fun handleBackgroundPermissionIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("open_background_settings", false) == true) {
            requestBackgroundPermission()
            intent.removeExtra("open_background_settings")
        }
    }

    private fun refreshBackgroundPermissionState(
        promptIfDue: Boolean,
        forceTimestampUpdate: Boolean = false,
    ) {
        val isGranted = isBackgroundPermissionGranted()
        val now = System.currentTimeMillis()
        val due = shouldCheckBackgroundPermission()

        store.backgroundPermissionGranted = isGranted
        if (forceTimestampUpdate || due) {
            store.backgroundPermissionLastCheckedAt = now
        }

        val nextCheckAt = if (store.backgroundPermissionLastCheckedAt == 0L) {
            "Ilk kontrol bekleniyor."
        } else {
            val nextCheck = store.backgroundPermissionLastCheckedAt + BACKGROUND_PERMISSION_RECHECK_MS
            "Bir sonraki otomatik kontrol: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(nextCheck)}"
        }

        val limitationNote =
            "Not: Android 10+ kisiti nedeniyle telefondan PC'ye pano senkronu sadece uygulama acikken calisir."
        val manufacturerNote = if (isMiuiFamilyDevice()) {
            "Bu cihazda ayrica Otomatik baslat ve pil kisitini Sinirsiz/Yok olarak ayarlamak gerekebilir."
        } else {
            null
        }

        binding.backgroundPermissionStatusText.text = if (isGranted) {
            buildString {
                append("Arka plan calisma izni acik. Bildirimler, canli baglanti ve PC'den telefona gelen pano daha stabil calisir.")
                append('\n')
                append(limitationNote)
                manufacturerNote?.let {
                    append('\n')
                    append(it)
                }
                append('\n')
                append(nextCheckAt)
            }
        } else {
            buildString {
                append("Arka plan calisma izni kapali gorunuyor. Batarya optimizasyonu bildirimleri, canli baglantiyi ve PC'den telefona gelen panoyu zayiflatabilir.")
                append('\n')
                append(limitationNote)
                manufacturerNote?.let {
                    append('\n')
                    append(it)
                }
                append('\n')
                append(nextCheckAt)
            }
        }
        binding.requestBackgroundPermissionButton.isEnabled = !isGranted
        updateSettingsTabBadge()

        if (!isGranted && promptIfDue) {
            AlertDialog.Builder(this)
                .setTitle("Arka plan izni onerilir")
                .setMessage(
                    buildString {
                        append("Batarya optimizasyonundan muaf olmak bildirimler, canli baglanti ve PC'den telefona gelen pano icin daha sagliklidir.")
                        append("\n\n")
                        append("Telefon -> PC clipboard senkronu ise Android kisiti nedeniyle diger uygulamalarda arka planda calismaz; bunun icin uygulamanin acik olmasi gerekir.")
                        if (isMiuiFamilyDevice()) {
                            append("\n\n")
                            append("Xiaomi/Redmi/POCO cihazlarda ayrica Otomatik baslat ve pil kisitini Sinirsiz/Yok yapman gerekebilir.")
                        }
                    },
                )
                .setPositiveButton("Ayarı ac") { _, _ -> requestBackgroundPermission() }
                .setNegativeButton("Sonra", null)
                .show()
        }
    }

    private fun requestBackgroundPermission() {
        if (isBackgroundPermissionGranted()) {
            refreshBackgroundPermissionState(promptIfDue = false, forceTimestampUpdate = true)
            showToast("Arka plan izni zaten acik.")
            return
        }

        val directIntent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )

        val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val targetIntent = when {
            directIntent.resolveActivity(packageManager) != null -> directIntent
            fallbackIntent.resolveActivity(packageManager) != null -> fallbackIntent
            else -> null
        }

        if (targetIntent == null) {
            showToast("Bu cihazda batarya optimizasyon ayari acilamadi.")
            return
        }

        startActivity(targetIntent)
        if (isMiuiFamilyDevice()) {
            showToast("Xiaomi/Redmi/POCO cihazlarda ayrica Otomatik baslat ve pil kisitini Sinirsiz/Yok yap.")
        }
    }

    private fun isBackgroundPermissionGranted(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun updateFilesTabBadge() {
        val tab = binding.mainTabLayout.getTabAt(TAB_INDEX_FILES) ?: return
        val selectionCount = selectedRemoteEntries.size
        if (selectionCount <= 0) {
            tab.removeBadge()
            return
        }

        tab.orCreateBadge.apply {
            number = selectionCount.coerceAtMost(99)
            isVisible = true
        }
    }

    private fun updateNotificationsTabBadge(unreadCount: Int) {
        unreadNotificationCount = unreadCount
        val tab = binding.mainTabLayout.getTabAt(TAB_INDEX_NOTIFICATIONS) ?: return
        if (unreadCount <= 0) {
            tab.removeBadge()
            return
        }

        tab.orCreateBadge.apply {
            number = unreadCount.coerceAtMost(99)
            isVisible = true
        }
    }

    private fun updateSettingsTabBadge() {
        val tab = binding.mainTabLayout.getTabAt(TAB_INDEX_SETTINGS) ?: return
        if (store.backgroundPermissionGranted) {
            tab.removeBadge()
            return
        }

        tab.orCreateBadge.apply {
            clearNumber()
            isVisible = true
        }
    }

    private fun readStoredWorkerCapability(): Boolean? {
        return when (store.workerR2SupportState) {
            WORKER_R2_STATE_SUPPORTED -> true
            WORKER_R2_STATE_LEGACY -> false
            else -> null
        }
    }

    private fun setWorkerCapabilityState(supportsR2: Boolean?) {
        workerSupportsR2 = supportsR2
        store.workerR2SupportState = when (supportsR2) {
            true -> WORKER_R2_STATE_SUPPORTED
            false -> WORKER_R2_STATE_LEGACY
            null -> WORKER_R2_STATE_UNKNOWN
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateWorkerCapabilityStatusViews()
        } else {
            mainHandler.post { updateWorkerCapabilityStatusViews() }
        }
    }

    private fun updateWorkerCapabilityStatusViews() {
        val status = when {
            store.workerUrl.isBlank() -> Triple(
                "Worker dosya modu: Worker URL bekleniyor.",
                "Secili Worker: URL girilmedi.",
                R.color.text_secondary,
            )
            workerSupportsR2 == true -> Triple(
                "Worker dosya modu: R2 aktif. Buyuk dosya aktarimi hazir.",
                "Secili Worker: R2 destekliyor.",
                R.color.success_green,
            )
            workerSupportsR2 == false -> Triple(
                "Worker dosya modu: Legacy. Dosya limiti 256 KB.",
                "Secili Worker: R2 yok, legacy mod aktif.",
                R.color.warning_amber,
            )
            else -> Triple(
                "Worker dosya modu: Kontrol ediliyor. Gerekirse otomatik fallback denenir.",
                "Secili Worker: R2 destegi kontrol ediliyor.",
                R.color.text_secondary,
            )
        }

        val color = ContextCompat.getColor(this, status.third)
        binding.workerCapabilityStatusText.text = status.first
        binding.workerCapabilityStatusText.setTextColor(color)
        binding.selectedPcCapabilityText.text = status.second
        binding.selectedPcCapabilityText.setTextColor(color)
    }

    private fun refreshWorkerCapabilities(showFeedback: Boolean) {
        val workerUrl = normalizeWorkerUrl(binding.workerUrlInput.text.toString())
        if (workerUrl.isBlank()) {
            setWorkerCapabilityState(null)
            return
        }

        runInBackground {
            val health = runCatching { api.getHealth(workerUrl) }.getOrNull()
            val detectedSupport = when {
                health?.optJSONObject("capabilities")?.has("r2") == true -> health.optJSONObject("capabilities")?.optBoolean("r2")
                health?.has("supportsR2") == true -> health.optBoolean("supportsR2")
                else -> null
            }

            runOnUiThread {
                setWorkerCapabilityState(detectedSupport)
            }

            if (showFeedback) {
                runOnUiThread {
                    val message = when (detectedSupport) {
                        true -> "Worker R2 destekliyor. Dosya aktarimi gelismis modda calisacak."
                        false -> "Worker R2 desteklemiyor. Dosya aktarimi legacy modda calisacak."
                        null -> "Worker dosya modu belirlenemedi. Uygulama gerektiğinde otomatik fallback deneyecek."
                    }
                    appendLog(message)
                }
            }
        }
    }

    private fun shouldTryR2Transfers(): Boolean = workerSupportsR2 != false

    private fun markWorkerAsLegacy() {
        if (workerSupportsR2 != false) {
            setWorkerCapabilityState(false)
            runOnUiThread {
                appendLog("Bu Worker icin legacy dosya aktarim moduna gecildi.")
            }
        }
    }

    private fun isR2UnsupportedError(message: String?): Boolean {
        val text = message?.lowercase().orEmpty()
        return text.contains("r2 bucket binding is missing") ||
            text.contains("bucket binding is missing") ||
            text.contains("/api/mobile/files/reserve") ||
            text.contains("objectkey") ||
            text == "not found" ||
            text.contains("\"error\":\"not found\"")
    }

    private fun setupButtons() {
        binding.openGettingStartedGuideButton.setOnClickListener {
            openGettingStartedGuide()
        }
        binding.shortcutReorderButton.setOnClickListener {
            isShortcutReorderMode = !isShortcutReorderMode
            shortcutDropHandled = false
            clearShortcutDragState(refreshGrid = false)
            renderShortcutGrid()
            showToast(
                if (isShortcutReorderMode) {
                    "Siralama modu acildi. Karti basili tutup surukleyebilirsin."
                } else {
                    "Siralama modu kapatildi."
                },
            )
        }
        binding.shortcutGrid.setOnDragListener { _, event -> handleShortcutGridDrag(event) }
        binding.saveConfigButton.setOnClickListener {
            persistLocalConfig()
            appendLog("Yerel ayarlar kaydedildi.")
            refreshWorkerCapabilities(showFeedback = true)
        }
        binding.requestBackgroundPermissionButton.setOnClickListener {
            requestBackgroundPermission()
        }
        binding.checkBackgroundPermissionButton.setOnClickListener {
            refreshBackgroundPermissionState(promptIfDue = false, forceTimestampUpdate = true)
        }

        binding.pairButton.setOnClickListener {
            persistLocalConfig()
            val pairingCode = binding.pairingCodeInput.text.toString().trim()
            runInBackground {
                ensureFcmTokenReady()

                val result = api.pairDevice(
                    workerUrl = store.workerUrl,
                    deviceName = store.deviceName,
                    pairingCode = pairingCode,
                    fcmToken = store.fcmToken,
                    existingOwnerToken = store.ownerToken.takeIf { it.isNotBlank() },
                )

                store.ownerToken = result.ownerToken
                store.pairedPcId = result.pcId
                store.pairedPcName = result.pcName

                runOnUiThread {
                    updateSelectedPcDisplay(result.pcName, result.status)
                    binding.unpairButton.isEnabled = true
                    binding.statusText.text = "Paired: ${result.pcName} (${result.status})"
                    appendLog("Telefon eslesti: ${result.pcName}")
                    refreshPcState()
                    refreshUsageSummary()
                    updateSettingsTabBadge()
                }

                registerTokenIfPossible()
            }
        }

        binding.refreshStatusButton.setOnClickListener {
            refreshPcState()
            refreshWorkerCapabilities(showFeedback = false)
        }
        binding.selectPcButton.setOnClickListener { showPcSelectionDialog() }
        binding.loadNotificationSettingsButton.setOnClickListener { loadNotificationSettings() }
        binding.saveNotificationSettingsButton.setOnClickListener { saveNotificationSettings() }
        binding.refreshNotificationCenterButton.setOnClickListener { loadNotificationCenter() }
        binding.markAllNotificationsReadButton.setOnClickListener { markAllNotificationsRead() }

        binding.lockButton.setOnClickListener { sendAwaitedCommand("Ekran kilitle", "lock") }
        binding.shutdownButton.setOnClickListener { sendAwaitedCommand("Bilgisayari zorla kapat", "shutdown") }
        binding.restartButton.setOnClickListener { sendAwaitedCommand("Zorla yeniden baslat", "restart") }
        binding.logoffButton.setOnClickListener { sendAwaitedCommand("Oturumu kapat", "logoff") }
        binding.unpairButton.setOnClickListener { unpairCurrentPc() }

        binding.playPauseButton.setOnClickListener {
            sendAwaitedCommand("Play/Pause", "media", JSONObject().put("action", "play-pause"))
        }
        binding.stopButton.setOnClickListener {
            sendAwaitedCommand("Stop", "media", JSONObject().put("action", "stop"))
        }
        binding.nextTrackButton.setOnClickListener {
            sendAwaitedCommand("Sonraki", "media", JSONObject().put("action", "next"))
        }
        binding.previousTrackButton.setOnClickListener {
            sendAwaitedCommand("Onceki", "media", JSONObject().put("action", "previous"))
        }
        binding.volumeUpButton.setOnClickListener {
            sendAwaitedCommand("Ses artir", "volume", JSONObject().put("action", "up").put("steps", 2))
        }
        binding.volumeDownButton.setOnClickListener {
            sendAwaitedCommand("Ses azalt", "volume", JSONObject().put("action", "down").put("steps", 2))
        }
        binding.muteButton.setOnClickListener {
            sendAwaitedCommand("Mute", "volume", JSONObject().put("action", "mute"))
        }

        binding.launchChromeButton.setOnClickListener { launchPresetApp("Chrome", "chrome") }
        binding.launchOperaButton.setOnClickListener { launchPresetApp("Opera", "opera") }
        binding.launchSpotifyButton.setOnClickListener { launchPresetApp("Spotify", "spotify") }
        binding.launchDiscordButton.setOnClickListener { launchPresetApp("Discord", "discord") }
        binding.launchCoreAppsButton.setOnClickListener {
            val applications = JSONArray()
                .put(JSONObject().put("path", "chrome"))
                .put(JSONObject().put("path", "spotify"))
                .put(JSONObject().put("path", "discord"))

            sendAwaitedCommand(
                label = "Chrome + Spotify + Discord",
                type = "macro-run",
                payload = JSONObject().put("applications", applications),
            )
        }
        binding.launchCustomAppButton.setOnClickListener {
            val path = binding.customAppPathInput.text.toString().trim()
            if (path.isBlank()) {
                showToast("Once custom uygulama yolu sec.")
                return@setOnClickListener
            }

            sendAwaitedCommand(
                label = "Custom uygulama",
                type = "app-launch",
                payload = JSONObject()
                    .put("path", path)
                    .put("arguments", binding.customAppArgumentsInput.text.toString().trim()),
            )
        }

        binding.loadForegroundProcessesButton.setOnClickListener { loadProcessList("foreground") }
        binding.loadAllProcessesButton.setOnClickListener { loadProcessList("all") }
        binding.killSelectedProcessButton.setOnClickListener { killSelectedProcess() }
        binding.refreshSystemInfoButton.setOnClickListener { loadSystemInfo() }

        binding.clipboardSetButton.setOnClickListener {
            sendAwaitedCommand(
                label = "Clipboard gonder",
                type = "clipboard-set",
                payload = JSONObject().put("text", binding.clipboardInput.text.toString()),
            )
        }
        binding.clipboardGetButton.setOnClickListener {
            sendAwaitedCommand("Clipboard oku", "clipboard-get") { payload ->
                val text = payload.optString("text", "")
                binding.clipboardInput.setText(text)
                binding.resultText.text = if (text.isBlank()) "Clipboard bos." else "Clipboard: $text"
            }
        }
        binding.clipboardClearButton.setOnClickListener {
            binding.clipboardInput.setText("")
            sendAwaitedCommand(
                label = "Clipboard temizle",
                type = "clipboard-set",
                payload = JSONObject().put("text", ""),
            )
        }
        binding.clipboardSyncSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressClipboardSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            val scopedPcId = selectedPcIdOrNull()
            if (scopedPcId == null) {
                setClipboardSyncSwitchChecked(false)
                stopClipboardSync()
                showToast("Clipboard sync icin once bir PC sec.")
                return@setOnCheckedChangeListener
            }

            store.setClipboardSyncEnabled(scopedPcId, isChecked)
            if (enabledClipboardSyncPcIds().isNotEmpty()) {
                startClipboardSync()
            } else {
                stopClipboardSync()
            }
        }
        binding.sendKeyboardButton.setOnClickListener {
            val text = binding.keyboardInput.text.toString()
            if (text.isBlank()) {
                showToast("Klavye metni bos olamaz.")
                return@setOnClickListener
            }

            sendAwaitedCommand(
                label = "Klavye metni gonder",
                type = "input-keyboard",
                payload = JSONObject().put("text", text),
            )
        }
        binding.sendEnterButton.setOnClickListener { sendKeyboardShortcut("Enter", "{ENTER}") }
        binding.sendBackspaceButton.setOnClickListener { sendKeyboardShortcut("Backspace", "{BACKSPACE}") }
        binding.sendTabButton.setOnClickListener { sendKeyboardShortcut("Tab", "{TAB}") }
        binding.sendEscButton.setOnClickListener { sendKeyboardShortcut("Esc", "{ESC}") }
        binding.ctrlAButton.setOnClickListener { sendKeyboardShortcut("Ctrl+A", "^a") }
        binding.ctrlCButton.setOnClickListener { sendKeyboardShortcut("Ctrl+C", "^c") }
        binding.ctrlVButton.setOnClickListener { sendKeyboardShortcut("Ctrl+V", "^v") }
        binding.ctrlXButton.setOnClickListener { sendKeyboardShortcut("Ctrl+X", "^x") }

        binding.dragModeButton.setOnClickListener {
            isDragModeEnabled = !isDragModeEnabled
            updateDragButton()
        }
        binding.middleClickButton.setOnClickListener {
            sendAwaitedCommand("Orta tik", "input-mouse", JSONObject().put("action", "middle-click"))
        }
        binding.mouseUpButton.setOnClickListener { sendMouseMove("Yukari", 0, -80) }
        binding.mouseDownButton.setOnClickListener { sendMouseMove("Asagi", 0, 80) }
        binding.mouseLeftButton.setOnClickListener { sendMouseMove("Sola", -80, 0) }
        binding.mouseRightButton.setOnClickListener { sendMouseMove("Saga", 80, 0) }
        binding.leftClickButton.setOnClickListener {
            sendAwaitedCommand("Sol tik", "input-mouse", JSONObject().put("action", "left-click"))
        }
        binding.rightClickButton.setOnClickListener {
            sendAwaitedCommand("Sag tik", "input-mouse", JSONObject().put("action", "right-click"))
        }
        binding.scrollUpButton.setOnClickListener {
            sendAwaitedCommand("Scroll yukari", "input-mouse", JSONObject().put("action", "scroll").put("delta", 240))
        }
        binding.scrollDownButton.setOnClickListener {
            sendAwaitedCommand("Scroll asagi", "input-mouse", JSONObject().put("action", "scroll").put("delta", -240))
        }
        binding.touchpadView.setOnTouchListener { _, event -> handleTouchpadEvent(event) }

        binding.listRootsButton.setOnClickListener {
            binding.filesPathInput.setText("")
            listRemoteFiles("")
        }
        binding.listFilesButton.setOnClickListener {
            listRemoteFiles(binding.filesPathInput.text.toString().trim())
        }
        binding.goUpButton.setOnClickListener {
            listRemoteFiles(currentRemoteParentPath.orEmpty())
        }
        binding.createFolderButton.setOnClickListener { createRemoteFolder() }
        binding.pickUploadFileButton.setOnClickListener {
            val targetDirectory = binding.filesPathInput.text.toString().trim()
            if (targetDirectory.isBlank()) {
                showToast("Yukleme icin once bir klasor ac.")
                return@setOnClickListener
            }

            uploadFileLauncher.launch("*/*")
        }
        binding.pickImageUploadButton.setOnClickListener {
            val targetDirectory = binding.filesPathInput.text.toString().trim()
            if (targetDirectory.isBlank()) {
                showToast("Resim yuklemek icin once bir klasor ac.")
                return@setOnClickListener
            }

            uploadImageLauncher.launch("image/*")
        }
        binding.launchSelectedFileButton.setOnClickListener {
            val path = selectedRemoteFilePath ?: binding.customAppPathInput.text.toString().trim()
            if (path.isBlank()) {
                showToast("Once listeden bir dosya sec.")
                return@setOnClickListener
            }

            sendAwaitedCommand(
                label = "Secili dosyayi calistir",
                type = "app-launch",
                payload = JSONObject()
                    .put("path", path)
                    .put("arguments", binding.customAppArgumentsInput.text.toString().trim()),
            )
        }
        binding.downloadSelectedFileButton.setOnClickListener {
            val path = selectedRemoteFilePath
            if (path.isNullOrBlank()) {
                showToast("Once listeden bir dosya sec.")
                return@setOnClickListener
            }

            downloadRemoteFile(path)
        }
        binding.previewSelectedFileButton.setOnClickListener { previewSelectedRemoteFile() }
        binding.clearFileSelectionButton.setOnClickListener { clearRemoteSelection() }
        binding.renameSelectedFileButton.setOnClickListener { renameSelectedRemoteEntry() }
        binding.deleteSelectedFileButton.setOnClickListener { deleteSelectedRemoteEntry() }
        binding.deleteMultiSelectedFilesButton.setOnClickListener { deleteMultiSelectedRemoteEntries() }

        binding.screenshotButton.setOnClickListener {
            sendAwaitedCommand("Screenshot", "screenshot") { payload ->
                val base64Image = payload.optString("imageBase64", "")
                if (base64Image.isBlank()) {
                    binding.resultText.text = "Screenshot alinamadi."
                    updateScreenshotActions()
                    return@sendAwaitedCommand
                }

                lastScreenshotBytes = Base64.decode(base64Image, Base64.DEFAULT)
                lastScreenshotFileName = "pc-screenshot-${System.currentTimeMillis()}.jpg"
                renderCurrentScreenshotPreview()
                binding.resultText.text = "Screenshot alindi: ${payload.optInt("width")} x ${payload.optInt("height")}"
            }
        }
        binding.openScreenshotButton.setOnClickListener { openScreenshotDialog() }
        binding.saveScreenshotButton.setOnClickListener { saveCurrentScreenshot() }
        binding.screenshotPreview.setOnClickListener { openScreenshotDialog() }
        binding.startLivePreviewButton.setOnClickListener { startLivePreview(LIVE_PREVIEW_PROFILE_ORIGINAL) }
        binding.startHdLivePreviewButton.setOnClickListener { startLivePreview(LIVE_PREVIEW_PROFILE_HD_1080) }
        binding.stopLivePreviewButton.setOnClickListener { stopLivePreview() }
    }

    private fun persistLocalConfig() {
        val previousWorkerUrl = store.workerUrl
        val normalizedWorkerUrl = normalizeWorkerUrl(binding.workerUrlInput.text.toString())
        if (normalizedWorkerUrl != previousWorkerUrl) {
            val hadExistingSession = store.ownerToken.isNotBlank() || store.pairedPcId.isNotBlank()
            setWorkerCapabilityState(null)
            clearPairingState(resetLogs = false)
            if (hadExistingSession) {
                appendLog("Worker URL degisti. Eski eslesme ve oturum bilgileri temizlendi.")
            }
        }
        store.workerUrl = normalizedWorkerUrl
        store.deviceName = binding.deviceNameInput.text.toString().ifBlank { Build.MODEL ?: "Android" }
        updateWorkerCapabilityStatusViews()
    }

    private fun maybeShowFirstRunPrompt() {
        if (store.hasSeenFirstRunPrompt || isFinishing || isDestroyed) {
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Ilk kullanim")
            .setMessage("Bu uygulamayi ilk defa mi kullaniyorsunuz?")
            .setCancelable(false)
            .setNegativeButton("Hayir") { _, _ ->
                store.hasSeenFirstRunPrompt = true
            }
            .setPositiveButton("Evet ilk defa kullaniyorum") { _, _ ->
                store.hasSeenFirstRunPrompt = true
                openGettingStartedGuide()
            }
            .show()
    }

    private fun openGettingStartedGuide() {
        val padding = (resources.displayMetrics.density * 18).roundToInt()
        val guideTextView = TextView(this).apply {
            text = GettingStartedGuide.buildGuideText()
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
            setLineSpacing(0f, 1.14f)
            setTextIsSelectable(true)
            setPadding(padding, padding, padding, padding)
        }

        val scrollView = ScrollView(this).apply {
            addView(
                guideTextView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Ilk kurulum rehberi")
            .setView(scrollView)
            .setPositiveButton("Kapat", null)
            .show()
    }

    private fun registerTokenIfPossible() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || store.fcmToken.isBlank()) {
            return
        }

        runInBackground {
            runCatching {
                api.registerToken(store.workerUrl, store.ownerToken, store.deviceName, store.fcmToken)
            }.onSuccess {
                runOnUiThread { appendLog("Token worker'a bildirildi.") }
            }.onFailure { error ->
                runOnUiThread { appendLog("Token guncellenemedi: ${friendlyErrorMessage(error.message, "Token guncellenemedi.")}") }
            }
        }
    }

    private fun refreshPcState() {
        persistLocalConfig()
        runInBackground {
            ensureOwnerTokenReady()
            val pcsJson = api.listPcs(store.workerUrl, store.ownerToken)
            val pcs = buildList {
                for (index in 0 until pcsJson.length()) {
                    val pc = pcsJson.optJSONObject(index) ?: continue
                    add(
                        RemotePcSummary(
                            id = pc.optString("id"),
                            name = pc.optString("name", "PC"),
                            status = pc.optString("status", "unknown"),
                            lastEventType = pc.optString("lastEventType", "-"),
                            lastSeenAt = pc.optLong("lastSeenAt", 0L),
                            platform = pc.optString("platform", "windows"),
                        ),
                    )
                }
            }

            if (pcs.isEmpty()) {
                runOnUiThread {
                    clearPairingState(resetLogs = false)
                    binding.statusText.text = "Eslestirilmis PC bulunamadi."
                    renderUsageSummaryPlaceholder()
                    renderPcSummary(emptyList())
                    appendLog("Hesaba bagli PC bulunamadi.")
                }
                return@runInBackground
            }

            val selectedPc = findPreferredPc(pcs)
            availablePcs = pcs
            store.pairedPcId = selectedPc.id
            store.pairedPcName = selectedPc.name

            runOnUiThread {
                renderPcSummary(pcs)
                updateSelectedPcDisplay(selectedPc.name, selectedPc.status)
                applySelectedPcScopedState(restoreLivePreview = true)
                binding.statusText.text = buildString {
                    append("${selectedPc.name} -> ${selectedPc.status}")
                    append(" | son olay: ${selectedPc.lastEventType}")
                    if (selectedPc.lastSeenAt > 0L) {
                        append('\n')
                        append("Son gorulme: ")
                        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(selectedPc.lastSeenAt))
                    }
                }
                refreshUsageSummary()
                loadNotificationSettings()
                loadNotificationCenter()
                appendLog("PC durumu guncellendi.")
            }
        }
    }

    private fun renderPcSummary(pcs: List<RemotePcSummary>) {
        availablePcs = pcs
        binding.pcListSummaryText.text = if (pcs.isEmpty()) {
            "Hesaba bagli PC bulunmuyor."
        } else {
            pcs.joinToString(separator = "\n") { pc ->
                val selectedLabel = if (pc.id == store.pairedPcId) " [SECILI]" else ""
                "${pc.name} • ${pc.status} • ${pc.lastEventType}$selectedLabel"
            }
        }
        binding.selectPcButton.isEnabled = pcs.size > 1
        binding.unpairButton.isEnabled = store.pairedPcId.isNotBlank()
    }

    private fun updateSelectedPcDisplay(name: String?, status: String?) {
        binding.selectedPcText.text = if (name.isNullOrBlank()) {
            "Secili PC: -"
        } else {
            "Secili PC: $name"
        }
        renderSelectedPcStatusBadge(status)
    }

    private fun renderSelectedPcStatusBadge(status: String?) {
        if (status.isNullOrBlank()) {
            binding.selectedPcStatusBadge.visibility = View.GONE
            return
        }

        val normalized = status.trim().lowercase()
        val (label, colorRes) = when {
            normalized.contains("online") || normalized.contains("connected") -> "Cevrim ici" to R.color.success_green
            normalized.contains("sleep") || normalized.contains("away") -> "Uykuda" to R.color.warning_amber
            normalized.contains("offline") || normalized.contains("disconnected") -> "Cevrim disi" to R.color.status_offline
            normalized.contains("starting") || normalized.contains("busy") -> "Hazirlaniyor" to R.color.warning_amber
            else -> status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } to R.color.surface_outline
        }

        val accentColor = ContextCompat.getColor(this, colorRes)
        binding.selectedPcStatusBadge.visibility = View.VISIBLE
        binding.selectedPcStatusBadge.text = label
        binding.selectedPcStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.selectedPcStatusBadge.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(999).toFloat()
            setColor(ColorUtils.setAlphaComponent(accentColor, 52))
            setStroke(dpToPx(1), accentColor)
        }
    }

    private fun showPcSelectionDialog() {
        if (availablePcs.isEmpty()) {
            showToast("Secilecek PC bulunamadi. Once durum yenile.")
            return
        }

        val labels = availablePcs.map { pc ->
            buildString {
                append(pc.name)
                append(" • ")
                append(pc.status)
                append(" • ")
                append(pc.lastEventType)
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Secili PC'yi degistir")
            .setItems(labels) { _, index ->
                val selected = availablePcs[index]
                store.pairedPcId = selected.id
                store.pairedPcName = selected.name
                updateSelectedPcDisplay(selected.name, selected.status)
                applySelectedPcScopedState(restoreLivePreview = true)
                renderPcSummary(availablePcs)
                appendLog("PC secildi: ${selected.name}")
                loadNotificationCenter()
                refreshUsageSummary()
            }
            .setNegativeButton("Kapat", null)
            .show()
    }

    private fun loadNotificationSettings() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            return
        }

        binding.notificationDisplayLimitInput.setText(currentNotificationDisplayLimit().toString())
        updateNotificationLimitLabels()
        runInBackground {
            val settings = api.getNotificationSettings(store.workerUrl, store.ownerToken)
            runOnUiThread {
                binding.startupNotificationSwitch.isChecked = settings.optBoolean("startupEnabled", true)
                binding.shutdownNotificationSwitch.isChecked = settings.optBoolean("shutdownEnabled", true)
                binding.agentStopNotificationSwitch.isChecked = settings.optBoolean("agentStopEnabled", true)
                binding.sleepNotificationSwitch.isChecked = settings.optBoolean("sleepEnabled", true)
                binding.wakeNotificationSwitch.isChecked = settings.optBoolean("wakeEnabled", true)
                binding.offlineNotificationSwitch.isChecked = settings.optBoolean("offlineEnabled", true)
                binding.commandFailedNotificationSwitch.isChecked = settings.optBoolean("commandFailedEnabled", true)
                binding.notificationSettingsText.text = "Bildirim ayarlari yuklendi."
            }
        }
    }

    private fun saveNotificationSettings() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            showToast("Once PC ile esles.")
            return
        }

        val displayLimit = parseNotificationDisplayLimit() ?: return
        store.notificationDisplayLimit = displayLimit
        updateNotificationLimitLabels()

        val settings = JSONObject()
            .put("startupEnabled", binding.startupNotificationSwitch.isChecked)
            .put("shutdownEnabled", binding.shutdownNotificationSwitch.isChecked)
            .put("agentStopEnabled", binding.agentStopNotificationSwitch.isChecked)
            .put("sleepEnabled", binding.sleepNotificationSwitch.isChecked)
            .put("wakeEnabled", binding.wakeNotificationSwitch.isChecked)
            .put("offlineEnabled", binding.offlineNotificationSwitch.isChecked)
            .put("commandFailedEnabled", binding.commandFailedNotificationSwitch.isChecked)

        runInBackground {
            api.updateNotificationSettings(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                settings = settings,
            )

            runOnUiThread {
                binding.notificationSettingsText.text = "Bildirim ayarlari kaydedildi."
                appendLog("Bildirim ayarlari guncellendi.")
                loadNotificationCenter()
            }
        }
    }

    private fun loadNotificationCenter() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            lastRenderedNotifications = emptyList()
            updateNotificationsTabBadge(0)
            updateNotificationLimitLabels()
            return
        }

        val displayLimit = currentNotificationDisplayLimit()
        runInBackground {
            val response = api.getNotificationCenter(store.workerUrl, store.ownerToken, limit = displayLimit)
            val unreadCount = response.optInt("unreadCount", 0)
            val notificationsJson = response.optJSONArray("notifications") ?: JSONArray()
            val notifications = buildList {
                for (index in 0 until notificationsJson.length()) {
                    val item = notificationsJson.optJSONObject(index) ?: continue
                    add(
                        RemoteNotificationItem(
                            id = item.optString("id"),
                            pcId = item.optString("pcId"),
                            type = item.optString("type", "system"),
                            title = item.optString("title"),
                            body = item.optString("body"),
                            isRead = item.optBoolean("isRead"),
                            createdAt = item.optLong("createdAt", 0L),
                        ),
                    )
                }
            }

            runOnUiThread {
                binding.notificationStatusText.text = "Son $displayLimit bildirim • okunmamis: $unreadCount"
                renderNotificationCenter(notifications.take(displayLimit), unreadCount)
            }
        }
    }

    private fun renderNotificationCenter(notifications: List<RemoteNotificationItem>, unreadCount: Int) {
        lastRenderedNotifications = notifications
        binding.notificationCenterContainer.removeAllViews()
        updateNotificationsTabBadge(unreadCount)
        updateNotificationLimitLabels()
        if (notifications.isEmpty()) {
            binding.notificationCenterContainer.addView(
                TextView(this).apply {
                    text = "Son bildirim yok."
                    setTextColor(getColor(R.color.text_secondary))
                },
            )
            return
        }

        notifications.forEach { notification ->
            val itemView = TextView(this).apply {
                setBackgroundResource(R.drawable.bg_section_outline)
                setPadding(24, 20, 24, 20)
                setTextColor(getColor(R.color.text_primary))
                text = buildString {
                    append(if (notification.isRead) "• " else "• YENI ")
                    append(notification.title)
                    append('\n')
                    append(notification.body)
                    if (notification.createdAt > 0L) {
                        append('\n')
                        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(notification.createdAt))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 8
                }
                setOnClickListener {
                    if (!notification.isRead) {
                        markNotificationRead(notification.id)
                    }
                }
            }
            binding.notificationCenterContainer.addView(itemView)
        }

        if (unreadCount > 0) {
            appendLog("Okunmamis bildirim sayisi: $unreadCount")
        }
    }

    private fun markNotificationRead(notificationId: String) {
        runInBackground {
            api.markNotificationRead(store.workerUrl, store.ownerToken, notificationId)
            runOnUiThread {
                loadNotificationCenter()
            }
        }
    }

    private fun markAllNotificationsRead() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            return
        }

        runInBackground {
            api.markAllNotificationsRead(store.workerUrl, store.ownerToken)
            runOnUiThread {
                binding.notificationStatusText.text = "Tum bildirimler okundu yapildi."
                loadNotificationCenter()
            }
        }
    }

    private fun unpairCurrentPc() {
        if (store.ownerToken.isBlank() || store.pairedPcId.isBlank()) {
            showToast("Kaldirilacak eslesme bulunamadi.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Eslesmeyi kaldir")
            .setMessage("${store.pairedPcName.ifBlank { "Bu PC" }} ile telefon eslesmesini kaldirmak istiyor musun?")
            .setPositiveButton("Kaldir") { _, _ ->
                persistLocalConfig()
                runInBackground {
                    val pc = api.unpairPc(store.workerUrl, store.ownerToken, store.pairedPcId)
                    runOnUiThread {
                        appendLog("${pc.optString("name", "PC")} eslesmesi telefondan kaldirildi.")
                        binding.statusText.text = "${pc.optString("name", "PC")} kaldirildi. Kalan cihazlar yenileniyor."
                        refreshPcState()
                        refreshUsageSummary()
                    }
                }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun loadProcessList(scope: String) {
        sendAwaitedCommand(
            label = if (scope == "all") "Tum islemleri listele" else "Acik uygulamalari listele",
            type = "process-list",
            payload = JSONObject().put("scope", scope),
        ) { payload ->
            val processesJson = payload.optJSONArray("processes") ?: JSONArray()
            val processes = buildList {
                for (index in 0 until processesJson.length()) {
                    val item = processesJson.optJSONObject(index) ?: continue
                    add(
                        RemoteProcessEntry(
                            processId = item.optInt("processId"),
                            processName = item.optString("processName"),
                            displayName = item.optString("displayName").ifBlank { item.optString("processName") },
                            windowTitle = item.optString("windowTitle"),
                            isForeground = item.optBoolean("isForeground"),
                            memoryMb = item.optDouble("memoryMb"),
                            iconBase64 = item.optString("iconBase64").ifBlank { null },
                        ),
                    )
                }
            }

            renderProcessEntries(processes, scope)
            if (processes.isEmpty()) {
                binding.resultText.text = if (scope == "all") {
                    "Listelenecek islem bulunamadi."
                } else {
                    "Acik uygulama bulunamadi."
                }
            }
        }
    }

    private fun renderProcessEntries(processes: List<RemoteProcessEntry>, scope: String) {
        binding.processListContainer.removeAllViews()
        updateSelectedProcess(null)

        if (processes.isEmpty()) {
            return
        }

        val header = Button(this).apply {
            isAllCaps = false
            isEnabled = false
            text = if (scope == "all") {
                "Tum islemler (${processes.size})"
            } else {
                "Acik / gorunen uygulamalar (${processes.size})"
            }
        }
        binding.processListContainer.addView(header)

        processes.forEach { process ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 8
                }
            }

            row.addView(
                ImageView(this).apply {
                    val iconSize = (resources.displayMetrics.density * PROCESS_ICON_SIZE_DP).roundToInt()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginEnd = 10
                    }
                    setImageDrawable(createProcessIconDrawable(process.iconBase64))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
            )

            val selectButton = Button(this).apply {
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = buildString {
                    append(process.displayName.ifBlank { process.processName })
                    append(" | ")
                    append(process.processName)
                    append(" | PID ")
                    append(process.processId)
                    append(" | ")
                    append(String.format("%.1f MB", process.memoryMb))
                    if (process.isForeground) {
                        append(" | ON PLAN")
                    }
                    if (process.windowTitle.isNotBlank() && process.windowTitle != process.displayName) {
                        append('\n')
                        append(process.windowTitle)
                    }
                }
                setOnClickListener {
                    updateSelectedProcess(process)
                    appendLog("Process secildi: ${process.processName} (${process.processId})")
                }
            }
            row.addView(selectButton)

            row.addView(
                Button(this).apply {
                    isAllCaps = false
                    text = "Kapat"
                    setOnClickListener {
                        updateSelectedProcess(process)
                        killSelectedProcess()
                    }
                },
            )

            binding.processListContainer.addView(row)
        }
    }

    private fun killSelectedProcess() {
        val processName = binding.killProcessInput.text.toString().trim().ifBlank { selectedProcessName.orEmpty() }
        val processId = selectedProcessId

        if (processName.isBlank()) {
            showToast("Once listeden bir process sec.")
            return
        }

        val payload = JSONObject().put("processName", processName)
        if (processId != null) {
            payload.put("processId", processId)
        }

        sendAwaitedCommand(
            label = "Uygulamayi kapat",
            type = "app-kill",
            payload = payload,
        )
    }

    private fun loadSystemInfo() {
        sendAwaitedCommand("Sistem bilgisi", "system-info") { payload ->
            val memory = payload.optJSONObject("memory") ?: JSONObject()
            val drives = payload.optJSONArray("drives") ?: JSONArray()
            val networks = payload.optJSONArray("networkAddresses") ?: JSONArray()
            binding.systemInfoText.text = buildString {
                append("Makine: ${payload.optString("machineName", "-")}\n")
                append("Kullanici: ${payload.optString("domainName", "")}\\${payload.optString("userName", "-")}\n")
                append("OS: ${payload.optString("osDescription", "-")}\n")
                append("Islemci cekirdegi: ${payload.optInt("processorCount", 0)}\n")
                append("RAM: ${formatFileSize(memory.optLong("usedBytes"))} / ${formatFileSize(memory.optLong("totalBytes"))}")
                append(" (${memory.optInt("memoryLoadPercent", 0)}%)\n")
                append("Surec sayisi: ${payload.optJSONObject("processes")?.optInt("total", 0) ?: 0}\n")
                append("IP'ler: ${if (networks.length() == 0) "-" else (0 until networks.length()).joinToString { networks.optString(it) }}\n")
                append("Diskler:\n")
                for (index in 0 until drives.length()) {
                    val drive = drives.optJSONObject(index) ?: continue
                    append("• ${drive.optString("name")} ${formatFileSize(drive.optLong("usedBytes"))} / ${formatFileSize(drive.optLong("totalBytes"))}\n")
                }
            }.trim()
        }
    }

    private fun readStoredShortcutItems(pcId: String? = selectedPcIdOrNull()): List<ShortcutItem> {
        return runCatching {
            val shortcuts = mutableListOf<ShortcutItem>()
            val json = JSONArray(store.getShortcutItemsJson(pcId))
            for (index in 0 until json.length()) {
                val item = json.optJSONObject(index) ?: continue
                val hotkeyKeysJson = item.optJSONArray("hotkeyKeys") ?: JSONArray()
                val hotkeyKeys = mutableListOf<String>()
                for (hotkeyIndex in 0 until hotkeyKeysJson.length()) {
                    hotkeyKeys += hotkeyKeysJson.optString(hotkeyIndex, "")
                }
                val normalizedHotkeyKeys = normalizedHotkeyKeys(hotkeyKeys)
                val target = item.optString("target", "")
                val type = normalizeStoredShortcutType(
                    type = item.optString("type", "application"),
                    target = target,
                    hotkeyKeys = normalizedHotkeyKeys,
                )
                shortcuts += ShortcutItem(
                    id = item.optString("id", "shortcut-$index"),
                    title = item.optString("title", "Kisayol"),
                    type = type,
                    target = target,
                    arguments = item.optString("arguments", ""),
                    iconBase64 = item.optString("iconBase64", "").ifBlank { null },
                    iconKind = item.optString("iconKind", "").ifBlank { defaultShortcutIconKind(type) },
                    accentId = item.optString("accentId", defaultShortcutAccentId(type))
                        .ifBlank { defaultShortcutAccentId(type) },
                    hotkeyKeys = normalizedHotkeyKeys,
                )
            }
            shortcuts
        }.getOrDefault(emptyList())
    }

    private fun persistShortcutItems(pcId: String? = selectedPcIdOrNull()) {
        val json = JSONArray()
        shortcutItems.forEach { item ->
            val effectiveType = effectiveShortcutType(item)
            json.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("type", effectiveType)
                    .put("target", item.target)
                    .put("arguments", item.arguments)
                    .put("iconBase64", item.iconBase64 ?: "")
                    .put("iconKind", item.iconKind ?: defaultShortcutIconKind(effectiveType))
                    .put("accentId", item.accentId)
                    .put("hotkeyKeys", JSONArray(item.hotkeyKeys))
            )
        }
        store.setShortcutItemsJson(pcId, json.toString())
    }

    private fun renderShortcutGrid() {
        if (binding.shortcutGrid.width <= 0) {
            binding.shortcutGrid.post { renderShortcutGrid() }
            return
        }

        updateShortcutReorderUi()
        val tileSize = resolveShortcutTileSize()
        binding.shortcutGrid.removeAllViews()
        binding.shortcutGrid.columnCount = 3
        shortcutItems.forEach { item ->
            binding.shortcutGrid.addView(createShortcutTile(item, tileSize))
        }
        binding.shortcutGrid.addView(createShortcutAddTile(tileSize))
        binding.shortcutsEmptyText.visibility = if (shortcutItems.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun createShortcutTile(item: ShortcutItem, tileSize: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = createShortcutTileBackground(item.accentId, item.id == activeShortcutId)
            layoutParams = createShortcutTileLayoutParams(tileSize)
            minimumHeight = tileSize
            minimumWidth = tileSize
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            isClickable = true
            isFocusable = true
            contentDescription = item.title
            attachShortcutTouchFeedback(this)
            setOnClickListener {
                if (isShortcutReorderMode) {
                    showToast("Siralama modunda karti basili tutup tasiyabilirsin.")
                } else {
                    runShortcutItem(item)
                }
            }
            setOnLongClickListener {
                if (isShortcutReorderMode) {
                    startShortcutDrag(this, item)
                } else {
                    showShortcutActions(item)
                    true
                }
            }
            setOnDragListener { view, event -> handleShortcutTileDrag(view, event, item) }

            addView(
                ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(SHORTCUT_ICON_SIZE_DP), dpToPx(SHORTCUT_ICON_SIZE_DP))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(createShortcutIconDrawable(item.iconBase64, resolveEffectiveShortcutIconKind(item)))
                },
            )

            addView(
                TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dpToPx(10)
                    }
                    gravity = Gravity.CENTER
                    maxLines = 2
                    text = item.title
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                },
            )
        }
    }

    private fun createShortcutAddTile(tileSize: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = if (isShortcutReorderMode) {
                createShortcutReorderButtonBackground(isActive = true)
            } else {
                AppCompatResources.getDrawable(this@MainActivity, R.drawable.bg_touchpad_surface)
            }
            layoutParams = createShortcutTileLayoutParams(tileSize)
            minimumHeight = tileSize
            minimumWidth = tileSize
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
            isClickable = true
            isFocusable = true
            contentDescription = if (isShortcutReorderMode) "Kisayolu sona tasi" else "Yeni kisayol ekle"
            attachShortcutTouchFeedback(this)
            setOnClickListener {
                if (isShortcutReorderMode) {
                    showToast("Bir karti bu alana birakirsan listenin sonuna tasinir.")
                } else {
                    showShortcutEditor()
                }
            }
            setOnDragListener { view, event -> handleShortcutAddTileDrag(view, event) }

            addView(
                ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(SHORTCUT_ICON_SIZE_DP), dpToPx(SHORTCUT_ICON_SIZE_DP))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageDrawable(AppCompatResources.getDrawable(this@MainActivity, R.drawable.ic_shortcut_add))
                },
            )

            addView(
                TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dpToPx(10)
                    }
                    gravity = Gravity.CENTER
                    text = if (isShortcutReorderMode) "Sona tasi" else "Ekle"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                },
            )
        }
    }

    private fun createShortcutTileLayoutParams(tileSize: Int): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = tileSize
            height = tileSize
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED)
            setMargins(
                dpToPx(SHORTCUT_TILE_MARGIN_DP),
                dpToPx(SHORTCUT_TILE_MARGIN_DP),
                dpToPx(SHORTCUT_TILE_MARGIN_DP),
                dpToPx(SHORTCUT_TILE_MARGIN_DP),
            )
        }
    }

    private fun createShortcutTileBackground(accentId: String, isActive: Boolean): Drawable {
        val accent = resolveShortcutAccentOption(accentId)
        val fillColor = ContextCompat.getColor(this, accent.fillColorRes)
        val strokeColor = ContextCompat.getColor(this, accent.strokeColorRes)
        val startColor = ColorUtils.blendARGB(fillColor, Color.WHITE, if (isActive) 0.12f else 0.04f)
        val endColor = ColorUtils.blendARGB(fillColor, ContextCompat.getColor(this, R.color.surface_background), if (isActive) 0.18f else 0.34f)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(startColor, endColor),
        ).apply {
            cornerRadius = dpToPx(22).toFloat()
            setStroke(dpToPx(if (isActive) 2 else 1), strokeColor)
        }
    }

    private fun createShortcutReorderButtonBackground(isActive: Boolean): Drawable {
        val fillColor = ContextCompat.getColor(this, R.color.shortcut_accent_violet_fill)
        val strokeColor = ContextCompat.getColor(this, R.color.shortcut_accent_violet_stroke)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.blendARGB(fillColor, Color.WHITE, if (isActive) 0.14f else 0.06f),
                ColorUtils.blendARGB(fillColor, ContextCompat.getColor(this, R.color.surface_background), if (isActive) 0.26f else 0.34f),
            ),
        ).apply {
            cornerRadius = dpToPx(18).toFloat()
            setStroke(dpToPx(if (isActive) 2 else 1), strokeColor)
        }
    }

    private fun createShortcutAccentSwatchBackground(accent: ShortcutAccentOption, isSelected: Boolean): Drawable {
        val fillColor = ContextCompat.getColor(this, accent.fillColorRes)
        val strokeColor = ContextCompat.getColor(this, accent.strokeColorRes)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                ColorUtils.blendARGB(fillColor, Color.WHITE, 0.08f),
                ColorUtils.blendARGB(fillColor, ContextCompat.getColor(this, R.color.surface_background), 0.28f),
            ),
        ).apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setStroke(dpToPx(if (isSelected) 2 else 1), strokeColor)
        }
    }

    private fun resolveShortcutAccentOption(accentId: String?): ShortcutAccentOption {
        return shortcutAccentOptions.firstOrNull { it.id == accentId } ?: shortcutAccentOptions.first()
    }

    private fun resolveShortcutTileSize(): Int {
        val totalWidth = binding.shortcutGrid.width.takeIf { it > 0 } ?: binding.contentRootLayout.width
        val marginPerTile = dpToPx(SHORTCUT_TILE_MARGIN_DP) * 2
        val availableWidth = (totalWidth - (marginPerTile * 3)).coerceAtLeast(dpToPx(78))
        return (availableWidth / 3f).roundToInt()
    }

    private fun updateShortcutReorderUi() {
        binding.shortcutReorderButton.background = createShortcutReorderButtonBackground(isShortcutReorderMode)
        binding.shortcutReorderButton.text = if (isShortcutReorderMode) "Siralama acik" else "Sirala"
        binding.shortcutsModeHintText.text = if (isShortcutReorderMode) {
            "Siralama modu acik. Bir karti basili tutup surukleyerek yerini degistir. Sona tasimak icin arti kartina birakabilirsin."
        } else {
            "Kartlara dokununca secili PC uzerinde calisir. Linkler varsayilan tarayicida, protokol linkleri destekleyen uygulamada acilir."
        }
    }

    private fun startShortcutDrag(view: View, item: ShortcutItem): Boolean {
        draggingShortcutId = item.id
        shortcutDropHandled = false
        view.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.45f).setDuration(90L).start()
        val dragData = ClipData.newPlainText("shortcut-id", item.id)
        val started = view.startDragAndDrop(dragData, View.DragShadowBuilder(view), item.id, 0)
        if (!started) {
            clearShortcutDragState(refreshGrid = true)
            showToast("Kisayol surukleme baslatilamadi.")
        }
        return started
    }

    private fun handleShortcutTileDrag(view: View, event: DragEvent, targetItem: ShortcutItem): Boolean {
        if (!isShortcutReorderMode) {
            return false
        }

        val draggedId = (event.localState as? String) ?: draggingShortcutId ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                if (draggedId != targetItem.id) {
                    applyShortcutDropTargetState(view, highlighted = true)
                }
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                applyShortcutDropTargetState(view, highlighted = false)
                true
            }
            DragEvent.ACTION_DROP -> {
                applyShortcutDropTargetState(view, highlighted = false)
                if (draggedId == targetItem.id) {
                    clearShortcutDragState(refreshGrid = true)
                    shortcutDropHandled = true
                    true
                } else {
                    val moved = moveShortcutItemBefore(draggedId, targetItem.id)
                    shortcutDropHandled = moved
                    clearShortcutDragState(refreshGrid = true)
                    if (moved) {
                        showToast("Kisayol sirasi guncellendi.")
                    }
                    moved
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                applyShortcutDropTargetState(view, highlighted = false)
                true
            }
            else -> true
        }
    }

    private fun handleShortcutAddTileDrag(view: View, event: DragEvent): Boolean {
        if (!isShortcutReorderMode) {
            return false
        }

        val draggedId = (event.localState as? String) ?: draggingShortcutId ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                applyShortcutDropTargetState(view, highlighted = true)
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                applyShortcutDropTargetState(view, highlighted = false)
                true
            }
            DragEvent.ACTION_DROP -> {
                applyShortcutDropTargetState(view, highlighted = false)
                val moved = moveShortcutItemToEnd(draggedId)
                shortcutDropHandled = moved
                clearShortcutDragState(refreshGrid = true)
                if (moved) {
                    showToast("Kisayol listenin sonuna tasindi.")
                }
                moved
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                applyShortcutDropTargetState(view, highlighted = false)
                true
            }
            else -> true
        }
    }

    private fun handleShortcutGridDrag(event: DragEvent): Boolean {
        if (!isShortcutReorderMode) {
            return false
        }

        val draggedId = (event.localState as? String) ?: draggingShortcutId ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                if (shortcutDropHandled) {
                    true
                } else {
                    val moved = moveShortcutItemToEnd(draggedId)
                    shortcutDropHandled = moved
                    clearShortcutDragState(refreshGrid = true)
                    if (moved) {
                        showToast("Kisayol listenin sonuna tasindi.")
                    }
                    moved
                }
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                val shouldRefresh = draggingShortcutId != null
                shortcutDropHandled = false
                if (shouldRefresh) {
                    clearShortcutDragState(refreshGrid = true)
                }
                true
            }
            else -> true
        }
    }

    private fun applyShortcutDropTargetState(view: View, highlighted: Boolean) {
        view.animate()
            .scaleX(if (highlighted) 1.04f else 1f)
            .scaleY(if (highlighted) 1.04f else 1f)
            .alpha(if (highlighted) 0.92f else 1f)
            .setDuration(100L)
            .start()
    }

    private fun moveShortcutItemBefore(draggedId: String, targetId: String): Boolean {
        val sourceIndex = shortcutItems.indexOfFirst { it.id == draggedId }
        val targetIndex = shortcutItems.indexOfFirst { it.id == targetId }
        if (sourceIndex == -1 || targetIndex == -1 || sourceIndex == targetIndex) {
            return false
        }

        val movedItem = shortcutItems.removeAt(sourceIndex)
        val insertIndex = if (sourceIndex < targetIndex) targetIndex - 1 else targetIndex
        shortcutItems.add(insertIndex.coerceIn(0, shortcutItems.size), movedItem)
        persistShortcutItems()
        appendLog("Kisayol sirasi guncellendi: ${movedItem.title}")
        return true
    }

    private fun moveShortcutItemToEnd(draggedId: String): Boolean {
        val sourceIndex = shortcutItems.indexOfFirst { it.id == draggedId }
        if (sourceIndex == -1 || sourceIndex == shortcutItems.lastIndex) {
            return false
        }

        val movedItem = shortcutItems.removeAt(sourceIndex)
        shortcutItems.add(movedItem)
        persistShortcutItems()
        appendLog("Kisayol sona tasindi: ${movedItem.title}")
        return true
    }

    private fun clearShortcutDragState(refreshGrid: Boolean) {
        draggingShortcutId = null
        if (refreshGrid) {
            renderShortcutGrid()
        }
    }

    private fun attachShortcutTouchFeedback(view: View) {
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).alpha(0.84f).setDuration(90L).start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120L).start()
                }
            }
            false
        }
    }

    private fun createShortcutIconDrawable(iconBase64: String?, iconKind: String?): Drawable? {
        val iconSize = dpToPx(SHORTCUT_ICON_SIZE_DP)
        val drawable = runCatching {
            if (iconBase64.isNullOrBlank()) {
                AppCompatResources.getDrawable(this, resolveShortcutIconResId(iconKind))
            } else {
                val bytes = Base64.decode(iconBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                BitmapDrawable(resources, bitmap)
            }
        }.getOrNull() ?: AppCompatResources.getDrawable(this, resolveShortcutIconResId(iconKind))

        drawable?.setBounds(0, 0, iconSize, iconSize)
        return drawable
    }

    private fun resolveEffectiveShortcutIconKind(item: ShortcutItem): String {
        if (!item.iconBase64.isNullOrBlank()) {
            return item.iconKind ?: "app"
        }

        return when (effectiveShortcutType(item)) {
            "folder" -> if (isDriveLikePath(item.target)) "drive" else "folder"
            "url" -> "link"
            "cmd", "powershell" -> "terminal"
            "run" -> "run"
            "hotkey" -> "hotkey"
            else -> item.iconKind ?: "app"
        }
    }

    private fun isDriveLikePath(path: String): Boolean {
        val trimmed = path.trim().trim('"').replace('/', '\\')
        return trimmed.matches(Regex("^[A-Za-z]:\\\\?$"))
    }

    private fun resolveShortcutIconResId(iconKind: String?): Int {
        return when (iconKind?.lowercase()) {
            "drive" -> R.drawable.ic_shortcut_drive
            "folder" -> R.drawable.ic_shortcut_folder
            "link" -> R.drawable.ic_shortcut_link
            "terminal" -> R.drawable.ic_shortcut_terminal
            "run" -> R.drawable.ic_shortcut_run
            "hotkey" -> R.drawable.ic_shortcut_hotkey
            else -> R.drawable.ic_shortcut_app
        }
    }

    private fun showShortcutActions(item: ShortcutItem) {
        val padding = dpToPx(18)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(this@MainActivity, R.drawable.bg_section_outline)
            setPadding(padding, padding, padding, padding)
        }
        val titleText = TextView(this).apply {
            text = item.title
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val itemType = effectiveShortcutType(item)
        val subtitleText = TextView(this).apply {
            text = buildString {
                append(shortcutTypeOptions.firstOrNull { it.id == itemType }?.label ?: itemType)
                val detail = describeShortcutTarget(item)
                if (detail.isNotBlank()) {
                    append('\n')
                    append(detail)
                }
            }
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            setLineSpacing(0f, 1.1f)
        }
        container.addView(titleText)
        container.addView(subtitleText)

        val rowOne = createShortcutActionRow()
        val rowTwo = createShortcutActionRow()
        container.addView(rowOne)
        container.addView(rowTwo)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setNegativeButton("Kapat", null)
            .create()

        rowOne.addView(createShortcutActionButton("Calistir") {
            dialog.dismiss()
            runShortcutItem(item)
        })
        rowOne.addView(createShortcutActionButton("Duzenle", withMarginStart = true) {
            dialog.dismiss()
            showShortcutEditor(item)
        })
        rowTwo.addView(createShortcutActionButton("Ikonu yenile") {
            dialog.dismiss()
            refreshShortcutIcon(item)
        })
        rowTwo.addView(createShortcutActionButton("Sil", withMarginStart = true) {
            dialog.dismiss()
            confirmShortcutDeletion(item)
        })

        dialog.show()
    }

    private fun createShortcutActionRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(12)
            }
        }
    }

    private fun createShortcutActionButton(
        text: String,
        withMarginStart: Boolean = false,
        onClick: () -> Unit,
    ): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            background = AppCompatResources.getDrawable(this@MainActivity, R.drawable.bg_touchpad_surface)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (withMarginStart) {
                    marginStart = dpToPx(10)
                }
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showShortcutEditor(existing: ShortcutItem? = null) {
        val padding = dpToPx(18)
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val titleInput = EditText(this).apply {
            hint = "Kisayol adi"
            setText(existing?.title.orEmpty())
        }
        val typeSpinner = Spinner(this)
        val helperText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val targetInput = EditText(this)
        val argumentsInput = EditText(this).apply {
            hint = "Opsiyonel argumanlar"
        }
        val hotkeyModifierHeading = TextView(this).apply {
            text = "Modifier tuslari"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val hotkeyModifierContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val hotkeyPrimaryHeading = TextView(this).apply {
            text = "Ana tus"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val hotkeyPrimarySpinner = Spinner(this)
        val hotkeyPreviewText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val existingEffectiveType = existing?.let(::effectiveShortcutType) ?: shortcutTypeOptions.first().id
        var selectedAccentId = existing?.accentId ?: defaultShortcutAccentId(existingEffectiveType)
        val existingHotkeyKeys = normalizedHotkeyKeys(existing?.hotkeyKeys.orEmpty())
        val selectedHotkeyModifiers = existingHotkeyKeys.filterTo(linkedSetOf()) { it in hotkeyModifierIdSet }
        var selectedHotkeyPrimary = existingHotkeyKeys.firstOrNull { it !in hotkeyModifierIdSet }.orEmpty()
        val accentHeading = TextView(this).apply {
            text = "Kart rengi"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val accentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val accentHint = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val browseButton = Button(this).apply {
            text = "PC'den sec"
        }

        dialogLayout.addView(titleInput)
        dialogLayout.addView(typeSpinner)
        dialogLayout.addView(helperText)
        dialogLayout.addView(hotkeyModifierHeading)
        dialogLayout.addView(hotkeyModifierContainer)
        dialogLayout.addView(hotkeyPrimaryHeading)
        dialogLayout.addView(hotkeyPrimarySpinner)
        dialogLayout.addView(hotkeyPreviewText)
        dialogLayout.addView(targetInput)
        dialogLayout.addView(argumentsInput)
        dialogLayout.addView(accentHeading)
        dialogLayout.addView(accentRow)
        dialogLayout.addView(accentHint)
        dialogLayout.addView(browseButton)

        val scrollView = ScrollView(this).apply {
            addView(
                dialogLayout,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            shortcutTypeOptions.map { it.label },
        )
        typeSpinner.adapter = adapter
        hotkeyPrimarySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Ana tus sec") + hotkeyPrimaryOptions.map { it.label },
        )

        fun currentHotkeyKeys(): List<String> {
            val keys = mutableListOf<String>()
            hotkeyModifierOptions.forEach { option ->
                if (option.id in selectedHotkeyModifiers) {
                    keys += option.id
                }
            }
            if (selectedHotkeyPrimary.isNotBlank()) {
                keys += selectedHotkeyPrimary
            }
            return normalizedHotkeyKeys(keys)
        }

        fun updateHotkeyPreview() {
            val keys = currentHotkeyKeys()
            val blocked = isBlockedHotkeyCombination(keys)
            hotkeyPreviewText.setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (blocked) R.color.warning_amber else R.color.text_secondary,
                ),
            )
            hotkeyPreviewText.text = when {
                keys.isEmpty() -> "Bir kombinasyon sec. Ornek: Left Ctrl + Right Alt + Q"
                blocked -> "Bu kombinasyon desteklenmez: ${formatHotkeyDisplayName(keys)}"
                else -> "Secili kombinasyon: ${formatHotkeyDisplayName(keys)}"
            }
        }

        hotkeyModifierOptions.chunked(2).forEach { rowOptions ->
            hotkeyModifierContainer.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    rowOptions.forEachIndexed { index, option ->
                        addView(
                            androidx.appcompat.widget.AppCompatCheckBox(this@MainActivity).apply {
                                text = option.label
                                isChecked = option.id in selectedHotkeyModifiers
                                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                    if (index > 0) {
                                        marginStart = dpToPx(8)
                                    }
                                    topMargin = dpToPx(8)
                                }
                                setOnCheckedChangeListener { _, isChecked ->
                                    if (isChecked) {
                                        selectedHotkeyModifiers += option.id
                                    } else {
                                        selectedHotkeyModifiers -= option.id
                                    }
                                    updateHotkeyPreview()
                                }
                            },
                        )
                    }
                },
            )
        }

        fun applyShortcutType(option: ShortcutTypeOption) {
            helperText.text = option.helperText
            targetInput.hint = option.targetHint
            targetInput.inputType = when (option.id) {
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                else -> InputType.TYPE_CLASS_TEXT
            }
            val isHotkey = option.id == "hotkey"
            hotkeyModifierHeading.visibility = if (isHotkey) View.VISIBLE else View.GONE
            hotkeyModifierContainer.visibility = if (isHotkey) View.VISIBLE else View.GONE
            hotkeyPrimaryHeading.visibility = if (isHotkey) View.VISIBLE else View.GONE
            hotkeyPrimarySpinner.visibility = if (isHotkey) View.VISIBLE else View.GONE
            hotkeyPreviewText.visibility = if (isHotkey) View.VISIBLE else View.GONE
            targetInput.visibility = if (isHotkey) View.GONE else View.VISIBLE
            argumentsInput.visibility = if (option.supportsArguments && !isHotkey) View.VISIBLE else View.GONE
            browseButton.visibility = if (option.pickerMode == null) View.GONE else View.VISIBLE
            browseButton.text = when (option.pickerMode) {
                ShortcutPickerMode.APPLICATION -> "PC'den EXE / kisayol sec"
                ShortcutPickerMode.FOLDER -> "PC'den klasor sec"
                null -> "PC'den sec"
            }
            updateHotkeyPreview()
        }

        fun renderAccentPicker() {
            accentRow.removeAllViews()
            shortcutAccentOptions.forEachIndexed { index, accent ->
                accentRow.addView(
                    TextView(this).apply {
                        gravity = Gravity.CENTER
                        minHeight = dpToPx(42)
                        text = if (accent.id == selectedAccentId) "✓" else ""
                        textSize = 14f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                        background = createShortcutAccentSwatchBackground(accent, accent.id == selectedAccentId)
                        layoutParams = LinearLayout.LayoutParams(0, dpToPx(42), 1f).apply {
                            if (index > 0) {
                                marginStart = dpToPx(8)
                            }
                            topMargin = dpToPx(10)
                        }
                        setOnClickListener {
                            selectedAccentId = accent.id
                            renderAccentPicker()
                        }
                    },
                )
            }

            accentHint.text = "Secili renk: ${resolveShortcutAccentOption(selectedAccentId).label}"
        }

        val initialIndex = shortcutTypeOptions.indexOfFirst { it.id == existingEffectiveType }.takeIf { it >= 0 } ?: 0
        typeSpinner.setSelection(initialIndex)
        targetInput.setText(existing?.target.orEmpty())
        argumentsInput.setText(existing?.arguments.orEmpty())
        val initialHotkeyIndex = hotkeyPrimaryOptions.indexOfFirst { it.id == selectedHotkeyPrimary }
        hotkeyPrimarySpinner.setSelection(if (initialHotkeyIndex >= 0) initialHotkeyIndex + 1 else 0)
        applyShortcutType(shortcutTypeOptions[initialIndex])
        renderAccentPicker()
        updateHotkeyPreview()

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyShortcutType(shortcutTypeOptions[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        hotkeyPrimarySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedHotkeyPrimary = if (position <= 0) {
                    ""
                } else {
                    hotkeyPrimaryOptions[position - 1].id
                }
                updateHotkeyPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Kisayol ekle" else "Kisayolu duzenle")
            .setView(scrollView)
            .setNegativeButton("Iptal", null)
            .setPositiveButton(if (existing == null) "Kaydet" else "Guncelle", null)
            .create()

        browseButton.setOnClickListener {
            val option = shortcutTypeOptions.getOrElse(typeSpinner.selectedItemPosition) { shortcutTypeOptions.first() }
            val pickerMode = option.pickerMode ?: return@setOnClickListener
            openRemotePathPicker(pickerMode) { selectedPath ->
                targetInput.setText(selectedPath)
                if (titleInput.text.toString().trim().isBlank()) {
                    titleInput.setText(suggestShortcutTitle(option.id, selectedPath))
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { positiveView ->
                val option = shortcutTypeOptions.getOrElse(typeSpinner.selectedItemPosition) { shortcutTypeOptions.first() }
                val hotkeyKeys = if (option.id == "hotkey") currentHotkeyKeys() else emptyList()
                if (option.id == "hotkey" && hotkeyKeys.none { it !in hotkeyModifierIdSet }) {
                    showToast("Hotkey icin bir ana tus sec.")
                    return@setOnClickListener
                }
                if (option.id == "hotkey" && isBlockedHotkeyCombination(hotkeyKeys)) {
                    showToast("Ctrl + Alt + Delete desteklenmiyor.")
                    return@setOnClickListener
                }

                val target = if (option.id == "hotkey") "" else targetInput.text.toString().trim()
                if (option.id != "hotkey" && target.isBlank()) {
                    showToast("Kisayol hedefi bos olamaz.")
                    return@setOnClickListener
                }

                val shortcutType = normalizeStoredShortcutType(option.id, target, hotkeyKeys)
                val shortcut = ShortcutItem(
                    id = existing?.id ?: "shortcut-${System.currentTimeMillis()}",
                    title = titleInput.text.toString().trim().ifBlank { suggestShortcutTitle(shortcutType, target, hotkeyKeys) },
                    type = shortcutType,
                    target = target,
                    arguments = if (option.supportsArguments && option.id != "hotkey") argumentsInput.text.toString().trim() else "",
                    iconBase64 = if (shortcutType == "hotkey") null else existing?.iconBase64,
                    iconKind = if (shortcutType == "hotkey") defaultShortcutIconKind(shortcutType) else existing?.iconKind ?: defaultShortcutIconKind(shortcutType),
                    accentId = selectedAccentId,
                    hotkeyKeys = hotkeyKeys,
                )

                positiveView.isEnabled = false
                saveShortcutItem(shortcut, dialog, positiveView)
            }
        }

        dialog.show()
    }

    private fun saveShortcutItem(shortcut: ShortcutItem, dialog: AlertDialog? = null, triggerView: View? = null) {
        val shortcutType = effectiveShortcutType(shortcut)
        if (!canUseShortcutCommands(showFeedback = false)) {
            upsertShortcutItem(shortcut.copy(type = shortcutType, hotkeyKeys = normalizedHotkeyKeys(shortcut.hotkeyKeys)))
            dialog?.dismiss()
            triggerView?.isEnabled = true
            return
        }

        executor.execute {
            val inspectedResult = runCatching {
                val command = api.sendCommandAndAwaitResult(
                    workerUrl = store.workerUrl,
                    ownerToken = store.ownerToken,
                    pcId = requirePcId(),
                    type = "shortcut-inspect",
                    payload = JSONObject()
                        .put("shortcutType", shortcutType)
                        .put("target", shortcut.target)
                        .put("hotkeyKeys", JSONArray(shortcut.hotkeyKeys)),
                )
                parseCommandResult(command)
            }.getOrNull()

            val updatedShortcut = if (inspectedResult?.success == true) {
                val resolvedType = normalizeStoredShortcutType(
                    type = inspectedResult.payload.optString("shortcutType", shortcut.type).ifBlank { shortcut.type },
                    target = shortcut.target,
                    hotkeyKeys = shortcut.hotkeyKeys,
                )
                val resolvedTitle = if (resolvedType == "url") {
                    inspectedResult.payload.optString("displayName", shortcut.title).ifBlank { shortcut.title }
                } else {
                    shortcut.title
                }
                shortcut.copy(
                    title = resolvedTitle,
                    type = resolvedType,
                    iconBase64 = inspectedResult.payload.optString("iconBase64", "").ifBlank { null },
                    iconKind = inspectedResult.payload.optString("iconKind", defaultShortcutIconKind(resolvedType))
                        .ifBlank { defaultShortcutIconKind(resolvedType) },
                    hotkeyKeys = normalizedHotkeyKeys(shortcut.hotkeyKeys),
                )
            } else {
                shortcut.copy(
                    type = shortcutType,
                    iconBase64 = null,
                    iconKind = defaultShortcutIconKind(shortcutType),
                    hotkeyKeys = normalizedHotkeyKeys(shortcut.hotkeyKeys),
                )
            }

            runOnUiThread {
                upsertShortcutItem(updatedShortcut)
                dialog?.dismiss()
                triggerView?.isEnabled = true
                if (inspectedResult?.success == false) {
                    appendLog(
                        "Kisayol kaydedildi ama ikon bilgisi alinamadi: ${
                            friendlyErrorMessage(inspectedResult.error, "bilinmeyen hata")
                        }",
                    )
                }
            }
        }
    }

    private fun upsertShortcutItem(shortcut: ShortcutItem) {
        val existingIndex = shortcutItems.indexOfFirst { it.id == shortcut.id }
        if (existingIndex >= 0) {
            shortcutItems[existingIndex] = shortcut
        } else {
            shortcutItems.add(shortcut)
        }

        persistShortcutItems()
        renderShortcutGrid()
        appendLog("Kisayol hazir: ${shortcut.title}")
    }

    private fun confirmShortcutDeletion(item: ShortcutItem) {
        AlertDialog.Builder(this)
            .setTitle("Kisayolu sil")
            .setMessage("${item.title} kaldirilsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                val removed = shortcutItems.removeAll { it.id == item.id }
                activeShortcutId = activeShortcutId.takeUnless { it == item.id }
                if (!removed) {
                    showToast("Kisayol listede bulunamadi.")
                    return@setPositiveButton
                }

                persistShortcutItems()
                renderShortcutGrid()
                appendLog("Kisayol silindi: ${item.title}")
                showToast("${item.title} kaldirildi.")
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun refreshShortcutIcon(item: ShortcutItem) {
        if (!canUseShortcutCommands(showFeedback = true)) {
            return
        }

        executor.execute {
            val inspected = runCatching {
                val command = api.sendCommandAndAwaitResult(
                    workerUrl = store.workerUrl,
                    ownerToken = store.ownerToken,
                    pcId = requirePcId(),
                    type = "shortcut-inspect",
                    payload = JSONObject()
                        .put("shortcutType", effectiveShortcutType(item))
                        .put("target", item.target)
                        .put("hotkeyKeys", JSONArray(item.hotkeyKeys)),
                )
                parseCommandResult(command)
            }.getOrElse { error ->
                runOnUiThread {
                    showToast(friendlyErrorMessage(error.message, "Kisayol ikonu yenilenemedi."))
                }
                return@execute
            }

            runOnUiThread {
                if (!inspected.success) {
                    showToast(friendlyErrorMessage(inspected.error, "Kisayol ikonu yenilenemedi."))
                    return@runOnUiThread
                }

                val itemType = effectiveShortcutType(item)
                upsertShortcutItem(
                    item.copy(
                        title = if (itemType == "url") {
                            inspected.payload.optString("displayName", item.title).ifBlank { item.title }
                        } else {
                            item.title
                        },
                        type = normalizeStoredShortcutType(
                            type = inspected.payload.optString("shortcutType", item.type).ifBlank { item.type },
                            target = item.target,
                            hotkeyKeys = item.hotkeyKeys,
                        ),
                        iconBase64 = inspected.payload.optString("iconBase64", "").ifBlank { null },
                        iconKind = inspected.payload.optString("iconKind", defaultShortcutIconKind(itemType))
                            .ifBlank { defaultShortcutIconKind(itemType) },
                        hotkeyKeys = normalizedHotkeyKeys(item.hotkeyKeys),
                    ),
                )
            }
        }
    }

    private fun runShortcutItem(item: ShortcutItem) {
        if (!canUseShortcutCommands(showFeedback = true)) {
            return
        }

        activeShortcutId = item.id
        renderShortcutGrid()
        binding.resultText.text = "${item.title} -> gonderiliyor..."
        showToast("${item.title} gonderiliyor")
        persistLocalConfig()

        executor.execute {
            val outcome = runCatching {
                val command = api.sendCommandAndAwaitResult(
                    workerUrl = store.workerUrl,
                    ownerToken = store.ownerToken,
                    pcId = requirePcId(),
                    type = "shortcut-run",
                    payload = JSONObject()
                        .put("shortcutType", effectiveShortcutType(item))
                        .put("target", item.target)
                        .put("arguments", item.arguments)
                        .put("title", item.title)
                        .put("hotkeyKeys", JSONArray(item.hotkeyKeys)),
                )
                parseCommandResult(command)
            }

            runOnUiThread {
                activeShortcutId = null
                renderShortcutGrid()
                val result = outcome.getOrElse { error ->
                    val message = friendlyErrorMessage(error.message)
                    appendLog("${item.title} hatasi: $message")
                    showToast(message)
                    return@runOnUiThread
                }

                renderCommandSummary("${item.title} calistir", result)
                refreshUsageSummary()
                if (result.success) {
                    appendLog("${item.title} basarili.")
                } else {
                    val message = friendlyErrorMessage(result.error, "${item.title} basarisiz.")
                    appendLog("${item.title} hatasi: $message")
                    showToast(message)
                }
            }
        }
    }

    private fun openRemotePathPicker(mode: ShortcutPickerMode, onSelected: (String) -> Unit) {
        if (!canUseShortcutCommands(showFeedback = true)) {
            return
        }

        val padding = dpToPx(16)
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val pathText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
        }
        val statusText = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        val entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rootsButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = "Kokler"
        }
        val upButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
            }
            text = "Ust"
        }
        val chooseCurrentButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(8)
            }
            text = "Bu klasoru sec"
            visibility = if (mode == ShortcutPickerMode.FOLDER) View.VISIBLE else View.GONE
        }
        actionsRow.addView(rootsButton)
        actionsRow.addView(upButton)
        actionsRow.addView(chooseCurrentButton)

        dialogLayout.addView(pathText)
        dialogLayout.addView(statusText)
        dialogLayout.addView(actionsRow)
        dialogLayout.addView(
            ScrollView(this).apply {
                addView(
                    entriesContainer,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (mode == ShortcutPickerMode.APPLICATION) "PC'den uygulama veya kisayol sec" else "PC'den klasor sec")
            .setView(dialogLayout)
            .setNegativeButton("Kapat", null)
            .create()

        var currentPath = currentRemotePath.ifBlank { binding.filesPathInput.text.toString().trim() }
        var currentParentPath: String? = null

        fun loadPath(path: String) {
            pathText.text = if (path.isBlank()) "Konum: Kokler" else "Konum: $path"
            statusText.text = "Yukleniyor..."
            entriesContainer.removeAllViews()
            rootsButton.isEnabled = false
            upButton.isEnabled = false
            chooseCurrentButton.isEnabled = false

            executor.execute {
                val result = runCatching {
                    val command = api.sendCommandAndAwaitResult(
                        workerUrl = store.workerUrl,
                        ownerToken = store.ownerToken,
                        pcId = requirePcId(),
                        type = "files-list",
                        payload = JSONObject().put("path", path),
                    )
                    parseCommandResult(command)
                }.getOrElse { error ->
                    runOnUiThread {
                        if (!dialog.isShowing) {
                            return@runOnUiThread
                        }
                        statusText.text = friendlyErrorMessage(error.message, "Yol okunamadi.")
                        rootsButton.isEnabled = true
                        upButton.isEnabled = true
                        chooseCurrentButton.isEnabled = mode == ShortcutPickerMode.FOLDER && currentPath.isNotBlank()
                    }
                    return@execute
                }

                runOnUiThread {
                    if (!dialog.isShowing) {
                        return@runOnUiThread
                    }
                    if (!result.success) {
                        statusText.text = friendlyErrorMessage(result.error, "Yol okunamadi.")
                        rootsButton.isEnabled = true
                        upButton.isEnabled = true
                        chooseCurrentButton.isEnabled = mode == ShortcutPickerMode.FOLDER && currentPath.isNotBlank()
                        return@runOnUiThread
                    }

                    currentPath = result.payload.optString("path", "")
                    currentParentPath = result.payload.optString("parentPath", "").ifBlank { null }
                    val entries = mutableListOf<RemoteEntry>()
                    val entriesJson = result.payload.optJSONArray("entries") ?: JSONArray()
                    for (index in 0 until entriesJson.length()) {
                        val entry = entriesJson.optJSONObject(index) ?: continue
                        entries += RemoteEntry(
                            name = entry.optString("name", "Isimsiz"),
                            fullPath = entry.optString("fullPath", ""),
                            isDirectory = entry.optBoolean("isDirectory", false),
                            size = entry.optLong("size", 0L),
                            modifiedAt = entry.optLong("modifiedAt", 0L),
                        )
                    }

                    pathText.text = if (currentPath.isBlank()) "Konum: Kokler" else "Konum: $currentPath"
                    rootsButton.isEnabled = true
                    upButton.isEnabled = currentParentPath != null || currentPath.isNotBlank()
                    chooseCurrentButton.isEnabled = mode == ShortcutPickerMode.FOLDER && currentPath.isNotBlank()
                    entriesContainer.removeAllViews()

                    val visibleEntries = entries.filter { entry ->
                        when (mode) {
                            ShortcutPickerMode.APPLICATION -> entry.isDirectory || isLaunchableFile(entry.fullPath)
                            ShortcutPickerMode.FOLDER -> entry.isDirectory
                        }
                    }

                    if (visibleEntries.isEmpty()) {
                        statusText.text = "Secilebilir oge bulunamadi."
                        return@runOnUiThread
                    }

                    statusText.text = "${visibleEntries.size} oge bulundu."
                    visibleEntries.forEach { entry ->
                        if (mode == ShortcutPickerMode.FOLDER && entry.isDirectory) {
                            entriesContainer.addView(
                                LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    layoutParams = LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ).apply {
                                        topMargin = dpToPx(8)
                                    }

                                    addView(
                                        ImageView(this@MainActivity).apply {
                                            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                                                marginEnd = dpToPx(8)
                                            }
                                            scaleType = ImageView.ScaleType.FIT_CENTER
                                            setImageDrawable(
                                                AppCompatResources.getDrawable(
                                                    this@MainActivity,
                                                    if (isDriveLikePath(entry.fullPath)) R.drawable.ic_shortcut_drive else R.drawable.ic_shortcut_folder,
                                                ),
                                            )
                                        },
                                    )

                                    addView(
                                        Button(this@MainActivity).apply {
                                            isAllCaps = false
                                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                            text = "[Klasor] ${entry.name}"
                                            setOnClickListener { loadPath(entry.fullPath) }
                                        },
                                    )
                                    addView(
                                        Button(this@MainActivity).apply {
                                            isAllCaps = false
                                            layoutParams = LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                            ).apply {
                                                marginStart = dpToPx(8)
                                            }
                                            text = "Sec"
                                            setOnClickListener {
                                                onSelected(entry.fullPath)
                                                dialog.dismiss()
                                            }
                                        },
                                    )
                                },
                            )
                        } else {
                            entriesContainer.addView(
                                Button(this).apply {
                                    isAllCaps = false
                                    text = if (entry.isDirectory) "[Klasor] ${entry.name}" else entry.name
                                    setOnClickListener {
                                        if (entry.isDirectory) {
                                            loadPath(entry.fullPath)
                                        } else {
                                            onSelected(entry.fullPath)
                                            dialog.dismiss()
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        rootsButton.setOnClickListener { loadPath("") }
        upButton.setOnClickListener { loadPath(currentParentPath.orEmpty()) }
        chooseCurrentButton.setOnClickListener {
            if (currentPath.isBlank()) {
                showToast("Once bir klasor ac.")
                return@setOnClickListener
            }
            onSelected(currentPath)
            dialog.dismiss()
        }

        dialog.show()
        loadPath(currentPath)
    }

    private fun suggestShortcutTitle(type: String, target: String, hotkeyKeys: List<String> = emptyList()): String {
        if (type == "hotkey") {
            return formatHotkeyDisplayName(hotkeyKeys).ifBlank { "Tus kombinasyonu" }
        }

        if (target.isBlank()) {
            return "Kisayol"
        }

        return when (type) {
            "application" -> target.substringAfterLast('\\').substringAfterLast('/').substringBeforeLast('.').ifBlank { target }
            "folder" -> target.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/').ifBlank { target }
            "url" -> runCatching { Uri.parse(target).host }.getOrNull().orEmpty().ifBlank { "Link" }
            "cmd" -> "CMD komutu"
            "powershell" -> "PowerShell komutu"
            "run" -> "Calistir"
            "hotkey" -> formatHotkeyDisplayName(hotkeyKeys).ifBlank { "Tus kombinasyonu" }
            else -> "Kisayol"
        }
    }

    private fun defaultShortcutIconKind(type: String): String {
        return when (type) {
            "folder" -> "folder"
            "url" -> "link"
            "cmd", "powershell" -> "terminal"
            "run" -> "run"
            "hotkey" -> "hotkey"
            else -> "app"
        }
    }

    private fun defaultShortcutAccentId(type: String): String {
        return when (type) {
            "folder" -> "emerald"
            "url" -> "aqua"
            "cmd", "powershell" -> "amber"
            "run" -> "coral"
            "hotkey" -> "slate"
            else -> DEFAULT_SHORTCUT_ACCENT_ID
        }
    }

    private fun normalizedHotkeyKeys(keys: Iterable<String>): List<String> {
        val seen = linkedSetOf<String>()
        keys.forEach { rawKey ->
            val normalized = rawKey.trim().uppercase()
            if (normalized in hotkeyOptionMap.keys) {
                seen += normalized
            }
        }

        val ordered = mutableListOf<String>()
        hotkeyModifierOptions.forEach { option ->
            if (option.id in seen) {
                ordered += option.id
            }
        }
        hotkeyPrimaryOptions.firstOrNull { it.id in seen }?.let { ordered += it.id }
        return ordered
    }

    private fun normalizeStoredShortcutType(type: String, target: String, hotkeyKeys: List<String>): String {
        val normalizedType = type.trim().ifBlank { "application" }
        return if (normalizedHotkeyKeys(hotkeyKeys).isNotEmpty() && target.isBlank()) {
            "hotkey"
        } else {
            normalizedType
        }
    }

    private fun effectiveShortcutType(item: ShortcutItem): String {
        return normalizeStoredShortcutType(item.type, item.target, item.hotkeyKeys)
    }

    private fun formatHotkeyDisplayName(keys: List<String>): String {
        return normalizedHotkeyKeys(keys)
            .map { hotkeyOptionMap[it]?.label ?: it }
            .joinToString(" + ")
    }

    private fun isBlockedHotkeyCombination(keys: List<String>): Boolean {
        val normalized = normalizedHotkeyKeys(keys).toSet()
        val hasCtrl = "LCTRL" in normalized || "RCTRL" in normalized
        val hasAlt = "LALT" in normalized || "RALT" in normalized
        return hasCtrl && hasAlt && "DELETE" in normalized
    }

    private fun describeShortcutTarget(item: ShortcutItem): String {
        return when (effectiveShortcutType(item)) {
            "hotkey" -> formatHotkeyDisplayName(item.hotkeyKeys)
            else -> item.target
        }
    }

    private fun canUseShortcutCommands(showFeedback: Boolean): Boolean {
        val ready = store.workerUrl.isNotBlank() && store.ownerToken.isNotBlank() && store.pairedPcId.isNotBlank()
        if (!ready && showFeedback) {
            showToast("Kisayollar icin once Worker ayarini girip bir PC sec.")
        }
        return ready
    }

    private fun dpToPx(valueDp: Int): Int = (resources.displayMetrics.density * valueDp).roundToInt()

    private fun listRemoteFiles(path: String) {
        val payload = JSONObject().put("path", path)
        sendAwaitedCommand("Dosya listesi", "files-list", payload) { resultPayload ->
                    currentRemotePath = resultPayload.optString("path", "")
                    currentRemoteParentPath = resultPayload.optString("parentPath", "").ifBlank { null }
                    store.setRemotePath(selectedPcIdOrNull(), currentRemotePath)
                    binding.filesPathInput.setText(currentRemotePath)
            clearRemoteSelection(resetPreview = false, rerender = false)
            updateSelectedRemoteFile(null)

            val entries = mutableListOf<RemoteEntry>()
            val entriesJson = resultPayload.optJSONArray("entries") ?: JSONArray()
            for (index in 0 until entriesJson.length()) {
                val entry = entriesJson.getJSONObject(index)
                entries.add(
                    RemoteEntry(
                        name = entry.optString("name", "Isimsiz"),
                        fullPath = entry.optString("fullPath", ""),
                        isDirectory = entry.optBoolean("isDirectory", false),
                        size = entry.optLong("size", 0L),
                        modifiedAt = entry.optLong("modifiedAt", 0L),
                    ),
                )
            }

            lastRemoteEntries = entries
            renderRemoteEntries(entries)
            if (entries.isEmpty()) {
                binding.resultText.text = if (currentRemotePath.isBlank()) {
                    "Gosterilecek kok klasor yok."
                } else {
                    "Bu klasor bos."
                }
            }
        }
    }

    private fun renderRemoteEntries(entries: List<RemoteEntry>) {
        binding.remoteFilesContainer.removeAllViews()

        entries.forEach { entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = 8
                }
            }

            row.addView(
                androidx.appcompat.widget.AppCompatCheckBox(this).apply {
                    isChecked = selectedRemoteEntries.containsKey(entry.fullPath)
                    setOnCheckedChangeListener { _, isChecked ->
                        toggleRemoteEntrySelection(entry, isChecked)
                    }
                },
            )

            val titleButton = Button(this).apply {
                isAllCaps = false
                text = buildString {
                    if (entry.isDirectory) {
                        append("[Klasor] ${entry.name}")
                    } else {
                        append("${entry.name} (${formatFileSize(entry.size)})")
                    }
                    if (entry.modifiedAt > 0L) {
                        append('\n')
                        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(entry.modifiedAt))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (entry.isDirectory) {
                        if (selectedRemoteEntries.isEmpty()) {
                            listRemoteFiles(entry.fullPath)
                        } else {
                            toggleRemoteEntrySelection(entry)
                        }
                    } else {
                        updateSelectedRemoteFile(entry.fullPath, entry)
                        toggleRemoteEntrySelection(entry, true)
                        binding.customAppPathInput.setText(entry.fullPath)
                    }
                }
                setOnLongClickListener {
                    toggleRemoteEntrySelection(entry)
                    updateSelectedRemoteFile(entry.fullPath, entry)
                    true
                }
            }
            row.addView(titleButton)

            if (!entry.isDirectory && isPreviewableFile(entry.fullPath)) {
                row.addView(
                    Button(this).apply {
                        isAllCaps = false
                        text = "Onizle"
                        setOnClickListener {
                            updateSelectedRemoteFile(entry.fullPath, entry)
                            previewRemoteFile(entry)
                        }
                    },
                )
            }

            if (!entry.isDirectory && isLaunchableFile(entry.fullPath)) {
                row.addView(
                    Button(this).apply {
                        isAllCaps = false
                        text = "Calistir"
                        setOnClickListener {
                            updateSelectedRemoteFile(entry.fullPath, entry)
                            binding.customAppPathInput.setText(entry.fullPath)
                            binding.launchSelectedFileButton.performClick()
                        }
                    },
                )
            }

            if (!entry.isDirectory) {
                row.addView(
                    Button(this).apply {
                        isAllCaps = false
                        text = "Indir"
                        setOnClickListener {
                            updateSelectedRemoteFile(entry.fullPath, entry)
                            downloadRemoteFile(entry.fullPath)
                        }
                    },
                )
            }

            binding.remoteFilesContainer.addView(row)
        }
    }

    private fun downloadRemoteFile(path: String) {
        persistLocalConfig()
        runInBackground {
            val entry = lastRemoteEntries.firstOrNull { it.fullPath == path }
            val legacyOnly = workerSupportsR2 == false
            if (legacyOnly || !shouldTryR2Transfers()) {
                if (!legacyOnly && entry != null && entry.size > MAX_LEGACY_FILE_BYTES) {
                    throw IllegalStateException("Bu Worker R2 desteklemedigi icin legacy modda tek dosya limiti 256 KB.")
                }
                downloadRemoteFileLegacy(path)
                return@runInBackground
            }

            try {
                downloadRemoteFileViaR2(path)
            } catch (error: IllegalStateException) {
                if (!isR2UnsupportedError(error.message)) {
                    throw error
                }

                markWorkerAsLegacy()
                if (entry != null && entry.size > MAX_LEGACY_FILE_BYTES) {
                    throw IllegalStateException("Bu Worker R2 desteklemedigi icin legacy modda tek dosya limiti 256 KB.")
                }
                downloadRemoteFileLegacy(path)
            }
        }
    }

    private fun downloadRemoteFileViaR2(path: String) {
        updateTransferProgress("Dosya indir", 5, "R2 alani hazirlaniyor")
        val reservation = api.reserveFile(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            direction = "pc-download",
            fileName = extractRemoteName(path),
        )
        setWorkerCapabilityState(true)

        updateTransferProgress("Dosya indir", 20, "PC dosyayi hazirliyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-download-r2",
            payload = JSONObject()
                .put("path", path)
                .put("objectKey", reservation.objectKey),
        )

        val result = parseCommandResult(command)
        if (!result.success) {
            throw IllegalStateException(friendlyErrorMessage(result.error, "Dosya indirilemedi."))
        }

        val downloaded = api.downloadReservedFile(store.workerUrl, store.ownerToken, reservation.objectKey) { transferred, total ->
            updateTransferStageProgress(
                title = "Dosya indir",
                stageStart = 30,
                stageEnd = 95,
                transferred = transferred,
                total = total,
                detail = "${extractRemoteName(path)} telefona aliniyor",
            )
        }
        pendingDownloadBytes = downloaded.bytes
        pendingDownloadObjectKey = reservation.objectKey
        pendingDownloadFileName = result.payload.optString("fileName", extractRemoteName(path))

        runOnUiThread {
            renderCommandSummary("Dosya indir", result)
            renderTransferComplete("Dosya hazir. Kaydetme konumunu sec.")
            appendLog("Dosya indirildi, telefona kaydetmeye hazir.")
            refreshUsageSummary()
            createDocumentLauncher.launch(pendingDownloadFileName)
        }
    }

    private fun downloadRemoteFileLegacy(path: String) {
        updateTransferProgress("Dosya indir", 10, "Legacy dosya modu ile hazirlaniyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-download",
            payload = JSONObject().put("path", path),
        )

        val result = parseCommandResult(command)
        if (!result.success) {
            throw IllegalStateException(friendlyErrorMessage(result.error, "Dosya indirilemedi."))
        }

        val base64 = result.payload.optString("base64Content", "")
        if (base64.isBlank()) {
            throw IllegalStateException("Dosya icerigi alinamadi.")
        }

        pendingDownloadBytes = Base64.decode(base64, Base64.DEFAULT)
        pendingDownloadObjectKey = null
        pendingDownloadFileName = result.payload.optString("fileName", extractRemoteName(path))

        runOnUiThread {
            renderCommandSummary("Dosya indir", result)
            renderTransferComplete("Legacy dosya hazir. Kaydetme konumunu sec.")
            appendLog("Dosya legacy modda indirildi, telefona kaydetmeye hazir.")
            refreshUsageSummary()
            createDocumentLauncher.launch(pendingDownloadFileName)
        }
    }

    private fun createRemoteFolder() {
        val folderName = binding.newFolderNameInput.text.toString().trim()
        val parentPath = binding.filesPathInput.text.toString().trim()
        if (folderName.isBlank() || parentPath.isBlank()) {
            showToast("Yeni klasor icin mevcut klasor ve ad gerekli.")
            return
        }

        val fullPath = combineRemotePath(parentPath, folderName)
        sendAwaitedCommand(
            label = "Klasor olustur",
            type = "file-manage",
            payload = JSONObject()
                .put("action", "create-folder")
                .put("path", fullPath),
        ) {
            binding.newFolderNameInput.setText("")
            listRemoteFiles(parentPath)
        }
    }

    private fun renameSelectedRemoteEntry() {
        val entry = selectedRemoteEntry
        val newName = binding.renameTargetInput.text.toString().trim()
        if (entry == null || newName.isBlank()) {
            showToast("Yeniden adlandirmak icin oge secip yeni ad gir.")
            return
        }

        sendAwaitedCommand(
            label = "Yeniden adlandir",
            type = "file-manage",
            payload = JSONObject()
                .put("action", "rename")
                .put("path", entry.fullPath)
                .put("newName", newName),
        ) {
            binding.renameTargetInput.setText("")
            listRemoteFiles(currentRemotePath.ifBlank { binding.filesPathInput.text.toString().trim() })
        }
    }

    private fun deleteSelectedRemoteEntry() {
        val entry = selectedRemoteEntry
        if (entry == null) {
            showToast("Silmek icin once bir oge sec.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Secili ogeyi sil")
            .setMessage("${entry.name} kalici olarak silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                sendAwaitedCommand(
                    label = "Oge sil",
                    type = "file-manage",
                    payload = JSONObject()
                        .put("action", "delete")
                        .put("path", entry.fullPath)
                        .put("recursive", true),
                ) {
                    updateSelectedRemoteFile(null)
                    binding.renameTargetInput.setText("")
                    listRemoteFiles(currentRemotePath.ifBlank { binding.filesPathInput.text.toString().trim() })
                }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun deleteMultiSelectedRemoteEntries() {
        val entries = selectedRemoteEntries.values.toList().ifEmpty {
            selectedRemoteEntry?.let { listOf(it) } ?: emptyList()
        }
        if (entries.isEmpty()) {
            showToast("Toplu silme icin once ogeleri sec.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Secilenleri sil")
            .setMessage("${entries.size} oge silinsin mi?")
            .setPositiveButton("Sil") { _, _ ->
                persistLocalConfig()
                runInBackground {
                    entries.forEachIndexed { index, entry ->
                        updateTransferProgress(
                            "Toplu silme",
                            (((index + 1) / entries.size.toFloat()) * 100).roundToInt(),
                            "${entry.name} siliniyor",
                        )
                        val command = api.sendCommandAndAwaitResult(
                            workerUrl = store.workerUrl,
                            ownerToken = store.ownerToken,
                            pcId = requirePcId(),
                            type = "file-manage",
                            payload = JSONObject()
                                .put("action", "delete")
                                .put("path", entry.fullPath)
                                .put("recursive", true),
                        )
                        val result = parseCommandResult(command)
                        if (!result.success) {
                            throw IllegalStateException(friendlyErrorMessage(result.error, "${entry.name} silinemedi."))
                        }
                    }

                    runOnUiThread {
                        renderTransferComplete("Toplu silme tamamlandi.")
                        clearRemoteSelection()
                        listRemoteFiles(currentRemotePath.ifBlank { binding.filesPathInput.text.toString().trim() })
                    }
                }
            }
            .setNegativeButton("Iptal", null)
            .show()
    }

    private fun uploadSelectedPhoneFile(uri: Uri) {
        val targetDirectory = binding.filesPathInput.text.toString().trim()
        runInBackground {
            if (targetDirectory.isBlank()) {
                throw IllegalStateException("Yukleme icin once bir klasor ac.")
            }

            updateTransferProgress("Dosya yukle", 5, "Telefon dosyasi okunuyor")
            val bytes = contentResolver.openInputStream(uri)?.use { input -> input.readBytes() }
                ?: throw IllegalStateException("Secilen dosya okunamadi.")
            val fileName = resolveDisplayName(uri)
            val remotePath = combineRemotePath(targetDirectory, fileName)
            val contentType = contentResolver.getType(uri) ?: "application/octet-stream"
            if (shouldTryR2Transfers()) {
                try {
                    uploadPhoneFileViaR2(bytes, fileName, remotePath, contentType, targetDirectory)
                    return@runInBackground
                } catch (error: IllegalStateException) {
                    if (!isR2UnsupportedError(error.message)) {
                        throw error
                    }

                    markWorkerAsLegacy()
                }
            }

            if (bytes.size > MAX_LEGACY_FILE_BYTES) {
                throw IllegalStateException("Bu Worker R2 desteklemedigi icin legacy modda tek dosya limiti 256 KB.")
            }
            uploadPhoneFileLegacy(bytes, fileName, remotePath, targetDirectory)
        }
    }

    private fun uploadPhoneFileViaR2(
        bytes: ByteArray,
        fileName: String,
        remotePath: String,
        contentType: String,
        targetDirectory: String,
    ) {
        if (bytes.size > MAX_R2_UPLOAD_BYTES) {
            throw IllegalStateException("Secilen dosya 25 MB sinirini asiyor.")
        }

        val reservation = api.reserveFile(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            direction = "mobile-upload",
            fileName = fileName,
        )
        setWorkerCapabilityState(true)

        updateTransferProgress("Dosya yukle", 15, "R2'ye yukleme basladi")
        api.uploadReservedFile(store.workerUrl, store.ownerToken, reservation.objectKey, bytes, contentType) { transferred, total ->
            updateTransferStageProgress(
                title = "Dosya yukle",
                stageStart = 15,
                stageEnd = 78,
                transferred = transferred,
                total = total,
                detail = "$fileName yukleniyor",
            )
        }

        updateTransferProgress("Dosya yukle", 84, "PC hedef klasore yaziyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-upload-r2",
            payload = JSONObject()
                .put("path", remotePath)
                .put("objectKey", reservation.objectKey),
        )

        val result = parseCommandResult(command)
        if (!result.success) {
            runCatching {
                api.deleteReservedFile(store.workerUrl, store.ownerToken, reservation.objectKey)
            }
        }

        runOnUiThread {
            renderCommandSummary("Dosya yukle", result)
            refreshUsageSummary()
            if (result.success) {
                renderTransferComplete("Dosya yuklendi: $fileName")
                appendLog("Telefon dosyasi yuklendi: $fileName")
                listRemoteFiles(targetDirectory)
            } else {
                val message = friendlyErrorMessage(result.error, "Dosya yuklenemedi.")
                renderTransferError(message)
                showToast(message)
            }
        }
    }

    private fun uploadPhoneFileLegacy(
        bytes: ByteArray,
        fileName: String,
        remotePath: String,
        targetDirectory: String,
    ) {
        updateTransferProgress("Dosya yukle", 20, "Legacy dosya modu ile gonderiliyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-upload",
            payload = JSONObject()
                .put("path", remotePath)
                .put("base64Content", Base64.encodeToString(bytes, Base64.NO_WRAP)),
        )
        val result = parseCommandResult(command)

        runOnUiThread {
            renderCommandSummary("Dosya yukle", result)
            refreshUsageSummary()
            if (result.success) {
                renderTransferComplete("Dosya yuklendi: $fileName")
                appendLog("Telefon dosyasi legacy modda yuklendi: $fileName")
                listRemoteFiles(targetDirectory)
            } else {
                val message = friendlyErrorMessage(result.error, "Dosya yuklenemedi.")
                renderTransferError(message)
                showToast(message)
            }
        }
    }

    private fun saveDownloadedFile(uri: Uri) {
        val bytes = pendingDownloadBytes
        val objectKey = pendingDownloadObjectKey
        if (bytes == null) {
            appendLog("Kaydedilecek indirme verisi bulunamadi.")
            return
        }

        runInBackground {
            contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
            } ?: throw IllegalStateException("Kayit konumu acilamadi.")

            if (!objectKey.isNullOrBlank()) {
                runCatching {
                    api.deleteReservedFile(store.workerUrl, store.ownerToken, objectKey)
                }
            }

            pendingDownloadBytes = null
            pendingDownloadObjectKey = null
            runOnUiThread {
                renderTransferComplete("Dosya telefona kaydedildi.")
                appendLog("Dosya telefona kaydedildi.")
                binding.resultText.text = "Dosya telefona kaydedildi: $uri"
            }
        }
    }

    private fun launchPresetApp(label: String, alias: String) {
        sendAwaitedCommand(
            label = "$label ac",
            type = "app-launch",
            payload = JSONObject().put("path", alias),
        )
    }

    private fun sendMouseMove(label: String, dx: Int, dy: Int) {
        sendAwaitedCommand(
            label = "Mouse $label",
            type = "input-mouse",
            payload = JSONObject()
                .put("action", "move-relative")
                .put("dx", dx)
                .put("dy", dy),
        )
    }

    private fun sendKeyboardShortcut(label: String, text: String) {
        sendAwaitedCommand(
            label = label,
            type = "input-keyboard",
            payload = JSONObject().put("text", text),
        )
    }

    private fun sendQueuedCommand(
        label: String,
        type: String,
        payload: JSONObject = JSONObject(),
        logDispatch: Boolean = true,
    ) {
        persistLocalConfig()
        runInBackground {
            api.sendCommand(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = requirePcId(),
                type = type,
                payload = payload,
            )

            runOnUiThread {
                if (logDispatch) {
                    appendLog("$label gonderildi.")
                }
            }
        }
    }

    private fun sendAwaitedCommand(
        label: String,
        type: String,
        payload: JSONObject = JSONObject(),
        onSuccess: (JSONObject) -> Unit = {},
    ) {
        persistLocalConfig()
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = requirePcId(),
                type = type,
                payload = payload,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                renderCommandSummary(label, result)
                refreshUsageSummary()
                if (result.success) {
                    appendLog("$label basarili.")
                    onSuccess(result.payload)
                } else {
                    val message = friendlyErrorMessage(result.error, "$label basarisiz.")
                    appendLog("$label hatasi: $message")
                    showToast(message)
                }
            }
        }
    }

    private fun parseCommandResult(command: JSONObject): RemoteCommandResult {
        val result = command.optJSONObject("result") ?: JSONObject()
        return RemoteCommandResult(
            commandType = command.optString("type", "-"),
            status = command.optString("status", "unknown"),
            success = result.optBoolean("success", false),
            error = result.optString("error", "").ifBlank { null },
            payload = result.optJSONObject("payload") ?: JSONObject(),
        )
    }

    private fun renderCommandSummary(label: String, result: RemoteCommandResult) {
        val payloadSummary = summarizePayload(result.payload)
        binding.resultText.text = buildString {
            append(label)
            append(" -> ")
            append(if (result.success) "basarili" else "basarisiz")
            append('\n')
            append("Komut: ${result.commandType} | Durum: ${result.status}")
            if (!result.error.isNullOrBlank()) {
                append('\n')
                append("Hata: ${friendlyErrorMessage(result.error)}")
            }
            if (payloadSummary.isNotBlank()) {
                append('\n')
                append(payloadSummary)
            }
        }
    }

    private fun summarizePayload(payload: JSONObject): String {
        if (payload.length() == 0) {
            return ""
        }

        return when {
            payload.has("entries") -> "Listelenen oge sayisi: ${payload.optJSONArray("entries")?.length() ?: 0}"
            payload.has("processes") -> "Listelenen process sayisi: ${payload.optJSONArray("processes")?.length() ?: 0}"
            payload.has("imageBase64") -> "Screenshot hazir: ${payload.optInt("width")} x ${payload.optInt("height")}"
            payload.has("base64Content") -> "Indirilecek dosya: ${payload.optString("fileName")} (${formatFileSize(payload.optLong("size"))})"
            payload.has("text") -> "Metin: ${payload.optString("text")}"
            payload.has("launchedAs") -> "Acilan hedef: ${payload.optString("launchedAs")}"
            else -> payload.toString(2)
        }
    }

    private fun findPreferredPc(pcs: List<RemotePcSummary>): RemotePcSummary {
        return pcs.firstOrNull { it.id == store.pairedPcId } ?: pcs.first()
    }

    private fun updateSelectedRemoteFile(path: String?, entry: RemoteEntry? = null) {
        selectedRemoteEntry = entry
        selectedRemoteFilePath = path?.takeIf { it.isNotBlank() && entry?.isDirectory != true }
        binding.selectedRemoteEntryText.text = entry?.let {
            "Odak oge: ${it.fullPath}"
        } ?: "Secili dosya: -"
        binding.renameTargetInput.setText(entry?.name.orEmpty())
        val fileEnabled = !selectedRemoteFilePath.isNullOrBlank()
        val entryEnabled = selectedRemoteEntry != null
        val multiSelectionCount = selectedRemoteEntries.size
        binding.launchSelectedFileButton.isEnabled = fileEnabled
        binding.downloadSelectedFileButton.isEnabled = fileEnabled
        binding.previewSelectedFileButton.isEnabled = fileEnabled && entry?.let { isPreviewableFile(it.fullPath) } == true
        binding.renameSelectedFileButton.isEnabled = entryEnabled
        binding.deleteSelectedFileButton.isEnabled = entryEnabled
        binding.deleteMultiSelectedFilesButton.isEnabled = multiSelectionCount > 0
        binding.clearFileSelectionButton.isEnabled = multiSelectionCount > 0
        binding.fileSelectionSummaryText.text = if (multiSelectionCount == 0) {
            "Coklu secim kapali."
        } else {
            "Secilen oge sayisi: $multiSelectionCount"
        }
        updateFilesTabBadge()
    }

    private fun toggleRemoteEntrySelection(entry: RemoteEntry, forcedState: Boolean? = null) {
        val shouldSelect = forcedState ?: !selectedRemoteEntries.containsKey(entry.fullPath)
        if (shouldSelect) {
            selectedRemoteEntries[entry.fullPath] = entry
            updateSelectedRemoteFile(entry.fullPath, entry)
        } else {
            selectedRemoteEntries.remove(entry.fullPath)
            val nextFocused = selectedRemoteEntries.values.lastOrNull()
            updateSelectedRemoteFile(nextFocused?.fullPath, nextFocused)
        }
        renderRemoteEntries(lastRemoteEntries)
    }

    private fun clearRemoteSelection(resetPreview: Boolean = true, rerender: Boolean = true) {
        selectedRemoteEntries.clear()
        updateSelectedRemoteFile(null)
        if (resetPreview) {
            renderFilePreviewPlaceholder()
        }
        if (rerender && lastRemoteEntries.isNotEmpty()) {
            renderRemoteEntries(lastRemoteEntries)
        }
    }

    private fun previewSelectedRemoteFile() {
        val target = selectedRemoteEntry?.takeIf { !it.isDirectory }
            ?: selectedRemoteEntries.values.singleOrNull()?.takeIf { !it.isDirectory }
        if (target == null) {
            showToast("Onizleme icin tek bir dosya sec.")
            return
        }

        previewRemoteFile(target)
    }

    private fun previewRemoteFile(entry: RemoteEntry) {
        if (entry.size > MAX_PREVIEW_BYTES) {
            renderFilePreviewText(
                title = entry.name,
                text = "Bu dosya ${formatFileSize(entry.size)} boyutunda. Onizleme siniri ${formatFileSize(MAX_PREVIEW_BYTES.toLong())}.",
            )
            return
        }

        persistLocalConfig()
        runInBackground {
            if (workerSupportsR2 == false || !shouldTryR2Transfers()) {
                if (entry.size > MAX_LEGACY_FILE_BYTES) {
                    throw IllegalStateException("Bu Worker R2 desteklemedigi icin legacy modda onizleme 256 KB ile sinirli.")
                }
                previewRemoteFileLegacy(entry)
                return@runInBackground
            }

            try {
                previewRemoteFileViaR2(entry)
            } catch (error: IllegalStateException) {
                if (!isR2UnsupportedError(error.message)) {
                    throw error
                }

                markWorkerAsLegacy()
                if (entry.size > MAX_LEGACY_FILE_BYTES) {
                    throw IllegalStateException("Bu Worker R2 desteklemedigi icin legacy modda onizleme 256 KB ile sinirli.")
                }
                previewRemoteFileLegacy(entry)
            }
        }
    }

    private fun previewRemoteFileViaR2(entry: RemoteEntry) {
        updateTransferProgress("Dosya onizleme", 5, "Onizleme dosyasi hazirlaniyor")
        val reservation = api.reserveFile(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            direction = "pc-download",
            fileName = extractRemoteName(entry.fullPath),
        )
        setWorkerCapabilityState(true)

        updateTransferProgress("Dosya onizleme", 20, "PC onizleme dosyasini okuyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-download-r2",
            payload = JSONObject()
                .put("path", entry.fullPath)
                .put("objectKey", reservation.objectKey),
        )

        val result = parseCommandResult(command)
        if (!result.success) {
            throw IllegalStateException(friendlyErrorMessage(result.error, "Onizleme hazirlanamadi."))
        }

        val downloaded = api.downloadReservedFile(store.workerUrl, store.ownerToken, reservation.objectKey) { transferred, total ->
            updateTransferStageProgress(
                title = "Dosya onizleme",
                stageStart = 30,
                stageEnd = 95,
                transferred = transferred,
                total = total,
                detail = "${entry.name} indiriliyor",
            )
        }
        runCatching {
            api.deleteReservedFile(store.workerUrl, store.ownerToken, reservation.objectKey)
        }

        runOnUiThread {
            renderTransferComplete("Dosya onizlemesi hazir.")
            renderRemotePreview(entry, downloaded)
        }
    }

    private fun previewRemoteFileLegacy(entry: RemoteEntry) {
        updateTransferProgress("Dosya onizleme", 15, "Legacy dosya modu ile hazirlaniyor")
        val command = api.sendCommandAndAwaitResult(
            workerUrl = store.workerUrl,
            ownerToken = store.ownerToken,
            pcId = requirePcId(),
            type = "file-download",
            payload = JSONObject().put("path", entry.fullPath),
        )
        val result = parseCommandResult(command)
        if (!result.success) {
            throw IllegalStateException(friendlyErrorMessage(result.error, "Onizleme hazirlanamadi."))
        }

        val base64 = result.payload.optString("base64Content", "")
        if (base64.isBlank()) {
            throw IllegalStateException("Onizleme dosyasi bos dondu.")
        }

        val downloaded = WorkerApi.DownloadedObject(
            bytes = Base64.decode(base64, Base64.DEFAULT),
            contentType = "application/octet-stream",
        )
        runOnUiThread {
            renderTransferComplete("Legacy dosya onizlemesi hazir.")
            renderRemotePreview(entry, downloaded)
        }
    }

    private fun renderRemotePreview(entry: RemoteEntry, downloaded: WorkerApi.DownloadedObject) {
        val lowerName = entry.name.lowercase()
        val contentType = downloaded.contentType.lowercase()
        when {
            contentType.startsWith("image/") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif") -> {
                val bitmap = BitmapFactory.decodeByteArray(downloaded.bytes, 0, downloaded.bytes.size)
                if (bitmap != null) {
                    binding.filePreviewImage.visibility = View.VISIBLE
                    binding.filePreviewImage.setImageBitmap(bitmap)
                    binding.filePreviewText.text = "Boyut: ${formatFileSize(entry.size)}"
                    binding.filePreviewTitleText.text = "Onizleme: ${entry.name}"
                    return
                }
            }

            contentType.startsWith("text/") || lowerName.endsWith(".txt") || lowerName.endsWith(".json") || lowerName.endsWith(".log") || lowerName.endsWith(".xml") || lowerName.endsWith(".ini") || lowerName.endsWith(".md") -> {
                val previewText = downloaded.bytes.toString(Charsets.UTF_8)
                    .take(6000)
                    .ifBlank { "(bos dosya)" }
                renderFilePreviewText(entry.name, previewText)
                return
            }
        }

        renderFilePreviewText(
            title = entry.name,
            text = "Bu dosya turu icin yerlesik onizleme yok.\nTur: ${downloaded.contentType}\nBoyut: ${formatFileSize(entry.size)}",
        )
    }

    private fun renderFilePreviewText(title: String, text: String) {
        binding.filePreviewTitleText.text = "Onizleme: $title"
        binding.filePreviewImage.visibility = View.GONE
        binding.filePreviewImage.setImageDrawable(null)
        binding.filePreviewText.text = text
    }

    private fun renderFilePreviewPlaceholder() {
        binding.filePreviewTitleText.text = "Dosya onizleme"
        binding.filePreviewImage.visibility = View.GONE
        binding.filePreviewImage.setImageDrawable(null)
        binding.filePreviewText.text = "Secili dosyanin hizli onizlemesi burada gosterilir."
    }

    private fun renderTransferIdleState() {
        binding.transferProgressBar.progress = 0
        binding.transferStatusText.text = "Aktarim bekleniyor."
    }

    private fun updateTransferProgress(title: String, progress: Int, detail: String) {
        runOnUiThread {
            binding.transferProgressBar.progress = progress.coerceIn(0, 100)
            binding.transferStatusText.text = "$title • $detail"
        }
    }

    private fun updateTransferStageProgress(
        title: String,
        stageStart: Int,
        stageEnd: Int,
        transferred: Long,
        total: Long,
        detail: String,
    ) {
        val stageProgress = if (total <= 0L) {
            stageStart
        } else {
            val ratio = (transferred.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
            (stageStart + ((stageEnd - stageStart) * ratio)).roundToInt()
        }
        val suffix = if (total > 0L) {
            " (${formatFileSize(transferred)} / ${formatFileSize(total)})"
        } else {
            ""
        }
        updateTransferProgress(title, stageProgress, detail + suffix)
    }

    private fun renderTransferComplete(message: String) {
        binding.transferProgressBar.progress = 100
        binding.transferStatusText.text = message
    }

    private fun renderTransferError(message: String) {
        binding.transferProgressBar.progress = 0
        binding.transferStatusText.text = friendlyErrorMessage(message, "Dosya aktariminda hata olustu.")
    }

    private fun updateSelectedProcess(process: RemoteProcessEntry?) {
        selectedProcessName = process?.processName
        selectedProcessId = process?.processId
        binding.killProcessInput.setText(process?.processName.orEmpty())
    }

    private fun createProcessIconDrawable(iconBase64: String?): Drawable? {
        val iconSize = (resources.displayMetrics.density * PROCESS_ICON_SIZE_DP).roundToInt()
        val drawable = runCatching {
            if (iconBase64.isNullOrBlank()) {
                AppCompatResources.getDrawable(this, R.drawable.ic_process_generic)
            } else {
                val bytes = Base64.decode(iconBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                BitmapDrawable(resources, bitmap)
            }
        }.getOrNull() ?: AppCompatResources.getDrawable(this, R.drawable.ic_process_generic)

        drawable?.setBounds(0, 0, iconSize, iconSize)
        return drawable
    }

    private fun updateScreenshotActions() {
        val enabled = lastScreenshotBytes != null
        binding.openScreenshotButton.isEnabled = enabled
        binding.saveScreenshotButton.isEnabled = enabled
    }

    private fun updateLivePreviewButtons() {
        binding.startLivePreviewButton.isEnabled = !isLivePreviewRunning
        binding.startHdLivePreviewButton.isEnabled = !isLivePreviewRunning
        binding.stopLivePreviewButton.isEnabled = isLivePreviewRunning
    }

    private fun renderUsageSummaryPlaceholder() {
        binding.usageSummaryText.text =
            "Yaklasik limit bilgisi eslesme sonrasi gorunur. Not: request sayisi tahminidir; R2 icin sabit gunluk veri limiti yoktur."
    }

    private fun refreshUsageSummary() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            renderUsageSummaryPlaceholder()
            return
        }

        runInBackground {
            val usage = api.getUsageSummary(store.workerUrl, store.ownerToken)
            val requestBudget = usage.optInt("requestBudget", 100000)
            val requestsUsed = usage.optInt("approxRequestsUsed", 0)
            val requestsRemaining = usage.optInt("approxRequestsRemaining", requestBudget)
            val transferredBytes = usage.optLong("approxTransferredBytes", 0L)
            val note = usage.optString("note")

            runOnUiThread {
                binding.usageSummaryText.text = buildString {
                    append("Yaklasik kalan request (son 24 saat): ")
                    append(String.format("%,d", requestsRemaining))
                    append(" / ")
                    append(String.format("%,d", requestBudget))
                    append('\n')
                    append("Yaklasik kullanilan request: ")
                    append(String.format("%,d", requestsUsed))
                    append('\n')
                    append("Aktarilan veri (son 24 saat): ")
                    append(formatFileSize(transferredBytes))
                    if (note.isNotBlank()) {
                        append('\n')
                        append(note)
                    }
                }
            }
        }
    }

    private fun updateDragButton() {
        binding.dragModeButton.text = if (isDragModeEnabled) {
            "Drag modu acik"
        } else {
            "Drag modu kapali"
        }
    }

    private fun openScreenshotDialog() {
        val bytes = lastScreenshotBytes
        if (bytes == null) {
            showToast("Once screenshot al.")
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            showToast("Screenshot acilamadi.")
            return
        }

        val imageView = ZoomableImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).roundToInt(),
            )
            setBackgroundColor(Color.parseColor("#140E26"))
            setImageBitmap(bitmap)
            setPadding(12, 12, 12, 12)
        }

        AlertDialog.Builder(this)
            .setTitle("PC Screenshot")
            .setView(imageView)
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Kaydet") { _, _ ->
                saveCurrentScreenshot()
            }
            .show()
    }

    private fun saveCurrentScreenshot() {
        val bytes = lastScreenshotBytes
        if (bytes == null) {
            showToast("Once screenshot al.")
            return
        }

        pendingDownloadBytes = bytes
        pendingDownloadObjectKey = null
        pendingDownloadFileName = lastScreenshotFileName
        createDocumentLauncher.launch(lastScreenshotFileName)
    }

    private fun pollLivePreview() {
        if (!isLivePreviewRunning || store.workerUrl.isBlank() || store.ownerToken.isBlank() || store.pairedPcId.isBlank()) {
            return
        }

        if (isLivePreviewInFlight) {
            mainHandler.postDelayed(livePreviewRunnable, LIVE_PREVIEW_INTERVAL_MS)
            return
        }

        isLivePreviewInFlight = true
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = requirePcId(),
                type = "screenshot",
                payload = JSONObject()
                    .put("quality", activeLivePreviewProfile.quality)
                    .put("maxWidth", activeLivePreviewProfile.maxWidth)
                    .put("maxHeight", activeLivePreviewProfile.maxHeight),
                timeoutMs = 18_000,
                pollIntervalMs = 250,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                isLivePreviewInFlight = false
                if (result.success) {
                    val base64Image = result.payload.optString("imageBase64", "")
                    if (base64Image.isNotBlank()) {
                        lastScreenshotBytes = Base64.decode(base64Image, Base64.DEFAULT)
                        lastScreenshotFileName = "pc-live-${System.currentTimeMillis()}.jpg"
                        renderCurrentScreenshotPreview()
                        binding.livePreviewStatusText.text =
                            "Canli onizleme acik • ${activeLivePreviewProfile.label} • ${result.payload.optInt("width")} x ${result.payload.optInt("height")}"
                    }
                } else {
                    binding.livePreviewStatusText.text = friendlyErrorMessage(result.error, "Canli onizleme hatasi.")
                }

                if (isLivePreviewRunning) {
                    mainHandler.postDelayed(livePreviewRunnable, LIVE_PREVIEW_INTERVAL_MS)
                }
            }
        }
    }

    private fun startLivePreview(profile: LivePreviewProfile = activeLivePreviewProfile, persistPreference: Boolean = true) {
        val scopedPcId = selectedPcIdOrNull()
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || scopedPcId == null) {
            showToast("Canli onizleme icin once bir PC sec.")
            return
        }

        activeLivePreviewProfile = profile
        if (persistPreference) {
            store.setLivePreviewEnabled(scopedPcId, true)
            store.setLivePreviewMode(scopedPcId, profile.modeId)
        }
        isLivePreviewRunning = true
        binding.livePreviewStatusText.text = "Canli ekran onizlemesi baslatildi (${profile.label})."
        updateLivePreviewButtons()
        mainHandler.removeCallbacks(livePreviewRunnable)
        mainHandler.post(livePreviewRunnable)
    }

    private fun stopLivePreview(updateStoredPreference: Boolean = true) {
        val scopedPcId = selectedPcIdOrNull()
        if (updateStoredPreference && scopedPcId != null) {
            store.setLivePreviewEnabled(scopedPcId, false)
        }
        isLivePreviewRunning = false
        isLivePreviewInFlight = false
        mainHandler.removeCallbacks(livePreviewRunnable)
        binding.livePreviewStatusText.text = "Canli ekran onizlemesi kapali."
        updateLivePreviewButtons()
    }

    private fun startClipboardSync() {
        val enabledPcIds = enabledClipboardSyncPcIds()
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || enabledPcIds.isEmpty()) {
            setClipboardSyncSwitchChecked(false)
            showToast("Clipboard sync icin once bir PC sec.")
            return
        }

        clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener)
        clipboardManager.addPrimaryClipChangedListener(clipboardChangedListener)
        mainHandler.removeCallbacks(clipboardPollRunnable)
        processLocalClipboardChange()
        if (isActivityResumed) {
            mainHandler.postDelayed(clipboardPollRunnable, CLIPBOARD_POLL_INTERVAL_MS)
        }
        binding.clipboardSyncStatusText.text = buildClipboardSyncActiveStatus(enabledPcIds)
    }

    private fun stopClipboardSync() {
        pauseClipboardForegroundMonitoring()
        binding.clipboardSyncStatusText.text = "Clipboard senkronizasyonu kapali."
    }

    private fun pauseClipboardForegroundMonitoring() {
        clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener)
        mainHandler.removeCallbacks(clipboardPollRunnable)
    }

    private fun enabledClipboardSyncPcIds(): Set<String> = store.getEnabledClipboardSyncPcIds()

    private fun processLocalClipboardChange() {
        val enabledPcIds = enabledClipboardSyncPcIds()
        if (enabledPcIds.isEmpty()) {
            return
        }

        val clipText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val clipSignature = clipboardSignature(clipText)
        val suppressedPcIds = enabledPcIds.filter { store.shouldSuppressClipboardEcho(it, clipText) }
        if (suppressedPcIds.isNotEmpty()) {
            lastLocalClipboardSignature = clipSignature
            store.lastLocalClipboardSignature = clipSignature
            suppressedPcIds.forEach { store.clearIncomingClipboardMarker(it) }
            binding.clipboardInput.setText(clipText)
            binding.clipboardSyncStatusText.text = "PC clipboard'u telefona alindi."
            return
        }

        if (clipText.isBlank() || clipSignature == lastLocalClipboardSignature) {
            return
        }

        lastLocalClipboardSignature = clipSignature
        store.lastLocalClipboardSignature = clipSignature
        sendQueuedCommandToPcs(
            pcIds = enabledPcIds,
            label = "Clipboard senkronize edildi",
            type = "clipboard-set",
            payload = JSONObject().put("text", clipText),
            logDispatch = false,
        )
        binding.clipboardSyncStatusText.text = buildClipboardDispatchStatus(enabledPcIds)
    }

    private fun buildClipboardSyncActiveStatus(enabledPcIds: Set<String>): String {
        return when (enabledPcIds.size) {
            0 -> "Clipboard senkronizasyonu kapali."
            1 -> "Clipboard senkronizasyonu acik. Uygulama acikken telefondan kopyaladigin yazi ${resolvePcDisplayName(enabledPcIds.first())} cihazina gonderilir. Yeni PC kopyalamalari otomatik gelir."
            else -> "Clipboard senkronizasyonu acik. Uygulama acikken telefondan kopyaladigin yazi ${enabledPcIds.size} etkin PC'ye gonderilir. Yeni PC kopyalamalari otomatik gelir."
        }
    }

    private fun buildClipboardDispatchStatus(enabledPcIds: Set<String>): String {
        return when (enabledPcIds.size) {
            0 -> "Clipboard senkronizasyonu kapali."
            1 -> "Yerel clipboard ${resolvePcDisplayName(enabledPcIds.first())} cihazina gonderildi."
            else -> "Yerel clipboard ${enabledPcIds.size} etkin PC'ye gonderildi."
        }
    }

    private fun clipboardSignature(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun isMiuiFamilyDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer.contains("xiaomi")
            || manufacturer.contains("redmi")
            || manufacturer.contains("poco")
            || brand.contains("xiaomi")
            || brand.contains("redmi")
            || brand.contains("poco")
    }

    private fun resolvePcDisplayName(pcId: String): String {
        return availablePcs.firstOrNull { it.id == pcId }?.name
            ?: if (store.pairedPcId == pcId && store.pairedPcName.isNotBlank()) {
                store.pairedPcName
            } else {
                "hedef PC"
            }
    }

    private fun sendQueuedCommandToPcs(
        pcIds: Collection<String>,
        label: String,
        type: String,
        payload: JSONObject = JSONObject(),
        logDispatch: Boolean = true,
    ) {
        val uniquePcIds = pcIds.filter { it.isNotBlank() }.distinct()
        if (uniquePcIds.isEmpty()) {
            return
        }

        persistLocalConfig()
        runInBackground {
            uniquePcIds.forEach { pcId ->
                api.sendCommand(
                    workerUrl = store.workerUrl,
                    ownerToken = store.ownerToken,
                    pcId = pcId,
                    type = type,
                    payload = JSONObject(payload.toString()),
                )
            }

            runOnUiThread {
                if (logDispatch) {
                    appendLog("$label gonderildi.")
                }
            }
        }
    }

    private fun sendTouchpadQueuedCommand(payload: JSONObject, onComplete: (() -> Unit)? = null) {
        runInBackground(inputExecutor) {
            runCatching {
                api.sendCommand(
                    workerUrl = store.workerUrl,
                    ownerToken = store.ownerToken,
                    pcId = requirePcId(),
                    type = "input-mouse",
                    payload = payload,
                )
            }

            mainHandler.post {
                onComplete?.invoke()
            }
        }
    }

    private fun dispatchTouchpadMoveIfNeeded(force: Boolean = false) {
        if (touchpadPendingDx == 0 && touchpadPendingDy == 0) {
            dispatchPendingTouchpadReleaseIfNeeded()
            return
        }

        if (isTouchpadCommandInFlight) {
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - touchpadLastSentAt < TOUCHPAD_SEND_INTERVAL_MS) {
            return
        }

        val dx = touchpadPendingDx
        val dy = touchpadPendingDy
        touchpadPendingDx = 0
        touchpadPendingDy = 0
        touchpadLastSentAt = now
        isTouchpadCommandInFlight = true

        sendTouchpadQueuedCommand(
            payload = JSONObject()
                .put("action", "move-relative")
                .put("dx", dx)
                .put("dy", dy),
        ) {
            isTouchpadCommandInFlight = false
            if (touchpadPendingDx != 0 || touchpadPendingDy != 0) {
                dispatchTouchpadMoveIfNeeded(force = true)
            } else {
                dispatchPendingTouchpadReleaseIfNeeded()
            }
        }
    }

    private fun dispatchPendingTouchpadReleaseIfNeeded() {
        if (!pendingTouchpadRelease || isTouchpadCommandInFlight) {
            return
        }

        pendingTouchpadRelease = false
        sendTouchpadQueuedCommand(JSONObject().put("action", "left-up"))
    }

    private fun handleTouchpadEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                binding.touchpadView.parent?.requestDisallowInterceptTouchEvent(true)
                touchpadLastX = event.x
                touchpadLastY = event.y
                touchpadLastSentAt = 0L
                touchpadPendingDx = 0
                touchpadPendingDy = 0
                pendingTouchpadRelease = false
                if (isDragModeEnabled && !isTouchpadDragging) {
                    isTouchpadDragging = true
                    sendTouchpadQueuedCommand(JSONObject().put("action", "left-down"))
                }
            }

            MotionEvent.ACTION_MOVE -> {
                binding.touchpadView.parent?.requestDisallowInterceptTouchEvent(true)
                touchpadPendingDx += ((event.x - touchpadLastX) * TOUCHPAD_MOVE_SCALE).roundToInt()
                touchpadPendingDy += ((event.y - touchpadLastY) * TOUCHPAD_MOVE_SCALE).roundToInt()
                touchpadLastX = event.x
                touchpadLastY = event.y
                dispatchTouchpadMoveIfNeeded()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                binding.touchpadView.parent?.requestDisallowInterceptTouchEvent(false)
                dispatchTouchpadMoveIfNeeded(force = true)
                if (isTouchpadDragging) {
                    isTouchpadDragging = false
                    pendingTouchpadRelease = true
                }
                dispatchPendingTouchpadReleaseIfNeeded()
            }
        }

        return true
    }

    private fun clearPairingState(resetLogs: Boolean = false) {
        store.ownerToken = ""
        store.pairedPcId = ""
        store.pairedPcName = ""
        unreadNotificationCount = 0
        lastRenderedNotifications = emptyList()
        updateSelectedPcDisplay(null, null)
        binding.unpairButton.isEnabled = false
        binding.notificationSettingsText.text = "Bildirim tercihlerini buradan yonetebilirsin."
        updateNotificationLimitLabels()
        renderPcSummary(emptyList())
        binding.processListContainer.removeAllViews()
        binding.remoteFilesContainer.removeAllViews()
        binding.notificationCenterContainer.removeAllViews()
        binding.systemInfoText.text = "Sistem bilgileri burada gorunecek."
        lastRemoteEntries = emptyList()
        selectedRemoteEntries.clear()
        updateSelectedProcess(null)
        updateSelectedRemoteFile(null)
        renderTransferIdleState()
        renderFilePreviewPlaceholder()
        stopLivePreview(updateStoredPreference = false)
        stopClipboardSync()
        setClipboardSyncSwitchChecked(false)
        shortcutItems.clear()
        activeShortcutId = null
        clearShortcutDragState(refreshGrid = false)
        renderShortcutGrid()
        updateFilesTabBadge()
        updateNotificationsTabBadge(0)
        updateSettingsTabBadge()
        if (resetLogs) {
            binding.resultText.text = "Son komut sonucu burada gorunecek."
        }
    }

    private fun ensureOwnerTokenReady() {
        if (store.workerUrl.isBlank()) {
            throw IllegalStateException("Worker URL gir.")
        }
        if (store.ownerToken.isBlank()) {
            throw IllegalStateException("Once PC ile esles.")
        }
    }

    private fun ensureFcmTokenReady() {
        if (store.fcmToken.isBlank()) {
            throw IllegalStateException("FCM token henuz hazir degil, biraz bekleyip tekrar dene.")
        }
    }

    private fun requirePcId(): String {
        ensureOwnerTokenReady()
        if (store.pairedPcId.isBlank()) {
            throw IllegalStateException("Secili PC bulunamadi. Once durum yenile.")
        }

        return store.pairedPcId
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0) {
                    return cursor.getString(columnIndex)
                }
            }
        }

        return uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
    }

    private fun isLaunchableFile(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in setOf("exe", "bat", "cmd", "lnk", "com")
    }

    private fun isPreviewableFile(path: String): Boolean {
        val extension = path.substringAfterLast('.', "").lowercase()
        return extension in setOf("png", "jpg", "jpeg", "gif", "webp", "txt", "json", "log", "xml", "ini", "md")
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> String.format("%.1f MB", size / (1024f * 1024f))
            size >= 1024 -> String.format("%.1f KB", size / 1024f)
            else -> "$size B"
        }
    }

    private fun combineRemotePath(directory: String, fileName: String): String {
        val separator = if (directory.contains('\\')) "\\" else "/"
        return if (directory.endsWith("\\") || directory.endsWith("/")) {
            "$directory$fileName"
        } else {
            "$directory$separator$fileName"
        }
    }

    private fun extractRemoteName(path: String): String {
        return path.substringAfterLast('\\').substringAfterLast('/').ifBlank { "download.bin" }
    }

    private fun runInBackground(executorService: java.util.concurrent.ExecutorService = executor, block: () -> Unit) {
        executorService.execute {
            runCatching(block).onFailure { error ->
                runOnUiThread {
                    val message = friendlyErrorMessage(error.message)
                    appendLog(message)
                    showToast(message)
                }
            }
        }
    }

    private fun friendlyErrorMessage(message: String?, fallback: String = "Beklenmeyen hata"): String {
        val trimmed = message?.trim().orEmpty()
        if (trimmed.isBlank()) {
            return fallback
        }

        val normalized = trimmed.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()

        return when {
            lower.contains("\"error_code\":1101")
                || lower.contains("error code: 1101")
                || lower.contains("worker threw exception") ->
                "Worker hatasi (1101): backend kurulumunda eksik adim olabilir. d1-tablolari-onar-yardimcisi.bat calistirip Worker'i yeniden deploy et."

            lower.contains("no such host is known")
                || lower.contains("name or service not known")
                || lower.contains("bilinen böyle bir ana bilgisayar yok")
                || lower.contains("bilinen boyle bir ana bilgisayar yok") ->
                "Worker URL hatali veya erisilemiyor. Deploy ciktisindaki tam https://...workers.dev adresini kullan."

            lower.contains("\"status\":401")
                || lower.contains("\"status\":403")
                || lower.contains("401 unauthorized")
                || lower.contains("403 forbidden") ->
                "Yetki hatasi: oturum bilgisi gecersiz olabilir. PC ile yeniden esles."

            lower.contains("\"status\":404")
                || lower.contains("404 not found") ->
                "Kaynak bulunamadi: Worker URL, secili PC veya ilgili veri eksik olabilir."

            lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("zaman asimina ugradi") ->
                "Istek zaman asimina ugradi. Baglantiyi ve Worker durumunu kontrol edip tekrar dene."

            normalized.startsWith("{") || normalized.startsWith("[") -> fallback
            else -> normalized
        }
    }

    private fun appendLog(message: String) {
        binding.logText.append("• $message\n")
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun normalizeWorkerUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            return ""
        }

        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed.removeSuffix("/")
        }

        return "https://${trimmed.removeSuffix("/")}"
    }

    private data class RemoteEntry(
        val name: String,
        val fullPath: String,
        val isDirectory: Boolean,
        val size: Long,
        val modifiedAt: Long,
    )

    private data class SectionSpec(
        val title: String,
        val container: LinearLayout,
        val anchorIds: List<Int>,
    )

    private data class RemotePcSummary(
        val id: String,
        val name: String,
        val status: String,
        val lastEventType: String,
        val lastSeenAt: Long,
        val platform: String,
    )

    private data class RetainedUiState(
        val selectedTabIndex: Int,
        val screenshotBytes: ByteArray?,
        val screenshotFileName: String,
        val notifications: List<RemoteNotificationItem>,
        val unreadNotificationCount: Int,
        val availablePcs: List<RemotePcSummary>,
        val statusText: String,
        val resultText: String,
        val logText: String,
    )

    private data class RemoteNotificationItem(
        val id: String,
        val pcId: String,
        val type: String,
        val title: String,
        val body: String,
        val isRead: Boolean,
        val createdAt: Long,
    )

    private data class RemoteProcessEntry(
        val processId: Int,
        val processName: String,
        val displayName: String,
        val windowTitle: String,
        val isForeground: Boolean,
        val memoryMb: Double,
        val iconBase64: String?,
    )

    private data class ShortcutItem(
        val id: String,
        val title: String,
        val type: String,
        val target: String,
        val arguments: String,
        val iconBase64: String?,
        val iconKind: String?,
        val accentId: String,
        val hotkeyKeys: List<String>,
    )

    private data class ShortcutAccentOption(
        val id: String,
        val label: String,
        val fillColorRes: Int,
        val strokeColorRes: Int,
    )

    private data class HotkeyKeyOption(
        val id: String,
        val label: String,
        val isModifier: Boolean = false,
    )

    private data class ShortcutTypeOption(
        val id: String,
        val label: String,
        val targetHint: String,
        val helperText: String,
        val supportsArguments: Boolean,
        val pickerMode: ShortcutPickerMode?,
    )

    private enum class ShortcutPickerMode {
        APPLICATION,
        FOLDER,
    }

    private data class RemoteCommandResult(
        val commandType: String,
        val status: String,
        val success: Boolean,
        val error: String?,
        val payload: JSONObject,
    )
}

