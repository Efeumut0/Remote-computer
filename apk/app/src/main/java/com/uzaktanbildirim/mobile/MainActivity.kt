package com.uzaktanbildirim.mobile

import android.Manifest
import android.content.ComponentName
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
private const val LIVE_PREVIEW_HD_1080_INTERVAL_MS = 200L
private const val CLIPBOARD_FOREGROUND_POLL_INTERVAL_MS = 1500L
private const val CLIPBOARD_BACKGROUND_POLL_INTERVAL_MS = 2500L
private const val CLIPBOARD_RETRY_DELAY_MS = 3000L
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
private const val CAMERA_QUALITY_MODE_HD_720 = "hd_720"
private const val CAMERA_QUALITY_MODE_HD_720_FAST = "hd_720_fast"
private const val CAMERA_QUALITY_MODE_HD_1080 = "hd_1080"
private const val CAMERA_LIVE_MODE_SESSION = "session"
private const val CAMERA_LIVE_MODE_REOPEN = "reopen"
private const val CAMERA_PREVIEW_INTERVAL_720_MS = 300L
private const val CAMERA_PREVIEW_INTERVAL_720_FAST_MS = 100L
private const val CAMERA_PREVIEW_INTERVAL_1080_MS = 600L

private data class LivePreviewProfile(
    val modeId: String,
    val label: String,
    val quality: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val intervalMs: Long,
)

private val LIVE_PREVIEW_PROFILE_ORIGINAL =
    LivePreviewProfile(
        modeId = LIVE_PREVIEW_MODE_ORIGINAL,
        label = "Original",
        quality = 30,
        maxWidth = 0,
        maxHeight = 0,
        intervalMs = LIVE_PREVIEW_INTERVAL_MS,
    )

private val LIVE_PREVIEW_PROFILE_HD_1080 =
    LivePreviewProfile(
        modeId = LIVE_PREVIEW_MODE_HD_1080,
        label = "1080p",
        quality = 34,
        maxWidth = 1920,
        maxHeight = 1080,
        intervalMs = LIVE_PREVIEW_HD_1080_INTERVAL_MS,
    )

private fun resolveLivePreviewProfile(modeId: String?): LivePreviewProfile =
    when (modeId?.trim()?.lowercase()) {
        LIVE_PREVIEW_MODE_HD_1080 -> LIVE_PREVIEW_PROFILE_HD_1080
        else -> LIVE_PREVIEW_PROFILE_ORIGINAL
    }

private data class CameraQualityProfile(
    val modeId: String,
    val label: String,
    val jpegQuality: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val intervalMs: Long,
)

private val CAMERA_QUALITY_PROFILE_HD_720 =
    CameraQualityProfile(
        modeId = CAMERA_QUALITY_MODE_HD_720,
        label = "720p",
        jpegQuality = 72,
        maxWidth = 1280,
        maxHeight = 720,
        intervalMs = CAMERA_PREVIEW_INTERVAL_720_MS,
    )

private val CAMERA_QUALITY_PROFILE_HD_720_FAST =
    CameraQualityProfile(
        modeId = CAMERA_QUALITY_MODE_HD_720_FAST,
        label = "720p fast",
        jpegQuality = 62,
        maxWidth = 1280,
        maxHeight = 720,
        intervalMs = CAMERA_PREVIEW_INTERVAL_720_FAST_MS,
    )

private val CAMERA_QUALITY_PROFILE_HD_1080 =
    CameraQualityProfile(
        modeId = CAMERA_QUALITY_MODE_HD_1080,
        label = "1080p",
        jpegQuality = 76,
        maxWidth = 1920,
        maxHeight = 1080,
        intervalMs = CAMERA_PREVIEW_INTERVAL_1080_MS,
    )

private fun resolveCameraQualityProfile(modeId: String?): CameraQualityProfile =
    when (modeId?.trim()?.lowercase()) {
        CAMERA_QUALITY_MODE_HD_720_FAST -> CAMERA_QUALITY_PROFILE_HD_720_FAST
        CAMERA_QUALITY_MODE_HD_1080 -> CAMERA_QUALITY_PROFILE_HD_1080
        else -> CAMERA_QUALITY_PROFILE_HD_720
    }

private data class CameraLiveModeOption(
    val modeId: String,
    val label: String,
)

private val CAMERA_LIVE_MODE_OPTION_SESSION =
    CameraLiveModeOption(
        modeId = CAMERA_LIVE_MODE_SESSION,
        label = "Live session",
    )

private val CAMERA_LIVE_MODE_OPTION_REOPEN =
    CameraLiveModeOption(
        modeId = CAMERA_LIVE_MODE_REOPEN,
        label = "Frame by frame",
    )

private fun resolveCameraLiveModeOption(modeId: String?): CameraLiveModeOption =
    when (modeId?.trim()?.lowercase()) {
        CAMERA_LIVE_MODE_REOPEN -> CAMERA_LIVE_MODE_OPTION_REOPEN
        else -> CAMERA_LIVE_MODE_OPTION_SESSION
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
    private var lastCameraPreviewBytes: ByteArray? = null
    private var lastCameraPreviewFileName = "pc-camera.jpg"
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
    private var isCameraPreviewRunning = false
    private var isCameraPreviewInFlight = false
    private var activeCameraQualityProfile = CAMERA_QUALITY_PROFILE_HD_720
    private var activeCameraLiveModeOption = CAMERA_LIVE_MODE_OPTION_SESSION
    private var activeCameraPreviewPcId: String? = null
    private var availableCameraDevices: List<RemoteCameraDevice> = emptyList()
    private var selectedCameraId = ""
    private var isActivityResumed = false
    private var suppressClipboardCallback = false
    private var suppressClipboardSwitchCallback = false
    private var suppressBackgroundClipboardSwitchCallback = false
    private var isClipboardDispatchInFlight = false
    private var lastClipboardReadErrorMessage: String? = null
    private var lastLocalClipboardSignature = ""
    private var unreadNotificationCount = 0
    private var currentSectionTabIndex = 0
    private var workerSupportsR2: Boolean? = null
    private var suppressCameraSelectionCallback = false
    private var suppressCameraQualityCallback = false
    private var suppressCameraLiveModeCallback = false
    private var suppressCameraMirrorCallback = false
    private var activeShortcutId: String? = null
    private var isShortcutReorderMode = false
    private var draggingShortcutId: String? = null
    private var shortcutDropHandled = false
    private var lastRenderedNotifications: List<RemoteNotificationItem> = emptyList()
    private val shortcutItems = mutableListOf<ShortcutItem>()
    private val shortcutTypeOptions = listOf(
        ShortcutTypeOption(
            id = "application",
            label = "App / shortcut",
            targetHint = "Example: chrome or C:\\Program Files\\App\\app.exe",
            helperText = "Use this for EXE, LNK, BAT, CMD, COM, or shortcuts such as chrome / opera / spotify / discord.",
            supportsArguments = true,
            pickerMode = ShortcutPickerMode.APPLICATION,
        ),
        ShortcutTypeOption(
            id = "folder",
            label = "Folder",
            targetHint = "Example: C:\\Users\\Efe\\Desktop",
            helperText = "When tapped, the selected folder opens in Windows Explorer.",
            supportsArguments = false,
            pickerMode = ShortcutPickerMode.FOLDER,
        ),
        ShortcutTypeOption(
            id = "url",
            label = "Link / URL",
            targetHint = "Example: https://example.com or discord://",
            helperText = "HTTP links open in the default browser, and custom protocols open in the app that supports them.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "cmd",
            label = "CMD command",
            targetHint = "Example: ipconfig /all",
            helperText = "Opens Command Prompt and runs the command with /K.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "powershell",
            label = "PowerShell command",
            targetHint = "Example: Get-Process | Select-Object -First 10",
            helperText = "Opens a PowerShell window and runs the command with -NoExit.",
            supportsArguments = false,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "run",
            label = "Run command",
            targetHint = "Example: shell:startup, control, or ms-settings:display",
            helperText = "Use this for shell and system commands that can be entered into the Windows Run dialog.",
            supportsArguments = true,
            pickerMode = null,
        ),
        ShortcutTypeOption(
            id = "hotkey",
            label = "Key combination",
            targetHint = "",
            helperText = "Runs a single-stroke keyboard combination on the selected PC. Secure attention sequences such as Ctrl+Alt+Delete are not supported.",
            supportsArguments = false,
            pickerMode = null,
        ),
    )
    private val shortcutAccentOptions = listOf(
        ShortcutAccentOption("violet", "Purple", R.color.shortcut_accent_violet_fill, R.color.shortcut_accent_violet_stroke),
        ShortcutAccentOption("aqua", "Aqua", R.color.shortcut_accent_aqua_fill, R.color.shortcut_accent_aqua_stroke),
        ShortcutAccentOption("emerald", "Emerald", R.color.shortcut_accent_emerald_fill, R.color.shortcut_accent_emerald_stroke),
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
    private val cameraQualityOptions =
        listOf(CAMERA_QUALITY_PROFILE_HD_720, CAMERA_QUALITY_PROFILE_HD_720_FAST, CAMERA_QUALITY_PROFILE_HD_1080)
    private val cameraLiveModeOptions = listOf(CAMERA_LIVE_MODE_OPTION_SESSION, CAMERA_LIVE_MODE_OPTION_REOPEN)

    private val clipboardChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (suppressClipboardCallback || enabledClipboardSyncPcIds().isEmpty()) {
            return@OnPrimaryClipChangedListener
        }

        processLocalClipboardChange()
    }

    private val livePreviewRunnable = Runnable { pollLivePreview() }
    private val cameraPreviewRunnable = Runnable { pollCameraPreview() }
    private val clipboardRetryRunnable = Runnable {
        if (!shouldClipboardMonitoringBeActive()) {
            return@Runnable
        }

        processLocalClipboardChange()
    }
    private val localizationSweepRunnable = object : Runnable {
        override fun run() {
            if (!BuildConfig.FORCE_ENGLISH || !isActivityResumed) {
                return
            }

            localizeVisibleUi()
            mainHandler.postDelayed(this, 350L)
        }
    }
    private val clipboardPollRunnable = object : Runnable {
        override fun run() {
            if (!shouldClipboardMonitoringBeActive()) {
                return
            }

            processLocalClipboardChange()
            if (shouldClipboardMonitoringBeActive()) {
                mainHandler.postDelayed(this, currentClipboardPollIntervalMs())
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
            appendLog("File save was cancelled.")
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
        binding.notificationSettingsText.text = "Manage your notification preferences here."
        binding.clipboardSyncStatusText.text = "Select a PC first to use clipboard sync."
        binding.cameraStatusText.text = "Select a PC first for the camera."
        binding.livePreviewStatusText.text = "Select a PC first for live screen preview."
        setBackgroundClipboardMonitoringSwitchChecked(store.backgroundClipboardMonitoringEnabled)

        updateSelectedRemoteFile(null)
        updateSelectedProcess(null)
        updateCameraPreview()
        updateScreenshotActions()
        updateDragButton()
        setupCameraControls()
        updateCameraControls()
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

        if (BuildConfig.FORCE_ENGLISH) {
            binding.root.post { localizeVisibleUi() }
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
        if (store.getCameraPreviewEnabled(selectedPcIdOrNull()) && !isCameraPreviewRunning) {
            startCameraPreview(persistPreference = false)
        }
        if (BuildConfig.FORCE_ENGLISH) {
            mainHandler.removeCallbacks(localizationSweepRunnable)
            mainHandler.post(localizationSweepRunnable)
            localizeVisibleUi()
        }
    }

    override fun onPause() {
        isActivityResumed = false
        refreshClipboardMonitoringState(processImmediately = false)
        stopCameraPreview(updateStoredPreference = false)
        mainHandler.removeCallbacks(localizationSweepRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        stopLivePreview(updateStoredPreference = false)
        stopCameraPreview(updateStoredPreference = false)
        stopClipboardSync()
        mainHandler.removeCallbacks(localizationSweepRunnable)
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
            cameraPreviewBytes = lastCameraPreviewBytes,
            cameraPreviewFileName = lastCameraPreviewFileName,
            notifications = lastRenderedNotifications,
            unreadNotificationCount = unreadNotificationCount,
            availablePcs = availablePcs,
            availableCameraDevices = availableCameraDevices,
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
                title = "Home",
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
                    R.id.cameraHeading,
                    R.id.cameraHintText,
                    R.id.cameraSelectionHeading,
                    R.id.cameraSpinner,
                    R.id.refreshCameraListButton,
                    R.id.cameraQualityHeading,
                    R.id.cameraQualitySpinner,
                    R.id.cameraLiveModeHeading,
                    R.id.cameraLiveModeSpinner,
                    R.id.cameraMirrorSwitch,
                    R.id.cameraSnapshotButton,
                    R.id.startCameraPreviewButton,
                    R.id.stopCameraPreviewButton,
                    R.id.cameraStatusText,
                    R.id.cameraPreviewImage,
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
                title = "Shortcuts",
                container = binding.shortcutsSection,
                anchorIds = emptyList(),
            ),
            SectionSpec(
                title = "Files",
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
                title = "System",
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
                title = "Notifications",
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
                title = "Settings",
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

    private fun setBackgroundClipboardMonitoringSwitchChecked(isChecked: Boolean) {
        suppressBackgroundClipboardSwitchCallback = true
        binding.backgroundClipboardMonitoringSwitch.isChecked = isChecked
        suppressBackgroundClipboardSwitchCallback = false
    }

    private fun applySelectedPcScopedState(restoreLivePreview: Boolean) {
        val scopedPcId = selectedPcIdOrNull()
        val selectedPcName = store.pairedPcName.ifBlank { "Selected PC" }
        store.migrateLegacyPcScopedStateIfNeeded(scopedPcId)

        stopClipboardSync()
        stopLivePreview(updateStoredPreference = false)
        stopCameraPreview(updateStoredPreference = false)

        val clipboardEnabled = scopedPcId != null && store.getClipboardSyncEnabled(scopedPcId)
        val livePreviewEnabled = scopedPcId != null && store.getLivePreviewEnabled(scopedPcId)
        val cameraPreviewEnabled = scopedPcId != null && store.getCameraPreviewEnabled(scopedPcId)
        activeLivePreviewProfile =
            scopedPcId?.let { resolveLivePreviewProfile(store.getLivePreviewMode(it)) }
                ?: LIVE_PREVIEW_PROFILE_ORIGINAL
        activeCameraQualityProfile =
            scopedPcId?.let { resolveCameraQualityProfile(store.getCameraQualityMode(it)) }
                ?: CAMERA_QUALITY_PROFILE_HD_720
        activeCameraLiveModeOption =
            scopedPcId?.let { resolveCameraLiveModeOption(store.getCameraLiveMode(it)) }
                ?: CAMERA_LIVE_MODE_OPTION_SESSION
        selectedCameraId = scopedPcId?.let { store.getSelectedCameraId(it) }.orEmpty()
        currentRemotePath = scopedPcId?.let { store.getRemotePath(it) }.orEmpty()
        currentRemoteParentPath = null
        binding.filesPathInput.setText(currentRemotePath)
        setCameraMirrorSwitchChecked(scopedPcId != null && store.getCameraMirrorEnabled(scopedPcId))

        setClipboardSyncSwitchChecked(clipboardEnabled)
        setBackgroundClipboardMonitoringSwitchChecked(store.backgroundClipboardMonitoringEnabled)
        binding.clipboardSyncStatusText.text = when {
            scopedPcId == null -> "Select a PC first to use clipboard sync."
            clipboardEnabled -> "Clipboard sync is ready for $selectedPcName.${buildBackgroundClipboardModeSuffix()}"
            else -> "Clipboard sync is off for $selectedPcName."
        }
        binding.livePreviewStatusText.text = when {
            scopedPcId == null -> "Select a PC first for live screen preview."
            livePreviewEnabled -> "Live screen preview is ready for $selectedPcName (${activeLivePreviewProfile.label})."
            else -> "Live screen preview is off for $selectedPcName."
        }
        binding.cameraStatusText.text = when {
            scopedPcId == null -> t("Kamera icin once bir PC sec.")
            cameraPreviewEnabled -> t("$selectedPcName icin kamera canli izleme hazir (${activeCameraQualityProfile.label}, ${activeCameraLiveModeOption.label}).")
            else -> t("$selectedPcName icin kamera hazir. Bir kamera secip tek kare veya canli izleme baslat.")
        }

        shortcutItems.clear()
        activeShortcutId = null
        clearShortcutDragState(refreshGrid = false)
        shortcutItems += readStoredShortcutItems(scopedPcId)
        renderShortcutGrid()
        updateCameraSelectionViews()
        updateCameraPreview()
        updateCameraControls()
        updateLivePreviewButtons()

        if (enabledClipboardSyncPcIds().isNotEmpty()) {
            startClipboardSync()
        }
        if (restoreLivePreview && livePreviewEnabled) {
            startLivePreview(activeLivePreviewProfile, persistPreference = false)
        }
        if (scopedPcId != null) {
            refreshCameraList(showFeedback = false)
        } else {
            availableCameraDevices = emptyList()
            updateCameraSelectionViews()
        }
        if (restoreLivePreview && cameraPreviewEnabled) {
            startCameraPreview(persistPreference = false)
        }
    }

    private fun restoreRetainedUiState(state: RetainedUiState?) {
        if (state == null) {
            return
        }

        currentSectionTabIndex = state.selectedTabIndex
        lastScreenshotBytes = state.screenshotBytes
        lastScreenshotFileName = state.screenshotFileName
        lastCameraPreviewBytes = state.cameraPreviewBytes
        lastCameraPreviewFileName = state.cameraPreviewFileName
        availablePcs = state.availablePcs
        availableCameraDevices = state.availableCameraDevices
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
        updateCameraSelectionViews()
        updateCameraPreview()
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
        binding.notificationCenterHeading.text = "Last $limit notifications"
        if (lastRenderedNotifications.isEmpty()) {
            binding.notificationStatusText.text = "The last $limit notifications will appear here."
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
            "Initial check pending."
        } else {
            val nextCheck = store.backgroundPermissionLastCheckedAt + BACKGROUND_PERMISSION_RECHECK_MS
            "Next automatic check: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(nextCheck)}"
        }

        val limitationNote =
            "Note: because of Android 10+ and manufacturer restrictions, phone-to-PC clipboard sync cannot be guaranteed in the background. The app keeps trying, but the device may still block it."
        val manufacturerNote = if (isMiuiFamilyDevice()) {
            "On this device you may also need to enable Auto Start and set battery restrictions to Unlimited/None."
        } else {
            null
        }

        binding.backgroundPermissionStatusText.text = if (isGranted) {
            buildString {
                append("Background activity permission is enabled. Notifications, the live connection, and clipboard updates from the PC will work more reliably.")
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
                append("Background activity permission appears to be off. Battery optimization may weaken notifications, the live connection, and clipboard updates from the PC.")
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
                .setTitle("Background permission recommended")
                .setMessage(
                    buildString {
                        append("Exempting the app from battery optimization improves notifications, the live connection, and clipboard updates from the PC.")
                        append("\n\n")
                        append("Phone-to-PC clipboard sync is not guaranteed while other apps are in the foreground. This build can keep trying in the background, but Android or the device vendor may still block clipboard access.")
                        if (isMiuiFamilyDevice()) {
                            append("\n\n")
                            append("On Xiaomi/Redmi/POCO devices you may also need to enable Auto Start and set battery restrictions to Unlimited/None.")
                        }
                    },
                )
                .setPositiveButton("Open settings") { _, _ -> requestBackgroundPermission() }
                .setNegativeButton("Later", null)
                .create()
                .also { showLocalizedDialog(it) }
        }
    }

    private fun requestBackgroundPermission() {
        if (isBackgroundPermissionGranted()) {
            refreshBackgroundPermissionState(promptIfDue = false, forceTimestampUpdate = true)
            showToast("Background permission is already enabled.")
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
            showToast("The battery optimization screen could not be opened on this device.")
            return
        }

        startActivity(targetIntent)
        if (isMiuiFamilyDevice()) {
            showToast("On Xiaomi/Redmi/POCO devices you may also need to enable Auto Start and set battery restrictions to Unlimited/None.")
        }
    }

    private fun openAdditionalBackgroundSettings() {
        val targetIntent = buildAdditionalBackgroundSettingIntents().firstOrNull { intent ->
            runCatching { intent.resolveActivity(packageManager) != null }.getOrDefault(false)
        }

        if (targetIntent == null) {
            showToast("Extra background settings could not be opened on this device.")
            return
        }

        startActivity(targetIntent)
        showToast("Extra background settings opened.")
    }

    private fun buildAdditionalBackgroundSettingIntents(): List<Intent> {
        val packageUri = Uri.parse("package:$packageName")

        fun componentIntent(targetPackage: String, targetClass: String): Intent =
            Intent().apply {
                component = ComponentName(targetPackage, targetClass)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

        return listOf(
            componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            componentIntent("com.miui.securitycenter", "com.miui.appmanager.ApplicationsDetailsActivity").apply {
                putExtra("package_name", packageName)
            },
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            componentIntent("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"),
            componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        )
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
                "Worker file mode: waiting for Worker URL.",
                "Selected Worker: no URL entered.",
                R.color.text_secondary,
            )
            workerSupportsR2 == true -> Triple(
                "Worker file mode: R2 active. Large file transfer is ready.",
                "Selected Worker: supports R2.",
                R.color.success_green,
            )
            workerSupportsR2 == false -> Triple(
                "Worker file mode: Legacy. File limit: 256 KB.",
                "Selected Worker: no R2, legacy mode is active.",
                R.color.warning_amber,
            )
            else -> Triple(
                "Worker file mode: checking. Automatic fallback will be tried if needed.",
                "Selected Worker: checking R2 support.",
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
                        true -> "The Worker supports R2. File transfer will use advanced mode."
                        false -> "The Worker does not support R2. File transfer will use legacy mode."
                        null -> "The Worker file mode could not be determined. The app will try automatic fallback when needed."
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

    private fun setupCameraControls() {
        binding.cameraQualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameraQualityOptions.map { it.label },
        )
        binding.cameraLiveModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameraLiveModeOptions.map { it.label },
        )

        binding.cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCameraSelectionCallback || availableCameraDevices.isEmpty()) {
                    return
                }

                val device = availableCameraDevices.getOrNull(position) ?: return
                selectedCameraId = device.id
                selectedPcIdOrNull()?.let { scopedPcId -> store.setSelectedCameraId(scopedPcId, device.id) }
                updateCameraControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.cameraQualitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCameraQualityCallback) {
                    return
                }

                activeCameraQualityProfile = cameraQualityOptions.getOrElse(position) { CAMERA_QUALITY_PROFILE_HD_720 }
                selectedPcIdOrNull()?.let { scopedPcId -> store.setCameraQualityMode(scopedPcId, activeCameraQualityProfile.modeId) }
                updateCameraControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.cameraLiveModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressCameraLiveModeCallback) {
                    return
                }

                activeCameraLiveModeOption = cameraLiveModeOptions.getOrElse(position) { CAMERA_LIVE_MODE_OPTION_SESSION }
                selectedPcIdOrNull()?.let { scopedPcId -> store.setCameraLiveMode(scopedPcId, activeCameraLiveModeOption.modeId) }
                updateCameraControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        binding.cameraMirrorSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressCameraMirrorCallback) {
                return@setOnCheckedChangeListener
            }

            selectedPcIdOrNull()?.let { scopedPcId -> store.setCameraMirrorEnabled(scopedPcId, isChecked) }
            updateCameraPreview()
        }

        binding.refreshCameraListButton.setOnClickListener { refreshCameraList(showFeedback = true) }
        binding.cameraSnapshotButton.setOnClickListener { requestCameraSnapshot() }
        binding.startCameraPreviewButton.setOnClickListener { startCameraPreview() }
        binding.stopCameraPreviewButton.setOnClickListener { stopCameraPreview() }
        binding.cameraPreviewImage.setOnClickListener { openCameraPreviewDialog() }
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
                    "Reorder mode is enabled. Long press a tile and drag it."
                } else {
                    "Reorder mode turned off."
                },
            )
        }
        binding.shortcutGrid.setOnDragListener { _, event -> handleShortcutGridDrag(event) }
        binding.saveConfigButton.setOnClickListener {
            persistLocalConfig()
                appendLog("Local settings saved.")
            refreshWorkerCapabilities(showFeedback = true)
        }
        binding.requestBackgroundPermissionButton.setOnClickListener {
            requestBackgroundPermission()
        }
        binding.checkBackgroundPermissionButton.setOnClickListener {
            refreshBackgroundPermissionState(promptIfDue = false, forceTimestampUpdate = true)
        }
        binding.backgroundAdvancedSettingsButton.setOnClickListener {
            openAdditionalBackgroundSettings()
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
                    appendLog("Phone paired: ${result.pcName}")
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
        binding.shutdownButton.setOnClickListener { sendAwaitedCommand("Force shutdown the computer", "shutdown") }
        binding.restartButton.setOnClickListener { sendAwaitedCommand("Force restart the computer", "restart") }
        binding.logoffButton.setOnClickListener { sendAwaitedCommand("Log off", "logoff") }
        binding.unpairButton.setOnClickListener { unpairCurrentPc() }

        binding.playPauseButton.setOnClickListener {
            sendAwaitedCommand("Play/Pause", "media", JSONObject().put("action", "play-pause"))
        }
        binding.stopButton.setOnClickListener {
            sendAwaitedCommand("Stop", "media", JSONObject().put("action", "stop"))
        }
        binding.nextTrackButton.setOnClickListener {
            sendAwaitedCommand("Next", "media", JSONObject().put("action", "next"))
        }
        binding.previousTrackButton.setOnClickListener {
            sendAwaitedCommand("Previous", "media", JSONObject().put("action", "previous"))
        }
        binding.volumeUpButton.setOnClickListener {
            sendAwaitedCommand("Volume up", "volume", JSONObject().put("action", "up").put("steps", 2))
        }
        binding.volumeDownButton.setOnClickListener {
            sendAwaitedCommand("Volume down", "volume", JSONObject().put("action", "down").put("steps", 2))
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
                showToast("Select a custom app path first.")
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
        binding.backgroundClipboardMonitoringSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressBackgroundClipboardSwitchCallback) {
                return@setOnCheckedChangeListener
            }

            store.backgroundClipboardMonitoringEnabled = isChecked
            if (enabledClipboardSyncPcIds().isNotEmpty()) {
                refreshClipboardMonitoringState(processImmediately = false)
                binding.clipboardSyncStatusText.text = buildClipboardSyncActiveStatus(enabledClipboardSyncPcIds())
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
            .setTitle("First use")
            .setMessage("Are you using this app for the first time?")
            .setCancelable(false)
            .setNegativeButton("No") { _, _ ->
                store.hasSeenFirstRunPrompt = true
            }
            .setPositiveButton("Yes, this is my first time") { _, _ ->
                store.hasSeenFirstRunPrompt = true
                openGettingStartedGuide()
            }
            .create()
            .also { showLocalizedDialog(it) }
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
            .setTitle("Initial setup guide")
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .create()
            .also { showLocalizedDialog(it) }
    }

    private fun registerTokenIfPossible() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || store.fcmToken.isBlank()) {
            return
        }

        runInBackground {
            runCatching {
                api.registerToken(store.workerUrl, store.ownerToken, store.deviceName, store.fcmToken)
            }.onSuccess {
                runOnUiThread { appendLog("The token was reported to the Worker.") }
            }.onFailure { error ->
                runOnUiThread { appendLog("The token could not be updated: ${friendlyErrorMessage(error.message, "The token could not be updated.")}") }
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
                    binding.statusText.text = "No paired PC found."
                    renderUsageSummaryPlaceholder()
                    renderPcSummary(emptyList())
                    appendLog("No PC is linked to this account.")
                    localizeVisibleUi()
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
                    append(" | latest event: ${selectedPc.lastEventType}")
                    if (selectedPc.lastSeenAt > 0L) {
                        append('\n')
                        append("Last seen: ")
                        append(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(selectedPc.lastSeenAt))
                    }
                }
                refreshUsageSummary()
                loadNotificationSettings()
                loadNotificationCenter()
                appendLog("PC status refreshed.")
                localizeVisibleUi()
            }
        }
    }

    private fun renderPcSummary(pcs: List<RemotePcSummary>) {
        availablePcs = pcs
        binding.pcListSummaryText.text = if (pcs.isEmpty()) {
            "No PC is linked to this account."
        } else {
            pcs.joinToString(separator = "\n") { pc ->
                val selectedLabel = if (pc.id == store.pairedPcId) " [SELECTED]" else ""
                "${pc.name} • ${pc.status} • ${pc.lastEventType}$selectedLabel"
            }
        }
        binding.selectPcButton.isEnabled = pcs.size > 1
        binding.unpairButton.isEnabled = store.pairedPcId.isNotBlank()
        localizeVisibleUi()
    }

    private fun updateSelectedPcDisplay(name: String?, status: String?) {
        binding.selectedPcText.text = if (name.isNullOrBlank()) {
            "Selected PC: -"
        } else {
            "Selected PC: $name"
        }
        renderSelectedPcStatusBadge(status)
        localizeVisibleUi()
    }

    private fun renderSelectedPcStatusBadge(status: String?) {
        if (status.isNullOrBlank()) {
            binding.selectedPcStatusBadge.visibility = View.GONE
            return
        }

        val normalized = status.trim().lowercase()
        val (label, colorRes) = when {
            normalized.contains("online") || normalized.contains("connected") -> "Online" to R.color.success_green
            normalized.contains("sleep") || normalized.contains("away") -> "Sleeping" to R.color.warning_amber
            normalized.contains("offline") || normalized.contains("disconnected") -> "Offline" to R.color.status_offline
            normalized.contains("starting") || normalized.contains("busy") -> "Preparing" to R.color.warning_amber
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
            showToast("No PC is available to select. Refresh the status first.")
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
            .setTitle("Change selected PC")
            .setItems(labels) { _, index ->
                val selected = availablePcs[index]
                store.pairedPcId = selected.id
                store.pairedPcName = selected.name
                updateSelectedPcDisplay(selected.name, selected.status)
                applySelectedPcScopedState(restoreLivePreview = true)
                renderPcSummary(availablePcs)
                appendLog("PC selected: ${selected.name}")
                loadNotificationCenter()
                refreshUsageSummary()
            }
            .setNegativeButton("Close", null)
            .create()
            .also { showLocalizedDialog(it) }
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
                binding.notificationSettingsText.text = "Notification settings loaded."
            }
        }
    }

    private fun saveNotificationSettings() {
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank()) {
            showToast("Pair with a PC first.")
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
                binding.notificationSettingsText.text = "Notification settings saved."
                appendLog("Notification settings updated.")
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
            binding.notificationStatusText.text = "Last $displayLimit notifications • unread: $unreadCount"
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
                    text = "No recent notifications."
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
            appendLog("Unread notification count: $unreadCount")
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
            binding.notificationStatusText.text = "All notifications were marked as read."
                loadNotificationCenter()
            }
        }
    }

    private fun unpairCurrentPc() {
        if (store.ownerToken.isBlank() || store.pairedPcId.isBlank()) {
            showToast("No pairing was found to remove.")
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Remove pairing")
            .setMessage("Do you want to remove the phone pairing for ${store.pairedPcName.ifBlank { "this PC" }}?")
            .setPositiveButton("Remove") { _, _ ->
                persistLocalConfig()
                runInBackground {
                    val pc = api.unpairPc(store.workerUrl, store.ownerToken, store.pairedPcId)
                    runOnUiThread {
                        appendLog("${pc.optString("name", "PC")} pairing was removed from the phone.")
                        binding.statusText.text = "${pc.optString("name", "PC")} was removed. Refreshing remaining devices."
                        refreshPcState()
                        refreshUsageSummary()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { showLocalizedDialog(it) }
    }

    private fun loadProcessList(scope: String) {
        sendAwaitedCommand(
            label = if (scope == "all") "List all processes" else "List open apps",
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
                        "No open apps were found."
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
                    "All processes (${processes.size})"
            } else {
                    "Open / visible apps (${processes.size})"
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
                    text = "Close"
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
            showToast("Select a process from the list first.")
            return
        }

        val payload = JSONObject().put("processName", processName)
        if (processId != null) {
            payload.put("processId", processId)
        }

        sendAwaitedCommand(
                label = "Close app",
            type = "app-kill",
            payload = payload,
        )
    }

    private fun loadSystemInfo() {
            sendAwaitedCommand("System information", "system-info") { payload ->
            val memory = payload.optJSONObject("memory") ?: JSONObject()
            val drives = payload.optJSONArray("drives") ?: JSONArray()
            val networks = payload.optJSONArray("networkAddresses") ?: JSONArray()
            val localIps = if (networks.length() == 0) "-" else (0 until networks.length()).joinToString { networks.optString(it) }
            val externalIp = payload.optString("externalIpAddress", "").ifBlank { "-" }
            val osName = payload.optString("osName", payload.optString("osDescription", "-"))
            val osVersion = payload.optString("osVersion", "").ifBlank { payload.optString("osBuild", "") }
            binding.systemInfoText.text = buildString {
                append("Makine: ${payload.optString("machineName", "-")}\n")
                append("Kullanici: ${payload.optString("domainName", "")}\\${payload.optString("userName", "-")}\n")
                append("OS: $osName\n")
                if (osVersion.isNotBlank()) {
                    append("OS surumu: $osVersion\n")
                }
                append("Islemci cekirdegi: ${payload.optInt("processorCount", 0)}\n")
                append("RAM: ${formatFileSize(memory.optLong("usedBytes"))} / ${formatFileSize(memory.optLong("totalBytes"))}")
                append(" (${memory.optInt("memoryLoadPercent", 0)}%)\n")
                append("Surec sayisi: ${payload.optJSONObject("processes")?.optInt("total", 0) ?: 0}\n")
                append("Yerel IP'ler: $localIps\n")
                append("Dis IP: $externalIp\n")
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
                    showToast("In reorder mode, press and hold a tile to move it.")
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
        binding.shortcutReorderButton.text = if (isShortcutReorderMode) "Reordering on" else "Reorder"
        binding.shortcutsModeHintText.text = if (isShortcutReorderMode) {
            "Reorder mode is on. Press and hold a tile to move it. Drop it on the plus tile to move it to the end."
        } else {
            "Tap a tile to run it on the selected PC. Links open in the default browser or in the app that handles that protocol."
        }
        localizeVisibleUi()
    }

    private fun startShortcutDrag(view: View, item: ShortcutItem): Boolean {
        draggingShortcutId = item.id
        shortcutDropHandled = false
        view.animate().scaleX(0.92f).scaleY(0.92f).alpha(0.45f).setDuration(90L).start()
        val dragData = ClipData.newPlainText("shortcut-id", item.id)
        val started = view.startDragAndDrop(dragData, View.DragShadowBuilder(view), item.id, 0)
        if (!started) {
            clearShortcutDragState(refreshGrid = true)
            showToast("Shortcut dragging could not be started.")
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
                        showToast("Shortcut order updated.")
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
                    showToast("The shortcut was moved to the end of the list.")
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
                        showToast("The shortcut was moved to the end of the list.")
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
        appendLog("Shortcut order updated: ${movedItem.title}")
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
            .setNegativeButton("Close", null)
            .create()

        rowOne.addView(createShortcutActionButton("Run") {
            dialog.dismiss()
            runShortcutItem(item)
        })
        rowOne.addView(createShortcutActionButton("Edit", withMarginStart = true) {
            dialog.dismiss()
            showShortcutEditor(item)
        })
        rowTwo.addView(createShortcutActionButton("Refresh icon") {
            dialog.dismiss()
            refreshShortcutIcon(item)
        })
        rowTwo.addView(createShortcutActionButton("Delete", withMarginStart = true) {
            dialog.dismiss()
            confirmShortcutDeletion(item)
        })

        showLocalizedDialog(dialog)
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
            .setTitle(if (existing == null) "Add shortcut" else "Edit shortcut")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton(if (existing == null) "Save" else "Update", null)
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
                    showToast("Select a primary key for the hotkey.")
                    return@setOnClickListener
                }
                if (option.id == "hotkey" && isBlockedHotkeyCombination(hotkeyKeys)) {
                    showToast("Ctrl + Alt + Delete desteklenmiyor.")
                    return@setOnClickListener
                }

                val target = if (option.id == "hotkey") "" else targetInput.text.toString().trim()
                if (option.id != "hotkey" && target.isBlank()) {
                    showToast("Shortcut target cannot be empty.")
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

        showLocalizedDialog(dialog)
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
                        "The shortcut was saved but the icon information could not be retrieved: ${
                            friendlyErrorMessage(inspectedResult.error, "unknown error")
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
        appendLog("Shortcut ready: ${shortcut.title}")
    }

    private fun confirmShortcutDeletion(item: ShortcutItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete shortcut")
            .setMessage("Remove ${item.title}?")
            .setPositiveButton("Delete") { _, _ ->
                val removed = shortcutItems.removeAll { it.id == item.id }
                activeShortcutId = activeShortcutId.takeUnless { it == item.id }
                if (!removed) {
                    showToast("The shortcut was not found in the list.")
                    return@setPositiveButton
                }

                persistShortcutItems()
                renderShortcutGrid()
                appendLog("Shortcut deleted: ${item.title}")
                showToast("${item.title} was removed.")
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { showLocalizedDialog(it) }
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
                    showToast(friendlyErrorMessage(error.message, "The shortcut icon could not be refreshed."))
                }
                return@execute
            }

            runOnUiThread {
                if (!inspected.success) {
                    showToast(friendlyErrorMessage(inspected.error, "The shortcut icon could not be refreshed."))
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
            .setNegativeButton("Close", null)
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

        showLocalizedDialog(dialog)
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
            showToast("Enter the Worker settings and select a PC first for shortcuts.")
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
        updateTransferProgress("Download file", 5, "Preparing R2 area")
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
                renderTransferComplete("The file is ready. Choose where to save it.")
                appendLog("The file was downloaded and is ready to be saved on the phone.")
            refreshUsageSummary()
            createDocumentLauncher.launch(pendingDownloadFileName)
        }
    }

    private fun downloadRemoteFileLegacy(path: String) {
        updateTransferProgress("Download file", 10, "Preparing in legacy file mode")
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
                renderTransferComplete("The legacy file is ready. Choose where to save it.")
                appendLog("The file was downloaded in legacy mode and is ready to be saved on the phone.")
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
            .setTitle("Delete selected item")
            .setMessage("Permanently delete ${entry.name}?")
            .setPositiveButton("Delete") { _, _ ->
                sendAwaitedCommand(
                    label = "Delete item",
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
            .setNegativeButton("Cancel", null)
            .create()
            .also { showLocalizedDialog(it) }
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
            .setTitle("Delete selected items")
            .setMessage("Delete ${entries.size} items?")
            .setPositiveButton("Delete") { _, _ ->
                persistLocalConfig()
                runInBackground {
                    entries.forEachIndexed { index, entry ->
                        updateTransferProgress(
                            "Bulk delete",
                            (((index + 1) / entries.size.toFloat()) * 100).roundToInt(),
                            "Deleting ${entry.name}",
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
                            throw IllegalStateException(friendlyErrorMessage(result.error, "${entry.name} could not be deleted."))
                        }
                    }

                    runOnUiThread {
                        renderTransferComplete("Bulk delete completed.")
                        clearRemoteSelection()
                        listRemoteFiles(currentRemotePath.ifBlank { binding.filesPathInput.text.toString().trim() })
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            .also { showLocalizedDialog(it) }
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
        updateTransferProgress("File preview", 5, "Preparing preview file")
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
        updateTransferProgress("File preview", 15, "Preparing in legacy file mode")
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
        binding.filePreviewTitleText.text = "Preview: $title"
        binding.filePreviewImage.visibility = View.GONE
        binding.filePreviewImage.setImageDrawable(null)
        binding.filePreviewText.text = text
        localizeVisibleUi()
    }

    private fun renderFilePreviewPlaceholder() {
        binding.filePreviewTitleText.text = "File preview"
        binding.filePreviewImage.visibility = View.GONE
        binding.filePreviewImage.setImageDrawable(null)
        binding.filePreviewText.text = "A quick preview of the selected file appears here."
        localizeVisibleUi()
    }

    private fun renderTransferIdleState() {
        binding.transferProgressBar.progress = 0
        binding.transferStatusText.text = "Waiting for transfer."
        localizeVisibleUi()
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
        binding.transferStatusText.text = friendlyErrorMessage(message, "A file transfer error occurred.")
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

    private fun setCameraMirrorSwitchChecked(isChecked: Boolean) {
        suppressCameraMirrorCallback = true
        binding.cameraMirrorSwitch.isChecked = isChecked
        suppressCameraMirrorCallback = false
    }

    private fun updateCameraSelectionViews() {
        val cameraNames = if (availableCameraDevices.isEmpty()) {
            listOf("No camera found")
        } else {
            availableCameraDevices.map { it.name }
        }
        binding.cameraSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            cameraNames,
        )

        suppressCameraSelectionCallback = true
        if (availableCameraDevices.isEmpty()) {
            binding.cameraSpinner.setSelection(0, false)
            selectedCameraId = ""
        } else {
            val resolvedCameraId = selectedCameraId.takeIf { currentId -> availableCameraDevices.any { it.id == currentId } }
                ?: availableCameraDevices.first().id
            selectedCameraId = resolvedCameraId
            val selectedIndex = availableCameraDevices.indexOfFirst { it.id == resolvedCameraId }.coerceAtLeast(0)
            binding.cameraSpinner.setSelection(selectedIndex, false)
            selectedPcIdOrNull()?.let { scopedPcId -> store.setSelectedCameraId(scopedPcId, resolvedCameraId) }
        }
        suppressCameraSelectionCallback = false

        suppressCameraQualityCallback = true
        binding.cameraQualitySpinner.setSelection(cameraQualityOptions.indexOfFirst { it.modeId == activeCameraQualityProfile.modeId }.coerceAtLeast(0), false)
        suppressCameraQualityCallback = false

        suppressCameraLiveModeCallback = true
        binding.cameraLiveModeSpinner.setSelection(cameraLiveModeOptions.indexOfFirst { it.modeId == activeCameraLiveModeOption.modeId }.coerceAtLeast(0), false)
        suppressCameraLiveModeCallback = false
    }

    private fun updateCameraControls() {
        val hasPc = selectedPcIdOrNull() != null
        val hasCamera = availableCameraDevices.isNotEmpty() && selectedCameraId.isNotBlank()
        binding.cameraSpinner.isEnabled = hasPc && !isCameraPreviewRunning && availableCameraDevices.isNotEmpty()
        binding.refreshCameraListButton.isEnabled = hasPc && !isCameraPreviewRunning
        binding.cameraQualitySpinner.isEnabled = hasPc && !isCameraPreviewRunning
        binding.cameraLiveModeSpinner.isEnabled = hasPc && !isCameraPreviewRunning
        binding.cameraMirrorSwitch.isEnabled = hasPc
        binding.cameraSnapshotButton.isEnabled = hasPc && hasCamera && !isCameraPreviewRunning
        binding.startCameraPreviewButton.isEnabled = hasPc && hasCamera && !isCameraPreviewRunning
        binding.stopCameraPreviewButton.isEnabled = isCameraPreviewRunning
    }

    private fun updateCameraPreview() {
        val bytes = lastCameraPreviewBytes
        if (bytes == null) {
            binding.cameraPreviewImage.setImageDrawable(null)
            binding.cameraPreviewImage.scaleX = 1f
            updateCameraControls()
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            binding.cameraPreviewImage.setImageDrawable(null)
            lastCameraPreviewBytes = null
            binding.cameraPreviewImage.scaleX = 1f
        } else {
            binding.cameraPreviewImage.setImageBitmap(bitmap)
            binding.cameraPreviewImage.scaleX = if (binding.cameraMirrorSwitch.isChecked) -1f else 1f
        }
        updateCameraControls()
    }

    private fun refreshCameraList(showFeedback: Boolean) {
        val scopedPcId = selectedPcIdOrNull()
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || scopedPcId == null) {
            binding.cameraStatusText.text = t("Kamera icin once bir PC sec.")
            availableCameraDevices = emptyList()
            selectedCameraId = ""
            updateCameraSelectionViews()
            updateCameraControls()
            if (showFeedback) {
                showToast("Kamera listesi icin once bir PC sec.")
            }
            return
        }

        binding.cameraStatusText.text = t("Kamera listesi aliniyor...")
        updateCameraControls()
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = scopedPcId,
                type = "camera-list",
                timeoutMs = 18_000,
                pollIntervalMs = 250,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                if (result.success) {
                    availableCameraDevices = result.payload.optJSONArray("cameras")
                        ?.let { camerasJson ->
                            buildList {
                                for (index in 0 until camerasJson.length()) {
                                    val item = camerasJson.optJSONObject(index) ?: continue
                                    add(
                                        RemoteCameraDevice(
                                            id = item.optString("id"),
                                            name = item.optString("name").ifBlank { "Camera ${index + 1}" },
                                        ),
                                    )
                                }
                            }
                        }
                        .orEmpty()
                    updateCameraSelectionViews()
                    binding.cameraStatusText.text = if (availableCameraDevices.isEmpty()) {
                        t("Bu PC'de kullanilabilir kamera bulunamadi.")
                    } else {
                        t("Kamera listesi hazir. ${availableCameraDevices.size} kamera bulundu.")
                    }
                    if (showFeedback) {
                        appendLog(binding.cameraStatusText.text.toString())
                    }
                } else {
                    availableCameraDevices = emptyList()
                    selectedCameraId = ""
                    updateCameraSelectionViews()
                    binding.cameraStatusText.text = t(friendlyErrorMessage(result.error, "Kamera listesi alinamadi."))
                }
                updateCameraControls()
            }
        }
    }

    private fun requestCameraSnapshot() {
        val scopedPcId = selectedPcIdOrNull()
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || scopedPcId == null) {
            showToast("Tek kare icin once bir PC sec.")
            return
        }
        if (selectedCameraId.isBlank()) {
            showToast("Once listeden bir kamera sec.")
            return
        }

        binding.cameraStatusText.text = t("Kameradan tek kare aliniyor...")
        updateCameraControls()
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = scopedPcId,
                type = "camera-snapshot",
                payload = buildCameraPayload(),
                timeoutMs = 20_000,
                pollIntervalMs = 250,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                if (result.success) {
                    applyCameraPreviewResult(
                        payload = result.payload,
                        activeLabel = "Tek kare hazir",
                        filePrefix = "pc-camera",
                    )
                } else {
                    binding.cameraStatusText.text = t(friendlyErrorMessage(result.error, "Kamera tek kare hatasi."))
                }
                updateCameraControls()
            }
        }
    }

    private fun startCameraPreview(persistPreference: Boolean = true) {
        val scopedPcId = selectedPcIdOrNull()
        if (store.workerUrl.isBlank() || store.ownerToken.isBlank() || scopedPcId == null) {
            showToast("Canli kamera icin once bir PC sec.")
            return
        }
        if (selectedCameraId.isBlank()) {
            showToast("Once listeden bir kamera sec.")
            return
        }

        if (persistPreference) {
            store.setCameraPreviewEnabled(scopedPcId, true)
            store.setCameraQualityMode(scopedPcId, activeCameraQualityProfile.modeId)
            store.setCameraLiveMode(scopedPcId, activeCameraLiveModeOption.modeId)
            store.setSelectedCameraId(scopedPcId, selectedCameraId)
        }

        isCameraPreviewRunning = true
        isCameraPreviewInFlight = false
        activeCameraPreviewPcId = scopedPcId
        binding.cameraStatusText.text = t("Kamera canli izleme baslatiliyor (${activeCameraQualityProfile.label}, ${activeCameraLiveModeOption.label}).")
        updateCameraControls()
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = scopedPcId,
                type = "camera-live-start",
                payload = buildCameraPayload(),
                timeoutMs = 20_000,
                pollIntervalMs = 250,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                if (result.success) {
                    binding.cameraStatusText.text = t(
                        "Kamera canli izleme acik • ${result.payload.optString("cameraName", resolveSelectedCameraName())} • ${activeCameraQualityProfile.label} • ${activeCameraLiveModeOption.label}",
                    )
                    mainHandler.removeCallbacks(cameraPreviewRunnable)
                    mainHandler.post(cameraPreviewRunnable)
                } else {
                    isCameraPreviewRunning = false
                    isCameraPreviewInFlight = false
                    activeCameraPreviewPcId = null
                    if (persistPreference && scopedPcId == selectedPcIdOrNull()) {
                        store.setCameraPreviewEnabled(scopedPcId, false)
                    }
                    binding.cameraStatusText.text = t(friendlyErrorMessage(result.error, "Kamera canli izleme baslatilamadi."))
                    updateCameraControls()
                }
            }
        }
    }

    private fun pollCameraPreview() {
        if (!isCameraPreviewRunning || store.workerUrl.isBlank() || store.ownerToken.isBlank() || activeCameraPreviewPcId.isNullOrBlank()) {
            return
        }

        if (isCameraPreviewInFlight) {
            mainHandler.postDelayed(cameraPreviewRunnable, activeCameraQualityProfile.intervalMs)
            return
        }

        isCameraPreviewInFlight = true
        runInBackground {
            val command = api.sendCommandAndAwaitResult(
                workerUrl = store.workerUrl,
                ownerToken = store.ownerToken,
                pcId = activeCameraPreviewPcId.orEmpty(),
                type = "camera-live-frame",
                payload = buildCameraPayload(),
                timeoutMs = 20_000,
                pollIntervalMs = 250,
            )
            val result = parseCommandResult(command)

            runOnUiThread {
                isCameraPreviewInFlight = false
                if (result.success) {
                    applyCameraPreviewResult(
                        payload = result.payload,
                        activeLabel = "Kamera canli izleme hazir",
                        filePrefix = "pc-camera-live",
                    )
                } else {
                    binding.cameraStatusText.text = t(friendlyErrorMessage(result.error, "Kamera canli izleme hatasi."))
                }

                if (isCameraPreviewRunning) {
                    mainHandler.postDelayed(cameraPreviewRunnable, activeCameraQualityProfile.intervalMs)
                }
            }
        }
    }

    private fun stopCameraPreview(updateStoredPreference: Boolean = true, requestRemoteStop: Boolean = true) {
        val livePcId = activeCameraPreviewPcId
        val currentPcId = selectedPcIdOrNull()
        if (updateStoredPreference && currentPcId != null) {
            store.setCameraPreviewEnabled(currentPcId, false)
        }

        isCameraPreviewRunning = false
        isCameraPreviewInFlight = false
        activeCameraPreviewPcId = null
        mainHandler.removeCallbacks(cameraPreviewRunnable)
        binding.cameraStatusText.text = t("Kamera kapali.")
        updateCameraControls()

        if (requestRemoteStop && livePcId != null && store.workerUrl.isNotBlank() && store.ownerToken.isNotBlank()) {
            runInBackground {
                runCatching {
                    api.sendCommand(
                        workerUrl = store.workerUrl,
                        ownerToken = store.ownerToken,
                        pcId = livePcId,
                        type = "camera-live-stop",
                    )
                }
            }
        }
    }

    private fun buildCameraPayload(): JSONObject {
        return JSONObject()
            .put("cameraId", selectedCameraId)
            .put("maxWidth", activeCameraQualityProfile.maxWidth)
            .put("maxHeight", activeCameraQualityProfile.maxHeight)
            .put("quality", activeCameraQualityProfile.jpegQuality)
            .put("liveMode", activeCameraLiveModeOption.modeId)
    }

    private fun applyCameraPreviewResult(payload: JSONObject, activeLabel: String, filePrefix: String) {
        val base64Image = payload.optString("imageBase64", "")
        if (base64Image.isBlank()) {
            binding.cameraStatusText.text = t("Kamera karesi alinamadi.")
            return
        }

        lastCameraPreviewBytes = Base64.decode(base64Image, Base64.DEFAULT)
        lastCameraPreviewFileName = "$filePrefix-${System.currentTimeMillis()}.jpg"
        updateCameraPreview()
        val cameraName = payload.optString("cameraName", resolveSelectedCameraName())
        binding.cameraStatusText.text = t(
            "$activeLabel • $cameraName • ${payload.optInt("width")} x ${payload.optInt("height")} • ${activeCameraQualityProfile.label}${if (binding.cameraMirrorSwitch.isChecked) " • Ayna" else ""}",
        )
    }

    private fun resolveSelectedCameraName(): String {
        return availableCameraDevices.firstOrNull { it.id == selectedCameraId }?.name ?: "Selected camera"
    }

    private fun renderUsageSummaryPlaceholder() {
        binding.usageSummaryText.text =
            "Estimated limit information appears after pairing. Note: the request count is approximate; there is no fixed daily data limit for R2."
        localizeVisibleUi()
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
            "Drag mode on"
        } else {
            "Drag mode off"
        }
        localizeVisibleUi()
    }

    private fun openCameraPreviewDialog() {
        openImageDialog(
            bytes = lastCameraPreviewBytes,
            emptyMessage = "Once kamera goruntusu al.",
            decodeErrorMessage = "Kamera goruntusu acilamadi.",
            dialogTitle = "PC Camera",
            mirrorHorizontally = binding.cameraMirrorSwitch.isChecked,
            onSave = ::saveCurrentCameraPreview,
        )
    }

    private fun openScreenshotDialog() {
        openImageDialog(
            bytes = lastScreenshotBytes,
            emptyMessage = "Once screenshot al.",
            decodeErrorMessage = "Screenshot acilamadi.",
            dialogTitle = "PC Screenshot",
            onSave = ::saveCurrentScreenshot,
        )
    }

    private fun saveCurrentScreenshot() {
        saveImageBytes(
            bytes = lastScreenshotBytes,
            fileName = lastScreenshotFileName,
            emptyMessage = "Once screenshot al.",
        )
    }

    private fun saveCurrentCameraPreview() {
        saveImageBytes(
            bytes = lastCameraPreviewBytes,
            fileName = lastCameraPreviewFileName,
            emptyMessage = "Once kamera goruntusu al.",
        )
    }

    private fun saveImageBytes(bytes: ByteArray?, fileName: String, emptyMessage: String) {
        if (bytes == null) {
            showToast(emptyMessage)
            return
        }

        pendingDownloadBytes = bytes
        pendingDownloadObjectKey = null
        pendingDownloadFileName = fileName
        createDocumentLauncher.launch(fileName)
    }

    private fun openImageDialog(
        bytes: ByteArray?,
        emptyMessage: String,
        decodeErrorMessage: String,
        dialogTitle: String,
        mirrorHorizontally: Boolean = false,
        onSave: () -> Unit,
    ) {
        if (bytes == null) {
            showToast(emptyMessage)
            return
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: run {
            showToast(decodeErrorMessage)
            return
        }

        val imageView = ZoomableImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72f).roundToInt(),
            )
            setBackgroundColor(Color.parseColor("#140E26"))
            setImageBitmap(bitmap)
            scaleX = if (mirrorHorizontally) -1f else 1f
            setPadding(12, 12, 12, 12)
        }

        AlertDialog.Builder(this)
            .setTitle(dialogTitle)
            .setView(imageView)
            .setPositiveButton("Close", null)
            .setNeutralButton("Save") { _, _ -> onSave() }
            .create()
            .also { showLocalizedDialog(it) }
    }

    private fun pollLivePreview() {
        if (!isLivePreviewRunning || store.workerUrl.isBlank() || store.ownerToken.isBlank() || store.pairedPcId.isBlank()) {
            return
        }

        if (isLivePreviewInFlight) {
            mainHandler.postDelayed(livePreviewRunnable, activeLivePreviewProfile.intervalMs)
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
                    mainHandler.postDelayed(livePreviewRunnable, activeLivePreviewProfile.intervalMs)
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

        refreshClipboardMonitoringState(processImmediately = true)
        binding.clipboardSyncStatusText.text = buildClipboardSyncActiveStatus(enabledPcIds)
    }

    private fun stopClipboardSync() {
        pauseClipboardMonitoring()
        binding.clipboardSyncStatusText.text = "Clipboard sync is off."
    }

    private fun refreshClipboardMonitoringState(processImmediately: Boolean) {
        pauseClipboardMonitoring()
        if (!shouldClipboardMonitoringBeActive()) {
            return
        }

        clipboardManager.addPrimaryClipChangedListener(clipboardChangedListener)
        if (processImmediately) {
            processLocalClipboardChange()
        }
        mainHandler.postDelayed(clipboardPollRunnable, currentClipboardPollIntervalMs())
    }

    private fun pauseClipboardMonitoring() {
        clipboardManager.removePrimaryClipChangedListener(clipboardChangedListener)
        mainHandler.removeCallbacks(clipboardPollRunnable)
        mainHandler.removeCallbacks(clipboardRetryRunnable)
    }

    private fun shouldClipboardMonitoringBeActive(): Boolean {
        val enabledPcIds = enabledClipboardSyncPcIds()
        return store.workerUrl.isNotBlank() &&
            store.ownerToken.isNotBlank() &&
            enabledPcIds.isNotEmpty() &&
            (isActivityResumed || store.backgroundClipboardMonitoringEnabled)
    }

    private fun currentClipboardPollIntervalMs(): Long {
        return if (isActivityResumed) {
            CLIPBOARD_FOREGROUND_POLL_INTERVAL_MS
        } else {
            CLIPBOARD_BACKGROUND_POLL_INTERVAL_MS
        }
    }

    private fun scheduleClipboardRetry(delayMs: Long = CLIPBOARD_RETRY_DELAY_MS) {
        mainHandler.removeCallbacks(clipboardRetryRunnable)
        if (shouldClipboardMonitoringBeActive()) {
            mainHandler.postDelayed(clipboardRetryRunnable, delayMs)
        }
    }

    private fun readLocalClipboardTextSafely(): String? {
        return runCatching {
            clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        }.onSuccess {
            lastClipboardReadErrorMessage = null
        }.getOrElse { error ->
            val message = friendlyErrorMessage(error.message, "Local clipboard could not be read.")
            if (message != lastClipboardReadErrorMessage) {
                lastClipboardReadErrorMessage = message
                appendLog(message)
            }
            binding.clipboardSyncStatusText.text =
                "The local clipboard cannot be read right now. Android or the device may be blocking background clipboard access."
            null
        }
    }

    private fun enabledClipboardSyncPcIds(): Set<String> = store.getEnabledClipboardSyncPcIds()

    private fun processLocalClipboardChange() {
        val enabledPcIds = enabledClipboardSyncPcIds()
        if (enabledPcIds.isEmpty()) {
            return
        }

        val clipText = readLocalClipboardTextSafely() ?: return
        val clipSignature = clipboardSignature(clipText)
        val suppressedPcIds = enabledPcIds.filter { store.shouldSuppressClipboardEcho(it, clipText) }
        if (suppressedPcIds.isNotEmpty()) {
            lastLocalClipboardSignature = clipSignature
            store.lastLocalClipboardSignature = clipSignature
            suppressedPcIds.forEach { store.clearIncomingClipboardMarker(it) }
            binding.clipboardInput.setText(clipText)
            binding.clipboardSyncStatusText.text = "PC clipboard received on the phone."
            return
        }

        if (clipText.isBlank() || clipSignature == lastLocalClipboardSignature) {
            return
        }

        dispatchClipboardToPcs(enabledPcIds, clipText, clipSignature)
    }

    private fun buildClipboardSyncActiveStatus(enabledPcIds: Set<String>): String {
        return when (enabledPcIds.size) {
            0 -> "Clipboard sync is off."
            1 -> "Clipboard sync is on. Text copied on the phone is sent to ${resolvePcDisplayName(enabledPcIds.first())}. New PC clipboard updates arrive automatically.${buildBackgroundClipboardModeSuffix()}"
            else -> "Clipboard sync is on. Text copied on the phone is sent to ${enabledPcIds.size} active PCs. New PC clipboard updates arrive automatically.${buildBackgroundClipboardModeSuffix()}"
        }
    }

    private fun buildClipboardDispatchStatus(enabledPcIds: Set<String>): String {
        return when (enabledPcIds.size) {
            0 -> "Clipboard sync is off."
            1 -> "Local clipboard sent to ${resolvePcDisplayName(enabledPcIds.first())}."
            else -> "Local clipboard sent to ${enabledPcIds.size} active PCs."
        }
    }

    private fun buildBackgroundClipboardModeSuffix(): String {
        return if (store.backgroundClipboardMonitoringEnabled) {
            " Background attempt mode is on, but Android or the device vendor may still block clipboard tracking."
        } else {
            " Phone-to-PC tracking stops while the app is in the background."
        }
    }

    private fun dispatchClipboardToPcs(
        enabledPcIds: Set<String>,
        clipText: String,
        clipSignature: String,
    ) {
        if (isClipboardDispatchInFlight) {
            scheduleClipboardRetry(delayMs = 500L)
            return
        }

        isClipboardDispatchInFlight = true
        persistLocalConfig()
        runInBackground {
            val failures = mutableListOf<String>()
            enabledPcIds.forEach { pcId ->
                runCatching {
                    api.sendCommand(
                        workerUrl = store.workerUrl,
                        ownerToken = store.ownerToken,
                        pcId = pcId,
                        type = "clipboard-set",
                        payload = JSONObject().put("text", clipText),
                    )
                }.onFailure { error ->
                    failures += "${resolvePcDisplayName(pcId)}: ${friendlyErrorMessage(error.message)}"
                }
            }

            runOnUiThread {
                isClipboardDispatchInFlight = false
                if (failures.isEmpty()) {
                    lastLocalClipboardSignature = clipSignature
                    store.lastLocalClipboardSignature = clipSignature
                    binding.clipboardSyncStatusText.text = buildClipboardDispatchStatus(enabledPcIds)
                } else {
                    binding.clipboardSyncStatusText.text = "Clipboard delivery failed and will be retried."
                    appendLog("Clipboard delivery failed: ${failures.joinToString(" | ")}")
                    scheduleClipboardRetry()
                }

                if (shouldClipboardMonitoringBeActive()) {
                    mainHandler.postDelayed(clipboardRetryRunnable, 350L)
                }
            }
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
        binding.notificationSettingsText.text = "Manage your notification preferences here."
        updateNotificationLimitLabels()
        renderPcSummary(emptyList())
        binding.processListContainer.removeAllViews()
        binding.remoteFilesContainer.removeAllViews()
        binding.notificationCenterContainer.removeAllViews()
        binding.systemInfoText.text = "System information will appear here."
        lastRemoteEntries = emptyList()
        selectedRemoteEntries.clear()
        updateSelectedProcess(null)
        updateSelectedRemoteFile(null)
        renderTransferIdleState()
        renderFilePreviewPlaceholder()
        stopLivePreview(updateStoredPreference = false)
        stopCameraPreview(updateStoredPreference = false)
        stopClipboardSync()
        setClipboardSyncSwitchChecked(false)
        availableCameraDevices = emptyList()
        selectedCameraId = ""
        lastCameraPreviewBytes = null
        updateCameraSelectionViews()
        updateCameraPreview()
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
            return t(fallback)
        }

        val normalized = trimmed.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase()

        return when {
            lower.contains("\"error_code\":1101")
                || lower.contains("error code: 1101")
                || lower.contains("worker threw exception") ->
                t("Worker error (1101): a backend setup step may be missing. Run repair-d1-tables-helper.bat and redeploy the Worker.")

            lower.contains("no such host is known")
                || lower.contains("name or service not known")
                || lower.contains("bilinen böyle bir ana bilgisayar yok")
                || lower.contains("bilinen boyle bir ana bilgisayar yok") ->
                t("The Worker URL is incorrect or unreachable. Use the full https://...workers.dev address from the deploy output.")

            lower.contains("\"status\":401")
                || lower.contains("\"status\":403")
                || lower.contains("401 unauthorized")
                || lower.contains("403 forbidden") ->
                t("Authorization error: the session information may be invalid. Pair with the PC again.")

            lower.contains("\"status\":404")
                || lower.contains("404 not found") ->
                t("Resource not found: the Worker URL, selected PC, or related data may be missing.")

            lower.contains("timeout")
                || lower.contains("timed out")
                || lower.contains("zaman asimina ugradi") ->
                t("The request timed out. Check the connection and Worker status, then try again.")

            lower.contains("camera is in use")
                || lower.contains("device in use")
                || lower.contains("0xa00f4243")
                || lower.contains("kamera kullanimda")
                || lower.contains("kamera baska bir uygulama tarafindan kullaniliyor") ->
                t("Kamera baska bir uygulama tarafindan kullaniliyor olabilir.")

            lower.contains("camera access was denied")
                || lower.contains("access denied")
                || lower.contains("kamera erisimi reddedildi") ->
                t("Kameraya erisim reddedildi. Windows gizlilik ayarlarini kontrol et.")

            normalized.startsWith("{") || normalized.startsWith("[") -> t(fallback)
            else -> t(normalized)
        }
    }

    private fun appendLog(message: String) {
        binding.logText.append("• ${t(message)}\n")
        if (BuildConfig.FORCE_ENGLISH) {
            localizeVisibleUi()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, t(message), Toast.LENGTH_SHORT).show()
    }

    private fun t(text: String): String = UiTextLocalizer.translate(text)

    private fun localizeVisibleUi(vararg extraRoots: View?) {
        if (!BuildConfig.FORCE_ENGLISH) {
            return
        }

        UiTextLocalizer.applyToViewTree(binding.root)
        extraRoots.forEach { UiTextLocalizer.applyToViewTree(it) }
    }

    private fun showLocalizedDialog(dialog: AlertDialog) {
        dialog.show()
        localizeVisibleUi(dialog.window?.decorView)
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

    private data class RemoteCameraDevice(
        val id: String,
        val name: String,
    )

    private data class RetainedUiState(
        val selectedTabIndex: Int,
        val screenshotBytes: ByteArray?,
        val screenshotFileName: String,
        val cameraPreviewBytes: ByteArray?,
        val cameraPreviewFileName: String,
        val notifications: List<RemoteNotificationItem>,
        val unreadNotificationCount: Int,
        val availablePcs: List<RemotePcSummary>,
        val availableCameraDevices: List<RemoteCameraDevice>,
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



