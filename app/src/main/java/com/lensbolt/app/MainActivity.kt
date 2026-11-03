package com.lensbolt.app

// LensBolt receiver: point the back camera at a LensBolt screen anywhere in view; the file arrives SHA-256 verified
// in the app's private cache (Storage) and stays there until the user saves it (the system save screen), opens or
// shares it. Air-gapped: no network permission, nothing goes back to the sender.
//
// This class owns the camera and the native session and maps the session's status to a UiState; ReceiverScreen
// draws it. Stability model (as in cfc): the analyzer thread never waits. Every camera frame is offered to the
// native pool once; it is taken only while a decode worker is free, otherwise dropped (the fountain code does not
// care which frames arrive). A failed or crashed decode costs one frame, never the session.

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), ScreenHost {
    private companion object {
        val FULL_HD = Size(1920, 1080)          // resolves to the 1440x1080 class (4:3) on the phones measured
        const val VERSION_BANNER_KEY = -1        // the banner's key for the other-version words (fps keys are rates)
        const val BANNER_HOLD_MS = 2000L        // the sender-fps banner outlives its condition (ledger ruling)
        const val HINT_HOLD_MS = 600L           // a new guidance line must hold this long before it shows
        const val LOCK_HOLD_MS = 2000L          // Locked stays this long after the last read (no Aim/Locked flicker)
        const val MANIFEST_PITCH = 12           // the manifest's cells: its geometry says nothing about the data's
        const val REQ_CAMERA = 1
        const val CHANGING_MAX_MS = 4000L      // "Changing camera…" at most this long if no frame arrives
        const val PAGE = 1000                  // files per batch page (the core's maximum)
        const val PAGE_MS = 1000L              // the list is read at most this often while receiving
        const val BUSY_RETRY_MS = 250L         // the receiver was busy with a feed: the list is asked again this soon
        const val PUSH_MS = 200L               // the file list's screen follows the saves at most this often
    }

    private lateinit var preview: PreviewView
    private lateinit var screen: ReceiverScreen
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }

    private val analyzerExec = Executors.newSingleThreadExecutor()
    private val ioExec = Executors.newSingleThreadExecutor()
    // the Done screen's SHA-256 read (a 16 GB file takes a while): its own thread, so nothing waits behind it
    private val shaExec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var provider: ProcessCameraProvider? = null
    private var previewUse: Preview? = null

    // the native session, each in its own cacheDir/rx/s<n>; a teardown (which joins its threads, maybe while it
    // finishes a large file) runs on ioExec, never on the UI or camera thread (Sessions)
    private val sessions by lazy {
        Sessions(Storage.rxDir(this),
            // the fast b/w decode is single-capture work: one capture per worker on all but two cores
            create = { d -> Native.create(d.absolutePath, (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)) },
            destroy = { h -> Native.destroy(h) },
            background = { r -> try { ioExec.execute(r) } catch (_: java.util.concurrent.RejectedExecutionException) { Thread(r, "lb-teardown").start() } })
    }
    // the window's keep-screen-on flag, changed on transitions only
    private val screenOn = Changes<Boolean>()

    // camera mode: Auto is Full HD except for 1 px tiles (CameraChoice); the menu forces one
    private var mode = CameraMode.AUTO
    private val choice = CameraChoice()
    private var maxSize: Size? = null      // the largest 4:3 YUV stream of the back camera (looked up once)
    private var boundTier: Tier? = null
    private var camSize = ""
    @Volatile private var camFps = 0.0
    private var camFrames = 0
    private var camT0 = 0L
    private var cameraProblem = false
    // a stream change in progress: the first frame of the new binding ends "Changing camera…" (never a late frame of
    // the stream being replaced)
    private val change = StreamChange()

    // what the screen shows
    private var state: UiState = UiState.Aim(Hints.SEARCH)
    private var detailsOn = false
    private var bannerText: String? = null
    private var bannerUntil = 0L
    private var bannerKey = 0
    // decoder activity between polls (guidance)
    private var lastDecoded = 0L
    // the status's data_reads at the last poll: the "locked on" rule counts data reads, not polls (LockWatch)
    private var lastDataReads = 0L
    private var lastFailed = 0L
    private var lastSkipped = 0L
    private var readAt = 0L
    private var failAt = 0L
    // the guidance line shown (a new warning waits out HINT_HOLD_MS)
    private val hold = HintHold(HINT_HOLD_MS)
    // the sender is another LensBolt version (its headers say so for about a second): the banner says to update
    private val versions = VersionWatch()
    // the sender shows frames faster than this camera takes them (sustained, not a momentary camera dip)
    private val fpsWatch = SenderFpsWatch()
    // the last data read's geometry (manifest reads skipped)
    private var dataPitch = 0
    private var dataBw = false
    private var dataCell = 0.0
    private var dataEdges = DoubleArray(0)
    // ... and how it read: two sender frames blended (status `mix`) and the border's edge width (`edge_px`)
    private var dataMix = 0.0
    private var dataEdgePx = 0.0
    // locked on (the blue line): the tile tracked for 0.5 s and a codeword in the last 10 reads
    private val lockWatch = LockWatch()
    // focus locked once the tile reads whole for 1 s (FocusLock)
    private val focusLock = FocusLock()
    // the camera's phase against the sender's panel (PhaseLock): one longer frame moves the captures out of the
    // panel's settling window; `slowFps` the slower rate of the fallback step (null: none offered)
    private val phase = PhaseLock()
    /** the last active focus scan asked for while nothing was seen (the start), and its rule: the sharpness gate
     *  score under which, with no tile seen, the camera gets one, at most this often */
    private var focusKickAt = 0L
    private val FOCUS_KICK_BELOW = 15.0
    private val FOCUS_KICK_GAP_MS = 1500L
    /** the sender's rate within PhaseLock.RATE_MATCH of the camera's on the last poll (the guidance reads it) */
    private var phaseRatesMatch = false
    private var slowFps: Range<Int>? = null
    // the lens and sensor as the last capture result reported them (lab log, Details)
    @Volatile private var afState = -1
    @Volatile private var focusDiopters = 0f
    @Volatile private var resultExpNs = 0L
    @Volatile private var resultIso = 0
    // the phase step's measured move: while a step runs, the capture timestamps' gaps beyond the camera's own frame
    // time add up (ns); the frame time itself is learnt between steps
    // the phase steps in flight (PhaseController: one lock between this poll and the camera's callback thread), made
    // per camera stream; the last capture timestamp; the camera's sensitivity range and post-raw boost (an exact
    // step copies auto exposure's own values, clamped to what the sensor takes)
    @Volatile private var phaseCtl: PhaseController? = null
    @Volatile private var lastStampNs = 0L
    @Volatile private var isoRange: Range<Int>? = null
    @Volatile private var resultBoost = -1
    // the last details rows (Done keeps them; a save error is added there, raw)
    private var lastRows: List<Pair<String, String>> = emptyList()
    private val poll = object : Runnable { override fun run() { refresh(); ui.postDelayed(this, 200) } }

    // Done: the verified file in cacheDir/rx, shown until New transfer; the same transfer completing again while
    // it is shown changes nothing
    private var doneFile: File? = null
    private var doneSize = 0L
    private var doneStream = 0L            // the bytes the sender sent (the compressed stream): Done's Mbit/s
    private var doneSecs = 0.0
    private var doneMime = "application/octet-stream"
    // the received file's SHA-256 (hex), once read (Done screen, history)
    private var doneSha: String? = null
    private var savedAs: String? = null      // display name of the last document saved (Save may run again)
    private var savedOrShared = false
    private var backAsked = false            // "Not saved yet" is asked once per file
    private var saving = false
    private var savePct = -1
    private var doneNote: String? = null     // a line under the result (a save that failed, no app opens the type)
    private var donate = false               // the support card shows on this Done screen (DonatePrompt)
    // not enough space for the announced file, or the receiver failed: the session is stopped until New transfer
    private var stopped: UiState.Problem? = null
    private var spaceChecked = false
    // lab mode (our tests only): `adb shell touch /sdcard/Android/data/com.lensbolt.app/files/lab.on`; only then
    // session logs, frame dumps and the canned screens below (Storage)
    private var lab = false
    // a canned lab screen (`--es lab_state <name>`) is up: the camera is off and the status is not polled
    private var labState: String? = null
    // lab: `adb shell touch /sdcard/Android/data/com.lensbolt.app/files/dump.request` saves the next 8 camera
    // frames' raw planes to files/dump/ (pull them and decode on the PC); a number in the file asks for that many
    // consecutive frames instead (up to 400, for an end-to-end replay); no effect otherwise
    @Volatile private var dumpLeft = 0
    // exposure: a screen in a dark room makes auto exposure clip the screen's colours (white, yellow and pink
    // merge; measured on a OnePlus 12, 0/83 codewords). Every few frames the Y plane is sampled; compensation
    // steps down while > 0.5% of samples clip, up while the brightest 1% sits below 200.
    private var camera: androidx.camera.core.Camera? = null
    private var evIndex = 0
    private var evFrame = 0
    private var logTick = 0
    // lab field test without a PC: one status line per second to files/logs/session_<start>.jsonl (adb pull later)
    private var sessionLog: File? = null

    // Save: the system save screen (ACTION_CREATE_DOCUMENT); a cancelled picker changes nothing
    private val saveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        val f = doneFile
        if (r.resultCode == RESULT_OK && uri != null && f != null) {
            // kept for the history (open it again later), when the save screen allows it
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
            if (f.isFile) copyTo(f, uri) else fileGone()
        }
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { if (pane != Pane.NONE) setPane(Pane.NONE) else askBeforeLeaving() }
    }

    // a 180-degree turn changes no configuration: the preview's rotation follows the display
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { previewUse?.targetRotation = rotation() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        lab = Storage.labOn(this)
        if (!lab) { val app = applicationContext; ioExec.execute { Storage.purgeLabFiles(app) } }
        // a real start only: a recreated activity may be about to receive a folder picker's result
        if (savedInstanceState == null) ioExec.execute { sweepTrees() }
        mode = runCatching { CameraMode.valueOf(prefs.getString("cameraMode", "AUTO")!!) }.getOrDefault(CameraMode.AUTO)
        detailsOn = prefs.getBoolean("details", false)
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        screen = ReceiverScreen(this, preview, this)
        setContentView(screen.root)
        WindowInsetsControllerCompat(window, screen.root).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
        screen.setCameraMode(mode)
        screen.setDetailsOn(detailsOn)
        onBackPressedDispatcher.addCallback(this, backCallback)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, ui)
        // recreated by the system (say the process was stopped while a share target was in front): the Done screen
        // and its file come back (its directory goes with the next New transfer); a real start wipes cacheDir/rx
        val restored = savedInstanceState?.getString("done")?.let(::File)?.takeIf { it.isFile }
        val restoredBatch = savedInstanceState?.getString("batchDone")?.let(::File)?.takeIf { it.isDirectory }
        if (restoredBatch != null) {
            restoredBatch.parentFile?.let(sessions::adopt)
            savedOrShared = savedInstanceState.getBoolean("savedOrShared"); backAsked = savedInstanceState.getBoolean("backAsked")
            donate = savedInstanceState.getBoolean("donate")
            batchName = savedInstanceState.getString("batchName"); pageSource = folderPages(restoredBatch)
            enterBatchDone(restoredBatch, savedInstanceState.getDouble("secs"), savedInstanceState.getLong("stream"), counted = false)
        } else if (restored != null) {
            restored.parentFile?.let(sessions::adopt)
            savedOrShared = savedInstanceState.getBoolean("savedOrShared"); backAsked = savedInstanceState.getBoolean("backAsked")
            savedAs = savedInstanceState.getString("savedAs"); donate = savedInstanceState.getBoolean("donate")
            enterDone(restored, savedInstanceState.getDouble("secs"), savedInstanceState.getLong("stream"), sha = ShaText.valid(savedInstanceState.getString("sha")))
        } else {
            Storage.wipeRx(this)
            newSession()
        }
        val labReq = if (lab) intent?.getStringExtra("lab_state") else null
        when {
            labReq != null -> showLab(labReq, intent)
            doneFile != null || batchDone -> {}
            !granted() -> show(permissionState())
            else -> startCamera()
        }
    }

    // the status is polled only while the activity is visible
    override fun onStart() { super.onStart(); ui.removeCallbacks(poll); ui.post(poll) }

    override fun onStop() { super.onStop(); ui.removeCallbacks(poll) }

    // singleTask: a second start (the launcher, or a lab `am start`) reaches this instance; it never builds a
    // second activity whose start would wipe this one's cacheDir/rx
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lab = Storage.labOn(this)
        val labReq = if (lab) intent.getStringExtra("lab_state") else null
        if (labReq != null) showLab(labReq, intent)
    }

    // rotation, font size, density, dark mode and the rest arrive here (manifest configChanges): the views are
    // rebuilt for the new configuration while the camera binding and the native session carry on, so a
    // transfer never restarts and its progress never resets
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        screen.rebuild()
        previewUse?.targetRotation = rotation()
    }

    override fun onResume() {
        super.onResume()
        treeChanged()   // the folder may have gone (a card removed) while LensBolt was away
        // back from the system settings: the camera allowed (start), or still off (the right wording)
        if (state is UiState.Permission && labState == null) { if (granted()) startCamera() else show(permissionState()) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera() else show(permissionState())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        doneFile?.let {
            outState.putString("done", it.absolutePath); outState.putDouble("secs", doneSecs); outState.putLong("stream", doneStream)
            outState.putBoolean("savedOrShared", savedOrShared); outState.putBoolean("backAsked", backAsked); outState.putString("savedAs", savedAs)
            outState.putBoolean("donate", donate); outState.putString("sha", doneSha)
        }
        if (batchDone) batchDir?.let {
            outState.putString("batchDone", it.absolutePath); outState.putString("batchName", batchName)
            outState.putDouble("secs", doneSecs); outState.putLong("stream", doneStream); outState.putBoolean("donate", donate)
            // a batch: every received file saved (the per-file marks are not kept across a recreation)
            outState.putBoolean("savedOrShared", savedOrShared || ledger.allSaved(receivedCount())); outState.putBoolean("backAsked", backAsked)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        ui.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        analyzerExec.shutdown()
        shaExec.shutdownNow()
        // the history thread ends after the saves queued before it (they hand it their entries)
        pageExec.shutdown(); onSaveExec { historyExec.shutdown() }; saveExec.shutdown()
        // handed off, never joined here: the teardown runs on ioExec (queued before its shutdown, which lets it run)
        sessions.release()
        ioExec.shutdown()
    }

    // ---- screen --------------------------------------------------------------------------------------------

    private fun show(s: UiState) {
        state = s
        screen.render(s)
        // the screen stays on only while the camera is reading
        val reading = s is UiState.Aim || s is UiState.Locked || s is UiState.Receiving
        if (screenOn.changed(reading)) {
            if (reading) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updateBack()
    }

    override fun onAction(a: Action) {
        when (a) {
            Action.GRANT -> { prefs.edit().putBoolean("askedCamera", true).apply(); ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA) }
            Action.SETTINGS -> try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } catch (_: ActivityNotFoundException) {}
            Action.SAVE -> save()
            Action.OPEN -> open()
            Action.SHARE -> share()
            Action.NEW_TRANSFER -> {
                labState = null
                newSession()
                if (granted()) startCamera() else show(permissionState())
            }
            Action.RETRY_CAMERA -> { cameraProblem = false; hold.reset(); boundTier = null; show(UiState.Aim(Hints.SEARCH)); bindCamera() }
            Action.DONATE_COFFEE -> openLink(DonatePrompt.COFFEE)
            Action.DONATE_SPONSORS -> openLink(DonatePrompt.SPONSORS)
            Action.SOURCE -> openLink(DonatePrompt.SOURCE)
            Action.DONATE_LATER -> { donate = false; renderDone() }
            Action.DONATE_NEVER -> { prefs.edit().putBoolean("donateNever", true).apply(); donate = false; renderDone() }
            Action.START_OVER -> confirm(Asks.startOver(), onRisky = { onAction(Action.NEW_TRANSFER) })
        }
    }

    override fun onCameraMode(m: CameraMode) {
        mode = m
        prefs.edit().putString("cameraMode", m.name).apply()
        screen.setCameraMode(m)
        // back to Auto: its evidence was gathered on whatever stream ran before; it starts over from its own choice
        if (m == CameraMode.AUTO) choice.clear()
        if (tier() != boundTier) bindCamera()
    }

    override fun onDetails(on: Boolean) {
        detailsOn = on
        prefs.edit().putBoolean("details", on).apply()
        screen.setDetailsOn(on)
    }

    private fun granted() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    // permanently denied: asked before and Android no longer shows its dialog (only Settings can turn it on)
    private fun permissionState() = UiState.Permission(
        prefs.getBoolean("askedCamera", false) && !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA))

    // ---- session -------------------------------------------------------------------------------------------

    // a fresh receiver in a new, empty session directory (app start and New transfer; never on finish: a share
    // target may still be reading the last file). It starts at once; the old session, and its directory with the
    // last file, are removed in the background.
    private fun newSession() {
        sessions.start()
        doneFile = null; savedAs = null; savedOrShared = false; backAsked = false; doneNote = null; savePct = -1; donate = false
        stopped = null; spaceChecked = false; sessionLog = null
        lastDecoded = 0; lastDataReads = 0; lastFailed = 0; lastSkipped = 0; readAt = 0; failAt = 0
        hold.reset(); versions.reset(); fpsWatch.reset()
        dataPitch = 0; dataBw = false; dataCell = 0.0; dataEdges = DoubleArray(0); lastRows = emptyList()
        dataMix = 0.0; dataEdgePx = 0.0; lockWatch.reset(); phase.reset(); phaseCtl?.abort()
        if (focusLock.reset()) camera?.cameraControl?.cancelFocusAndMetering()
        if (camera != null && phase.takeRestore()) setNormalRate()   // (abort above put it back too)
        bannerText = null; bannerUntil = 0; screen.setBanner(null)
        choice.reset()
        resetBatch()
        labDoneShown = false
        screen.resetAnnouncements()
        state = UiState.Aim(Hints.SEARCH)
        updateBack()
    }

    // the session stops at once; its teardown and its directory's removal run in the background
    private fun stopSession() = sessions.stop()

    // `bind`: the binding whose analyzer delivered this frame
    private fun onFrame(img: ImageProxy, bind: Int) {
        try {
            camFrames++
            if (change.frame(bind)) ui.post { if (!change.waiting()) endChanging() }
            val now = System.nanoTime()
            if (camT0 == 0L) camT0 = now
            if (now - camT0 >= 1_000_000_000L) { camFps = camFrames * 1e9 / (now - camT0); camFrames = 0; camT0 = now }
            if (img.format != ImageFormat.YUV_420_888 || img.planes.size < 3) return
            val p = img.planes
            if (dumpLeft > 0) dump(img)
            if (++evFrame % 6 == 0) exposure(p[0].buffer, img.width, img.height, p[0].rowStride)
            sessions.withHandle { h ->
                if (h != 0L) Native.submit(h, p[0].buffer, p[1].buffer, p[2].buffer, img.width, img.height, p[0].rowStride, p[1].rowStride, p[1].pixelStride)
            }
        } catch (_: Throwable) {
            // one bad frame never stops the camera
        } finally {
            img.close()
        }
    }

    // ---- camera --------------------------------------------------------------------------------------------

    private fun startCamera() {
        if (doneFile == null && stopped == null) show(UiState.Aim(Hints.SEARCH))
        if (provider != null) { bindCamera(); return }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            provider = try { future.get() } catch (_: Throwable) { null }
            if (provider == null) { cameraProblem = true; show(cameraProblemState()) } else bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tier() = CameraChoice.streamFor(mode, choice.tier)

    // the camera runs only while there is something to read
    private fun wantCamera() = granted() && labState == null && doneFile == null && stopped == null && !batchDone

    private fun rotation() = preview.display?.rotation ?: Surface.ROTATION_0

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun largest43(provider: ProcessCameraProvider): Size {
        return try {
            val info = provider.availableCameraInfos.first { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() }
            val map = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            (sizes.filter { it.width.toLong() * 3 == it.height.toLong() * 4 }.ifEmpty { sizes.toList() })
                .maxByOrNull { it.width.toLong() * it.height } ?: Size(4000, 3000)
        } catch (_: Throwable) { Size(4000, 3000) }
    }

    /** The back camera's slower fixed frame rate for the fallback phase step: the fastest fixed range under 30 fps
     *  whose extra time per frame shifts the phase (15 fps adds a whole 33.3 ms period per frame: no shift; 24 fps
     *  adds 8.3 ms), else the fastest such range whose top is under 30; null when none (exact steps only). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun slowRange(provider: ProcessCameraProvider): Range<Int>? = try {
        val info = Camera2CameraInfo.from(provider.availableCameraInfos.first { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() })
        val period = 1000.0 / 30
        val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
            .filter { it.upper in 15..29 && phaseShift(1000.0 / it.upper - period, period) >= 3.0 }
        ranges.filter { it.lower == it.upper }.maxByOrNull { it.upper } ?: ranges.maxByOrNull { it.upper }
    } catch (_: Throwable) { null }

    /** Whether the back camera can take the exact phase step: manual sensor control with auto exposure off among its
     *  modes; with its sensitivity range (to clamp the copied ISO). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun exactSupport(provider: ProcessCameraProvider): Pair<Boolean, Range<Int>?> = try {
        val info = Camera2CameraInfo.from(provider.availableCameraInfos.first { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() })
        val manual = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val aeOff = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)?.contains(CameraCharacteristics.CONTROL_AE_MODE_OFF) == true
        (manual && aeOff) to info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    } catch (_: Throwable) { false to null }

    // binds (or rebinds, for a new camera mode) the preview and the analysis; the native session is untouched
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun bindCamera() {
        val provider = provider ?: return
        if (!wantCamera()) { provider.unbindAll(); camera = null; boundTier = null; previewUse = null; screen.setPreviewLive(false); endChanging(); return }
        val t = tier()
        val bind = change.nextBind()
        // another stream for the same session: the preview goes dark while the camera reopens, so the screen says
        // why until the first frame of the new stream (or a few seconds)
        if (boundTier != null && boundTier != t) {
            screen.setChanging(true); change.await(bind)
            ui.removeCallbacks(changingTimeout); ui.postDelayed(changingTimeout, CHANGING_MAX_MS)
        }
        val big = maxSize ?: largest43(provider).also { maxSize = it }
        val target = if (t == Tier.MAX) big else FULL_HD
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            .setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            // no temporal noise reduction or edge enhancement: the tile's cells are the signal, not the ISP's idea of
            // noise (first suspected for the blended captures of 2026-11-02, which turned out to be the sender's
            // panel mid-change when the camera looked: PhaseLock below)
            .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        slowFps = slowRange(provider)
        // a new stream: the phase lock keeps what it learnt this session; the controller starts afresh (frame time,
        // gain and pipeline depth are this stream's)
        phase.cameraChanged()
        phaseCtl?.abort()
        val (exactOk, isoR) = exactSupport(provider)
        isoRange = isoR
        // how many frames this camera carries an exact request on, as learnt before (0: a probe first)
        val camId = try { Camera2CameraInfo.from(provider.availableCameraInfos.first { CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty() }).cameraId } catch (_: Throwable) { "back" }
        phaseCtl = PhaseController(phaseCamera, exactOk, prefs.getInt("phaseCarry_$camId", 0)) { c -> prefs.edit().putInt("phaseCarry_$camId", c).apply() }
        // the lens and sensor state per capture, for the lab log and Details
        Camera2Interop.Extender(builder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: -1
                focusDiopters = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
                resultExpNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                resultIso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                resultBoost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: -1
                val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                val gap = if (lastStampNs > 0 && ts > lastStampNs) ts - lastStampNs else 0L
                lastStampNs = ts
                // the phase steps: every capture's gap (the controller learns the frame time between steps)
                if (gap in 1..200_000_000L) phaseCtl?.frame(gap / 1e6)
            }
            override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                // a request the camera rejected (an exact step it cannot take): auto exposure back at once
                phaseCtl?.failed()
            }
        })
        val analysis = builder.build()
        analysis.setAnalyzer(analyzerExec) { onFrame(it, bind) }
        // the preview at 4:3 like the analysis: the framing guide then marks what the decoder sees
        val prev = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .setTargetRotation(rotation())
            .build().also { it.surfaceProvider = preview.surfaceProvider }
        try {
            provider.unbindAll()
            camera?.cameraInfo?.cameraState?.removeObservers(this)
            val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, prev, analysis)
            camera = cam; previewUse = prev; boundTier = t
            screen.setPreviewLive(true)
            cam.cameraControl.setExposureCompensationIndex(evIndex)
            if (focusLock.reset()) cam.cameraControl.cancelFocusAndMetering()
            if (phase.takeRestore()) setNormalRate()
            val r = analysis.resolutionInfo?.resolution
            camSize = if (r != null) "${r.width}x${r.height}" else "?"
            if (r != null && r.height > 0) screen.setImageAspect(maxOf(r.width, r.height).toFloat() / minOf(r.width, r.height))
            cam.cameraInfo.cameraState.observe(this) { st -> onCameraState(st) }
        } catch (_: Throwable) {
            cameraProblem = true; boundTier = null
            endChanging()
            screen.setPreviewLive(false)
            show(cameraProblemState())
        }
    }

    private val changingTimeout = Runnable { endChanging() }

    private fun endChanging() { change.cancel(); ui.removeCallbacks(changingTimeout); screen.setChanging(false) }

    // another app holding the camera, the camera disabled by policy, a fatal error: a problem screen until the
    // camera opens again (CameraX retries on its own); the session and its progress are kept
    private fun onCameraState(st: CameraState) {
        val err = st.error
        if (err != null && (err.type == CameraState.ErrorType.CRITICAL || err.code == CameraState.ERROR_CAMERA_IN_USE ||
                err.code == CameraState.ERROR_MAX_CAMERAS_IN_USE || err.code == CameraState.ERROR_CAMERA_DISABLED)) {
            if (!cameraProblem) { cameraProblem = true; if (wantCamera()) show(cameraProblemState()) }
        } else if (st.type == CameraState.Type.OPEN && cameraProblem) {
            cameraProblem = false
            hold.reset()   // the old line (say "Hold steady") would otherwise sit over a tile no longer seen
            if (wantCamera()) show(UiState.Aim(Hints.SEARCH))
        }
    }

    private fun cameraProblemState() = UiState.Problem("Camera unavailable",
        "Another app may be using the camera. Close it, then try again. What was received so far is kept.", Action.RETRY_CAMERA, "Try again")

    // ---- status --------------------------------------------------------------------------------------------

    private fun refresh() {
        if (++logTick % 5 == 0) lab = Storage.labOn(this)
        if (lab) {
            val req = File(getExternalFilesDir(null), "dump.request")
            // the file may hold a frame count (consecutive frames for an end-to-end replay on the PC or phone)
            if (req.exists()) { dumpLeft = (try { req.readText().trim().toInt() } catch (_: Throwable) { 8 }).coerceIn(1, 400); req.delete() }
        }
        if (labState != null || state is UiState.Permission) return
        val json = sessions.withHandle { h -> if (h == 0L) "{}" else Native.status(h) }
        val s = try { JSONObject(json) } catch (_: Throwable) { return }
        if (lab && logTick % 5 == 0) {
            val line = ("{\"wall_ms\":${System.currentTimeMillis()},\"camera\":\"$camSize\",\"camera_fps\":%.1f,\"ev\":$evIndex," +
                "\"exposure_ns\":$resultExpNs,\"iso\":$resultIso,\"af_state\":$afState,\"focus_diopters\":%.2f,\"focus_locked\":${focusLock.locked},\"focus_failed\":${focusLock.failedLocks},\"phase\":\"${phase.state}\",\"phase_steps\":${phase.steps},\"phase_searches\":${phase.searches},\"phase_ignored\":${phase.ignored},\"phase_locked\":${phase.locked},\"phase_shift_ms\":%.2f,\"phase_cum_ms\":%.2f,\"phase_exact\":${phaseCtl?.lastExact ?: false},\"phase_carry\":${phaseCtl?.carry ?: 0},\"phase_fallback_off\":${phaseCtl?.fallbackOff ?: false},\"settle_mix\":%.2f,\"frame_ms\":%.3f,\"status\":$json}").format(java.util.Locale.ROOT, camFps, focusDiopters, phase.lastMoveMs, phase.cumulativeMs, phase.settleMix, phaseCtl?.frameMs ?: 0.0)
            android.util.Log.i("LensBolt", line)
            try {
                val f = sessionLog ?: File(getExternalFilesDir("logs"), "session_${System.currentTimeMillis()}.jsonl").also { sessionLog = it }
                f.appendText(line + "\n")
            } catch (_: Throwable) {}
        }
        // Done and No space are fixed screens until New transfer (Details keep the numbers they ended with)
        if (doneFile != null || stopped != null || batchDone) return
        lastRows = detailRows(s)
        screen.setDetails(lastRows)   // kept while Details are off, shown when they are turned on
        if (cameraProblem) return

        val now = SystemClock.elapsedRealtime()
        val decoded = s.optLong("decoded"); val failed = s.optLong("failed"); val skipped = s.optLong("colour_skipped")
        val newRead = decoded > lastDecoded
        if (newRead || skipped > lastSkipped) readAt = now
        if (failed > lastFailed) failAt = now
        lastDecoded = decoded; lastFailed = failed; lastSkipped = skipped
        val fresh = readAt > 0 && now - readAt < Guidance.FRESH_MS

        val format = s.str("format")
        val pitch = format?.substringBefore("px")?.toIntOrNull() ?: 0
        // the guidance reads the geometry of the last data read: a 12 px manifest read always looks large
        if (newRead && pitch in 1 until MANIFEST_PITCH) {
            dataPitch = pitch; dataBw = format?.split(' ')?.getOrNull(1) == "bw"
            dataCell = s.optDouble("cell_px", 0.0); dataEdges = edgesOf(s)
            dataMix = s.optDouble("mix", 0.0); dataEdgePx = s.optDouble("edge_px", 0.0)
        }
        // the data reads since the last poll (a poll may bring several; the status's codewords are the latest one's)
        val dataReads = s.optLong("data_reads", lastDataReads)
        val newDataReads = (dataReads - lastDataReads).coerceAtLeast(0); lastDataReads = dataReads
        val locked = lockWatch.update(fresh, newDataReads, s.optLong("codewords_ok"), now)
        // focus: locked once the tile reads whole for 1 s, continuous again when reads fail for 2 s or the
        // distance changes (FocusLock); the lens state goes to the lab log
        val dataRead = newRead && pitch in 1 until MANIFEST_PITCH
        when (focusLock.update(fresh, dataRead, s.optLong("codewords_ok"), s.optLong("codewords"), dataCell, dataEdgePx, now)) {
            FocusLock.Act.LOCK -> camera?.cameraControl?.let { c ->
                val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                val f = c.startFocusAndMetering(FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).disableAutoCancel().build())
                // a lock the camera does not report focused is dropped at once (two of those: continuous for the
                // session); on the analyzer thread, where the focus lock is kept
                f.addListener({
                    val ok = try { f.get().isFocusSuccessful } catch (_: Throwable) { false }
                    if (!ok && focusLock.locked) { focusLock.lockFailed(); c.cancelFocusAndMetering() }
                }, analyzerExec)
            }
            FocusLock.Act.UNLOCK -> camera?.cameraControl?.cancelFocusAndMetering()
            FocusLock.Act.NONE -> {}
        }
        // the start: continuous focus can sit soft on a screen for seconds (the first captures of a session at
        // sharpness 7-8 against 37-60 once reading, no markers found, 2026-11-03): while nothing is seen and the
        // picture is soft, an active focus scan at the centre, at most every 1.5 s (auto-cancelled: continuous
        // focus goes on afterwards; FocusLock's own lock is untouched)
        if (!fresh && !focusLock.locked && s.optDouble("sharpness", 0.0) < FOCUS_KICK_BELOW && now - focusKickAt >= FOCUS_KICK_GAP_MS) {
            focusKickAt = now
            camera?.cameraControl?.let { c ->
                val centre = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                try { c.startFocusAndMetering(FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(2, java.util.concurrent.TimeUnit.SECONDS).build()) } catch (_: Throwable) {}
            }
        }
        // the phase: two sender frames blended in every capture (the panel mid-change when the camera looks) move
        // the camera's captures later in the sender's period (PhaseLock)
        val ctl = phaseCtl
        if (ctl != null) {
            ctl.tick(now)
            ctl.takeResult()?.let { phase.stepDone(it, now) }
            phase.periodMs = ctl.frameMs
            val sFps = s.optDouble("sender_fps", 0.0)
            val ratesMatch = sFps > 0 && camFps > 0 && kotlin.math.abs(sFps / camFps - 1) <= PhaseLock.RATE_MATCH
            phaseRatesMatch = ratesMatch
            // a read whole: 90% of its frame's codewords or more (the lock line needs them)
            val whole = s.optLong("codewords") > 0 && s.optLong("codewords_ok") * 10 >= s.optLong("codewords") * 9
            // a read that looks clean yet reads under a fifth of its codewords, with cells large enough and the
            // border sharp, is a blend the measure under-reports (a MacBook panel settles slowly: 15-25% of the
            // previous frame over the whole tile read 2 of 96 at mix 0.06-0.36, 2026-11-03): it counts as blended
            val poor = dataRead && s.optLong("codewords") > 0 && s.optLong("codewords_ok") * 5 < s.optLong("codewords") &&
                s.optDouble("cell_px", 0.0) >= Guidance.floor(pitch, format?.contains(" bw ") == true) && s.optDouble("edge_px", 0.0) < Guidance.EDGE_SOFT
            // as a full blend: the lock (held while the blend stays under UNLOCK_MIX) must drop for the step to come
            // (phone on the MacBook, 06:45: reads fell 96 -> 2 of 96 with mix 0.0-0.15 and the lock held for 20 s)
            val mixEff = if (poor) maxOf(dataMix, PhaseLock.UNLOCK_MIX + 0.2) else dataMix
            if (phase.update(dataRead, mixEff, ratesMatch, now, whole)) ctl.start(phase.shiftMs, resultExpNs > 0 && resultIso > 0, now)
            if (phase.stepTimedOut) ctl.abort()
        }
        val name = s.str("filename")
        // live progress: unique blocks received (bytes_done moves only when a whole 16 MB segment verifies)
        val done = maxOf(s.optLong("bytes_done"), s.optLong("received_bytes")); val total = s.optLong("stream_size")
        val mbit = s.optDouble("mbit_per_s", 0.0)
        val failure = s.str("last_error").takeIf { failAt > 0 && now - failAt < Guidance.FRESH_MS }
        val hint = hold.next(Guidance.hint(Reading(fresh, failure,
            dataPitch, dataBw, dataCell, dataEdges, s.optLong("codewords_ok"), s.optLong("codewords"), total > 0,
            mix = dataMix, edgePx = dataEdgePx, locked = locked,
            phaseFixing = phaseCtl != null && phase.state != PhaseLock.State.OFF && !phase.resting(now) && phaseRatesMatch)), now)
        val otherVersion = versions.update(failure, fresh, now)

        // Auto: the stream follows how the data reads (a rebind keeps the session and its progress)
        if (newRead && mode == CameraMode.AUTO && pitch in 1 until MANIFEST_PITCH && !cameraProblem &&
            choice.observe(TileRead(pitch, dataBw, s.optDouble("cell_px", 0.0), s.optLong("codewords_ok"), s.optLong("codewords")), now)) bindCamera()
        val batch = s.optJSONObject("batch")
        if (batch != null) {
            onBatchStatus(batch, done, total)
            // the file list did not check out: nothing can be saved
            batch.str("error")?.let { showReceiverError(it); return }
        }
        s.str("complete")?.let {
            val out = File(it)
            if (batch != null && out.isDirectory) enterBatchDone(out, s.optDouble("seconds", 0.0), total, counted = true)
            else enterDone(out, s.optDouble("seconds", 0.0), total, counted = true, sha = ShaText.valid(s.str("sha256")))
            return
        }
        s.str("receiver_error")?.let { showReceiverError(it); return }
        // space: once the manifest gives the size, before much is received; short => stop rather than fail halfway
        if (!spaceChecked && total > 0) {
            spaceChecked = true
            val need = Storage.needBytes(total, s.optLong("disk_peak")); val free = Storage.freeBytes(this)
            if (free < need) { showNoSpace(free, need); return }
        }
        // the sender shows frames faster than this camera takes them (for 2 s, against the camera's best rate of
        // the last 3 s): held 2 s after it clears, so a stutter in the estimate never blinks it
        val senderFps = s.optDouble("sender_fps", 0.0)
        val fpsSuggest = fpsWatch.update(senderFps, camFps, now)
        // a sender of another version comes first: nothing decodes until both ends are the same version
        if (otherVersion) { bannerText = VersionWatch.TEXT; bannerKey = VERSION_BANNER_KEY; bannerUntil = 0 }
        else if (fpsSuggest != null) { bannerText = SenderFps.banner(senderFps, fpsWatch.camRate); bannerKey = fpsSuggest; bannerUntil = now + BANNER_HOLD_MS }
        else if (now >= bannerUntil) bannerText = null
        screen.setBanner(bannerText, bannerKey)

        // time left at the measured rate
        val left = if (mbit > 0 && total > 0) ((total - done).coerceAtLeast(0) * 8 / (mbit * 1e6)).toLong() else -1L
        // the parts still missing, as words to type on the sender (optional: the file arrives without them)
        val words = FinishWords.of(s.str("finish_words"), s.str("complete") != null)
        val parts = FinishWords.parts(s.str("finish_parts"), words)
        if (batch != null && pane == Pane.FILES) pushFiles()
        show(when {
            total > 0 && name != null && done > 0 -> UiState.Receiving(name, done, total, mbit, left, hint, Guidance.finishing(s.optLong("bytes_done"), total), words, parts,
                batch = if (batch != null) BatchWords.receiving(batchReceived, batchTotal, batchFailed, done, total) else null)
            total > 0 || name != null -> UiState.Locked(name, hint)
            readAt > 0 && now - readAt < LOCK_HOLD_MS -> UiState.Locked(null, hint)
            else -> UiState.Aim(hint)
        })
    }

    private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null


    private fun edgesOf(s: JSONObject): DoubleArray = s.optJSONArray("edges")?.let { a -> DoubleArray(a.length()) { a.optDouble(it, 0.0) } } ?: DoubleArray(0)

    private fun detailRows(s: JSONObject): List<Pair<String, String>> {
        val received = s.optLong("received"); val dropped = s.optLong("dropped")
        val cw = s.optLong("codewords")
        val cell = s.optDouble("cell_px", 0.0)
        val sf = s.optDouble("sender_fps", 0.0)
        val tierName = if (boundTier == Tier.MAX) "Max" else "Full HD"
        return buildList {
            add("Camera" to "${camSize.ifEmpty { "–" }} · %.1f fps · ${mode.label}".format(camFps) + if (mode == CameraMode.AUTO) " ($tierName)" else "")
            add("Sender" to if (sf > 0) "%.1f fps".format(sf) else "not measured yet")
            add("Decode" to "%.0f ms (b/w %.0f, colour %.0f)".format(s.optDouble("decode_ms", 0.0), s.optDouble("luma_ms", 0.0), s.optDouble("colour_ms", 0.0)))
            add("Captures" to "%,d".format(received))
            add("Decoded" to "%,d · %,d failed".format(s.optLong("decoded"), s.optLong("failed")))
            add("Dropped" to "%,d".format(dropped) + if (received > 0) " (%.1f%%)".format(dropped * 100.0 / received) else "")
            add("Colour skipped" to "%,d".format(s.optLong("colour_skipped")))
            add("Codewords" to if (cw > 0) "${s.optLong("codewords_ok")} / $cw" else "–")
            add("Format" to (s.str("format") ?: "–"))
            add("Cell size" to if (cell > 0) "%.2f camera px · perspective %.2f".format(cell, Guidance.skew(edgesOf(s))) else "–")
            add("Exposure" to "EV $evIndex" + if (resultExpNs > 0) " · 1/%d s · ISO %d".format((1e9 / resultExpNs).roundToInt(), resultIso) else "")
            add("Screen timing" to when {
                phase.state == PhaseLock.State.OFF -> "not available"
                phase.resting(SystemClock.elapsedRealtime()) -> "resting"
                phase.locked -> "locked"
                else -> "searching"
            } + " · ${phase.steps} steps" + (if (phase.lastMoveMs != 0.0) " · last %.1f ms (%s)".format(java.util.Locale.ROOT, phase.lastMoveMs, if (phaseCtl?.lastExact == true) "exact" else "rate") else "") +
                (if (phase.settleMix >= 0) " · blend %.2f".format(java.util.Locale.ROOT, phase.settleMix) else "") + if (phase.ignored > 0) " · ignored ${phase.ignored}x" else "")
            add("Focus" to (if (focusLock.locked) "locked" else "continuous") + " · " + afName(afState) + if (focusDiopters > 0) " · %.2f m".format(1 / focusDiopters) else "")
            if (s.optDouble("edge_px", 0.0) > 0) add("Sharpness" to "edges %.2f camera px · frames blended over %.0f%%".format(s.optDouble("edge_px", 0.0), s.optDouble("mix", 0.0) * 100))
            s.str("last_error")?.let { add("Last error" to it) }
        }
    }

    private fun exposure(y: java.nio.ByteBuffer, w: Int, h: Int, row: Int) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val hist = IntArray(256)
        var n = 0
        for (yy in 0 until h step 8) for (xx in 0 until w step 8) { hist[y.get(yy * row + xx).toInt() and 0xFF]++; n++ }
        val clipped = hist[250] + hist[251] + hist[252] + hist[253] + hist[254] + hist[255]
        var acc = 0; var p99 = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= n / 100) { p99 = v; break } }
        val range = state.exposureCompensationRange
        val next = when {
            clipped * 200 > n -> evIndex - 1
            p99 < 200 -> evIndex + 1
            else -> evIndex
        }.coerceIn(range.lower, minOf(0, range.upper)) // a screen is never too dark: only ever darken, so no
        // runaway brightening while the tile is out of view (measured: EV +18, then seconds of clipped frames)
        if (next != evIndex) { evIndex = next; cam.cameraControl.setExposureCompensationIndex(next) }
    }

    /** The phase steps' camera requests (PhaseController calls them under its lock). */
    private val phaseCamera = object : PhaseCamera {
        @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
        override fun exact(askMs: Double) {
            val cam = camera ?: return
            val r = isoRange
            val iso = if (r != null) resultIso.coerceIn(r.lower, r.upper) else resultIso
            val frame = ((phaseCtl?.frameMs ?: (1000.0 / 30)) + askMs) * 1e6
            try {
                val b = CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, resultExpNs)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, frame.toLong())
                if (resultBoost >= 0) b.setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, resultBoost)
                Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
            } catch (_: Throwable) {}
        }
        override fun slow() { slowFps?.let { setFpsRange(it) } ?: setNormalRate() }
        override fun normal() = setNormalRate()
    }

    /** The camera's own timing again: auto exposure at a fixed 30 fps. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun setNormalRate() {
        val cam = camera ?: return
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30)).build())
        } catch (_: Throwable) {}
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun setFpsRange(r: Range<Int>) {
        val cam = camera ?: return
        try {
            Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r).build())
        } catch (_: Throwable) {}
    }

    private fun afName(st: Int): String = when (st) {
        CaptureResult.CONTROL_AF_STATE_INACTIVE -> "AF idle"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN, CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> "AF scanning"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED, CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED -> "AF focused"
        CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED, CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED -> "AF not focused"
        else -> "AF –"
    }

    private fun dump(img: ImageProxy) {
        val dir = File(getExternalFilesDir(null), "dump").apply { mkdirs() }
        val p = img.planes
        val stem = "f_%d_%dx%d_%d_%d_%d".format(System.nanoTime() / 1000000, img.width, img.height, p[0].rowStride, p[1].rowStride, p[1].pixelStride)
        for ((i, ext) in listOf("y", "u", "v").withIndex()) {
            val b = p[i].buffer.duplicate().apply { rewind() }
            val bytes = ByteArray(b.remaining()); b.get(bytes)
            File(dir, "$stem.$ext").writeBytes(bytes)
        }
        dumpLeft--
    }

    // ---- done: save / open / share ---------------------------------------------------------------------------

    // `stream`: the bytes sent (the compressed stream; 0 when unknown, then the file's size): the rate is what
    // crossed the screen, not the unpacked file
    // `counted`: a transfer that just completed (not a restored Done screen, not a lab one): it counts towards the
    // support card, which then shows on this Done screen now and then (DonatePrompt)
    // `sha`: the file's SHA-256 from the status (the receiver verified the file against it), null when unknown
    private fun enterDone(f: File, secs: Double, stream: Long, counted: Boolean = false, sha: String? = null) {
        if (doneFile == f) return
        if (counted) donate = countTransfer()
        doneFile = f; doneSecs = secs; doneSize = f.length(); doneStream = if (stream > 0) stream else doneSize
        doneMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
        bindCamera()   // the camera stops: nothing more to read
        doneSha = sha
        renderDone()
        // the file's SHA-256 for people to compare with the sender's: the status's (the receiver verified the file
        // against it); without one (a lab file) read once, off the screen's thread
        if (sha == null && f.isFile) shaExec.execute {
            val read = try { ShaText.of(f) } catch (_: Throwable) { null }
            ui.post { if (doneFile == f && read != null && doneSha == null) { doneSha = read; renderDone() } }
        }
    }

    private fun renderDone() {
        val f = doneFile ?: return
        val mbit = if (doneSecs > 0) doneStream * 8 / doneSecs / 1e6 else 0.0
        show(UiState.Done(f.name, doneSize, doneSecs, mbit, savedAs, if (saving) savePct.coerceAtLeast(0) else -1, doneNote, f.isFile, donate, doneSha))
    }

    private fun countTransfer(): Boolean {
        // a build without support links (the Play build) never shows the card and keeps no count
        if (!BuildConfig.SUPPORT_LINKS) return false
        val (c, show) = DonatePrompt.afterTransfer(DonatePrompt.Count(prefs.getInt("donateCount", 0), prefs.getInt("donateShownAt", 0), prefs.getBoolean("donateNever", false)))
        prefs.edit().putInt("donateCount", c.transfers).putInt("donateShownAt", c.lastShown).apply()
        return show
    }

    // a support link, in the browser (LensBolt itself has no network permission); the card goes once it is used
    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            donate = false
        } catch (_: ActivityNotFoundException) { doneNote = "No browser on this phone opens the link." }
        renderDone()
    }

    private fun showNoSpace(free: Long, need: Long) {
        stopSession()
        // free space rounds down and the need rounds up: the screen never promises space that is not there
        val p = UiState.Problem("Not enough space",
            "${mbDown(free)} free, ${mbUp(need)} needed for this file. Free some space, then try again.", Action.NEW_TRANSFER, "Try again")
        stopped = p
        bindCamera()
        show(p)
    }

    // the receiver could not write or check the file (a disk error): plain words on screen, the error itself in
    // Details; New transfer starts over
    private fun showReceiverError(err: String) {
        stopSession()
        val p = UiState.Problem("Transfer stopped", "The file could not be put together. Start a new transfer.", Action.NEW_TRANSFER, "New transfer")
        stopped = p
        lastRows = lastRows.filter { it.first != "Receiver error" } + ("Receiver error" to err)
        screen.setDetails(lastRows)
        if (lab) android.util.Log.i("LensBolt", "receiver error: $err")
        bindCamera()
        show(p)
    }

    // the system may clear an app's cache at any time: the file is checked before every Save / Open / Share
    private fun present(): File? {
        val f = doneFile ?: return null
        if (f.isFile) return f
        fileGone(); return null
    }

    private fun fileGone() {
        doneNote = "The received file was removed (Android can clear LensBolt's storage). Start a new transfer to receive it again."
        renderDone()
    }

    private fun save() {
        if (saving) return
        val f = present() ?: return
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(doneMime).putExtra(Intent.EXTRA_TITLE, f.name)
        try { saveLauncher.launch(i) } catch (_: ActivityNotFoundException) { doneNote = "This phone has no save screen. Use Share instead."; renderDone() }
    }

    // copies the received file into the chosen document off the UI thread, with progress; a copy that fails
    // after the document was opened for writing removes the half-written document. A document that could not
    // be opened is left alone: it may be an existing file the user chose to overwrite.
    private fun copyTo(f: File, uri: Uri) {
        saving = true; savePct = 0; doneNote = null
        renderDone()
        val total = f.length().coerceAtLeast(1)
        ioExec.execute {
            var err: String? = null
            var opened = false
            var copiedSha: String? = null
            try {
                val out = try { contentResolver.openOutputStream(uri, "wt") } catch (_: Throwable) { contentResolver.openOutputStream(uri, "w") }
                    ?: throw java.io.IOException("the chosen place cannot be written")
                opened = true
                // the copy is hashed as it is written: a Save before the Done screen's hash is known still gives the
                // history item its SHA-256
                copiedSha = out.use { o ->
                    f.inputStream().use { inp ->
                        var shown = -1
                        ShaText.copy(inp, o, BatchSaver.buffer()) { done ->
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; ui.post { if (!isDestroyed && doneFile == f && saving) { savePct = pct; renderDone() } } }
                        }
                    }
                }
            } catch (t: Throwable) {
                err = t.message ?: t.toString()
                if (opened) try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Throwable) {}
            }
            val name = if (err == null) displayName(uri) ?: f.name else null
            if (lab) android.util.Log.i("LensBolt", "save ${f.name} -> $uri: " + (err ?: "ok, ${f.length()} bytes, saved as $name"))
            ui.post {
                if (isDestroyed) return@post
                saving = false; savePct = -1
                if (doneFile != f) return@post
                if (err == null) {
                    savedAs = name; savedOrShared = true
                    if (doneSha == null) doneSha = copiedSha   // the bytes just written are the file's
                    if (!labDoneShown) { val size = f.length(); val item = HistoryItem(name ?: f.name, size, System.currentTimeMillis(), BatchSaver.whereSaved(uri), uri.toString(), null, doneSha); onHistory { dropped(history.add(item)) } }
                }
                else {
                    // plain words on screen; the system's own message goes to Details (and the lab log)
                    doneNote = "Could not save the file there. Try again, or choose another place."
                    lastRows = lastRows.filter { it.first != "Save error" } + ("Save error" to err)
                    screen.setDetails(lastRows)
                }
                renderDone()
            }
        }
    }

    private fun displayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun open() {
        val f = present() ?: return
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Storage.contentUri(this, f), doneMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(i) } catch (_: ActivityNotFoundException) {
            doneNote = "No app on this phone opens this type of file. Save it or Share it instead."; renderDone()
        }
    }

    private fun share() {
        val f = present() ?: return
        val uri = Storage.contentUri(this, f)
        val i = Intent(Intent.ACTION_SEND).setType(doneMime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri(f.name, uri)
        startActivity(Intent.createChooser(i, f.name))
        savedOrShared = true; updateBack()
    }

    // while a copy runs Back simply leaves (the copy finishes on its own): no "Not saved yet" over a save
    // Back closes a pane first; it asks once before leaving a received file that is not saved, or a batch while any
    // received file of it has no successful save
    private fun updateBack() {
        val kept = if (batchDone) savedOrShared || ledger.allSaved(receivedCount()) else savedOrShared
        backCallback.isEnabled = backEnabled(pane != Pane.NONE, batchDone, doneFile != null || batchDone, kept, backAsked, saving)
    }

    private fun askBeforeLeaving() {
        if (batchDone) {
            val ask = BatchAsk.leaving(receivedCount(), ledger.savedCount, ledger.queuedUnsavedCount, newTransfer = false) ?: return
            backAsked = true; updateBack()
            confirm(Asks.batch(ask), onRisky = { finish() }, onSafe = { if (!ask.saving) saveAll() })
            return
        }
        val f = doneFile ?: return
        backAsked = true; updateBack()
        confirm(Asks.notSaved(f.name), onRisky = { finish() }, onSafe = { save() })
    }

    // a question before something is lost: the risky choice first, the safe one last (the positive button, focused)
    private fun confirm(c: Confirm, onRisky: () -> Unit, onSafe: () -> Unit = {}) {
        val d = AlertDialog.Builder(this)
            .setTitle(c.title)
            .setMessage(c.message)
            .setNegativeButton(c.risky) { _, _ -> onRisky() }
            .setPositiveButton(c.safe) { _, _ -> onSafe() }
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }

    private fun mbDown(b: Long): String = "%,d MB".format(b shr 20)
    private fun mbUp(b: Long): String = "%,d MB".format((b + (1L shl 20) - 1) shr 20)


    // ---- batch: the file list, Save all, history -------------------------------------------------------------

    // pages of the list are read here (Native.batchPage never waits: busy is asked again); saving and the history
    // file on their own thread
    private val pageExec = Executors.newSingleThreadExecutor()
    private val saveExec = Executors.newSingleThreadExecutor()
    // the history file: read, written and cleared on its own thread (a long copy never holds up the list)
    private val historyExec = Executors.newSingleThreadExecutor()
    private val history by lazy { History(File(filesDir, "history.json")) }
    private val batcher by lazy { HistoryBatcher(history) }   // historyExec only
    // this batch's own token: sessions are numbered from s1 on every app start, so its history entry needs it
    private var batchToken = HistoryBatcher.token()
    private val saver by lazy { BatchSaver(applicationContext) }
    private var pane = Pane.NONE
    // the batch being received (status "batch"); null: a single file
    private var batchName: String? = null
    private var batchTotal = 0
    private var batchReceived = 0
    private var batchFailed = 0
    private var batchBytesDone = 0L
    private var batchStream = 0L
    private var batchDir: File? = null          // the folder the receiver saves the batch's files in
    private var batchDone = false
    // this batch session: late pages and saves of an older one are dropped (read on pageExec and saveExec)
    @Volatile private var batchGen = 0
    private var pages = BatchFiles()            // pageExec only
    private var files: BatchFiles? = null       // the screen's copy
    private var pageBusy = false
    private var pageAt = 0L
    private var pageKey = ""
    // a page source other than the receiver: a lab list, or a restored Done screen's folder
    private var pageSource: ((Int, Int) -> String)? = null
    @Volatile private var labBatch = false
    // Save all and the rows' Save…, per file index (UI thread); the failures by index, named
    private val ledger = SaveLedger()
    private val saveFails = LinkedHashMap<Int, Pair<String, String>>()
    private var savedMarks = 0
    private var saveRun = 0                     // files in the current run, and how many of them are through
    private var saveRunDone = 0
    private var saveNow: String? = null
    private var saveFrac = 0f                     // how far the current save is (0..1), for the bar under saveNow
    private var saveOk = 0
    private var saveWhere: String? = null
    private var folderLost: String? = null
    private var pendingSaveAll = false
    private var pendingArrive = false
    private var rowTarget = -1
    // Save all's queued saves (any batch): when the last one ends, the run's history entries are written at once
    private val pendingSaves = java.util.concurrent.atomic.AtomicInteger()
    // folder permissions while saves are queued (the folder changed away from goes back once its saves end)
    private val trees = TreeKeeper()
    // the folder Save all writes to, looked up once until the folder changes (a binder call each time otherwise)
    private var treeCache: Uri? = null
    private var treeKnown = false
    // the picked folder in words ("SD card › LensBolt"), looked up with it (a Downloads folder needs its name)
    private var treeWords: String? = null
    // the file list's screen follows the saves at most every PUSH_MS
    private val pushGate = Coalesce(PUSH_MS)
    private val pushRun = Runnable { pushGate.ran(SystemClock.elapsedRealtime()); pushFiles() }
    private var historyItems: List<HistoryItem> = emptyList()
    private var historyNote: String? = null
    private var labHistory: List<HistoryItem>? = null
    private var labDoneShown = false            // the lab Done screen: its saves stay out of the history

    private val treeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) { pendingSaveAll = false; pendingArrive = false; pushFiles(); return@registerForActivityResult }
        val old = prefs.getString("saveTree", null)
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
        prefs.edit().putString("saveTree", uri.toString()).apply()
        treeChanged()
        // the old folder's permission goes back now, or once the saves still queued for it end
        for (t in trees.picked(old, uri.toString())) releaseUri(t)
        folderLost = null; saveWhere = null
        if (pendingArrive) { pendingArrive = false; prefs.edit().putBoolean("saveArrive", true).apply() }
        if (pendingSaveAll || prefs.getBoolean("saveArrive", false)) { pendingSaveAll = false; saveAll() }
        pushFiles()
    }

    private val rowSaveLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data; val i = rowTarget; rowTarget = -1
        if (r.resultCode == RESULT_OK && uri != null && i >= 0) saveRow(i, uri)
    }

    // the folder Save all writes to, while LensBolt still may
    private fun tree(): Uri? {
        if (!treeKnown) {
            val u = prefs.getString("saveTree", null)?.let(Uri::parse)
            treeCache = u?.takeIf { contentResolver.persistedUriPermissions.any { p -> p.uri == u && p.isWritePermission } }
            treeWords = treeCache?.let { t ->
                val id = try { DocumentsContract.getTreeDocumentId(t) } catch (_: Throwable) { null }
                // only a Downloads folder ("msf:<number>") is named by its display name: one lookup per folder change
                val name = if (id?.startsWith("msf:") == true) try { androidx.documentfile.provider.DocumentFile.fromTreeUri(this, t)?.name } catch (_: Throwable) { null } else null
                BatchSaver.label(t, name)
            }
            treeKnown = true
        }
        return treeCache
    }

    private fun treeChanged() { treeKnown = false }

    private fun releaseUri(u: String) {
        try { contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
    }

    // work for the history thread; dropped once the activity is gone and the thread has ended
    private fun onHistory(r: () -> Unit) {
        try { historyExec.execute { try { r() } catch (_: Throwable) {} } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun onSaveExec(r: () -> Unit) {
        try { saveExec.execute { r() } } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    // historyExec: items that fell off the history take their kept permissions with them
    private fun dropped(gone: List<HistoryItem>) {
        if (gone.isEmpty()) return
        for (u in History.releasable(gone, history.items(), prefs.getString("saveTree", null))) releaseUri(u)
    }

    // historyExec: the entries saved since the last write, one write
    private fun flushHistory() = dropped(batcher.flush())

    // a queued Save all file through `tree` ended (any batch, any outcome): its folder may now be free to give back
    private fun treeEnded(tree: String) { for (t in trees.ended(tree, prefs.getString("saveTree", null))) releaseUri(t) }

    // app start (not a recreation): a folder permission from an earlier run that Save all no longer uses (a change
    // made while saves were queued, then the app went away) goes back; saves to single documents keep theirs
    private fun sweepTrees() {
        val keep = prefs.getString("saveTree", null)
        try {
            for (p in contentResolver.persistedUriPermissions) if (DocumentsContract.isTreeUri(p.uri) && p.uri.toString() != keep) releaseUri(p.uri.toString())
        } catch (_: Throwable) {}
    }

    private fun pickFolder() {
        try { treeLauncher.launch(tree()) } catch (_: ActivityNotFoundException) {
            pendingSaveAll = false; pendingArrive = false
            doneNote = "This phone has no folder picker. Save the files one by one instead."; pushFiles()
        }
    }

    private fun resetBatch() {
        batchGen++
        batchToken = HistoryBatcher.token()
        batchName = null; batchTotal = 0; batchReceived = 0; batchFailed = 0; batchBytesDone = 0; batchStream = 0
        batchDir = null; batchDone = false; files = null; pageBusy = false; pageAt = 0; pageKey = ""
        pageSource = null; labBatch = false
        ledger.clear(); savedMarks++; saveRun = 0; saveRunDone = 0; saveNow = null; saveOk = 0; saveFails.clear()
        saveWhere = null; folderLost = null; pendingSaveAll = false; rowTarget = -1
        val gen = batchGen
        pageExec.execute { if (gen == batchGen) pages = BatchFiles() }
        if (pane == Pane.FILES) setPane(Pane.NONE)
    }

    private fun receivedCount() = files?.count(Filter.RECEIVED) ?: batchReceived

    // the batch's counts from the status (O(1) in the core); the list itself is read in pages when they change
    private fun onBatchStatus(b: JSONObject, done: Long, stream: Long) {
        batchName = b.optString("name").ifEmpty { batchName }
        batchTotal = b.optInt("total"); batchReceived = b.optInt("received"); batchFailed = b.optInt("failed")
        batchBytesDone = done; batchStream = stream
        if (batchDir == null) batchName?.let { n -> sessions.dir?.let { batchDir = File(it, n) } }
        requestPages(false)
    }

    // at most once a second, and only when the counts moved or a file is part way in (its progress)
    private fun requestPages(force: Boolean) {
        if (pageBusy || batchName == null) return
        val now = SystemClock.elapsedRealtime()
        val key = "$batchTotal/$batchReceived/$batchFailed"
        val moving = files?.let { it.count(Filter.MISSING) > 0 } ?: true
        if (!force && (now - pageAt < PAGE_MS || (key == pageKey && (!moving || pane != Pane.FILES || now - pageAt < 2 * PAGE_MS)))) return
        if (!force && key == pageKey && !moving) return
        pageBusy = true; pageAt = now; pageKey = key
        val gen = batchGen; val src = pageSource
        pageExec.execute {
            var snap: BatchFiles? = null
            var busy = false
            try {
                // the receiver busy with a feed (maybe writing a large file): the pages read so far, the rest later
                val out = PageReader.readAll(pages, PAGE, { off, n ->
                    src?.invoke(off, n) ?: sessions.withHandle { h -> if (h == 0L) PageReader.NO_SESSION else Native.batchPage(h, off, n) }
                }, { gen == batchGen })
                busy = out == PageReader.Outcome.BUSY
                snap = pages.snapshot()
            } catch (_: Throwable) {}
            ui.post {
                if (isDestroyed || gen != batchGen) return@post
                pageBusy = false
                if (snap != null) { files = snap; onFilesChanged() }
                if (busy) { pageKey = ""; ui.postDelayed({ if (!isDestroyed && gen == batchGen) requestPages(true) }, BUSY_RETRY_MS) }
            }
        }
    }

    private fun onFilesChanged() {
        if (prefs.getBoolean("saveArrive", false) && tree() != null && !labBatch) saveAll(quiet = true)
        pushFiles()
    }

    // the list's screen soon (at most every PUSH_MS): many saves reporting make one update
    private fun schedulePush() { pushGate.request(SystemClock.elapsedRealtime())?.let { ui.postDelayed(pushRun, it) } }

    // the list's header in real numbers, and each row's save mark
    private fun pushFiles() {
        val name = batchName ?: return
        val f = files
        val received = f?.count(Filter.RECEIVED) ?: batchReceived
        val failed = f?.count(Filter.FAILED) ?: batchFailed
        val total = if (f?.known == true) f.total else batchTotal
        val savedOk = ledger.savedCount
        val queued = ledger.queuedCount
        val unsaved = if (f == null) 0 else ledger.unsaved(received)
        val t = tree()
        val label = when {
            received == 0 -> null
            savedOk == 0 && queued == 0 -> if (batchDone) "Save all" else "Save %,d received".format(received)
            unsaved > 0 -> "Save the other %,d".format(unsaved)
            queued > 0 -> "Saving…"
            else -> "All saved"
        }
        val problem = folderLost ?: saveFails.takeIf { it.isNotEmpty() }?.values?.let { fl ->
            val names = fl.take(2).joinToString(", ") { "${it.first} (${it.second})" } + if (fl.size > 2) " +${fl.size - 2}" else ""
            (if (fl.size == 1) "1 file not saved: " else "%,d files not saved: ".format(fl.size)) + names
        }
        val line = when {
            queued > 0 -> saveNow
            else -> null
        }
        // after a save its result takes the folder row: "Saved 4 files to SD card › LensBolt › trip" beside Change
        val saved = if (queued == 0 && saveOk > 0) (if (saveOk == 1) "Saved 1 file to " else "Saved %,d files to ".format(saveOk)) + (saveWhere ?: t?.let { treeWords } ?: "the folder") else null
        val header = FilesHeader(
            done = batchDone, title = name,
            summary = if (batchDone) BatchWords.done(total, received, failed, f?.bytes(Filter.RECEIVED) ?: 0) else BatchWords.receiving(received, total, failed, batchBytesDone, batchStream),
            meta = if (batchDone && doneSecs > 0) "${Fmt.duration(doneSecs)} · %.2f Mbit/s".format(doneStream * 8 / doneSecs / 1e6) else null,
            saveLabel = label, saveEnabled = unsaved > 0 && queued == 0 || (savedOk == 0 && queued == 0 && received > 0),
            folder = t?.let { treeWords }, saved = saved, saveLine = line, saveProblem = problem,
            saveProgress = if (queued > 0) saveFrac else null, state = BatchWords.stateOf(batchDone, total, received, failed),
            arrive = if (batchDone) null else prefs.getBoolean("saveArrive", false),
            note = doneNote, donate = batchDone && donate, marks = savedMarks, saving = queued > 0)
        screen.setFiles(f, header) { i -> ledger.note(i) }
        updateBack()
    }

    /** Every received file not saved yet (or whose save failed) into the picked folder, one at a time. */
    private fun saveAll(quiet: Boolean = false) {
        val t = tree() ?: run { if (!quiet) { pendingSaveAll = true; pickFolder() }; return }
        val f = files ?: return
        val dir = batchDir ?: return
        val name = batchName ?: return
        val todo = (0 until f.total).filter { f.state(it) == FileState.RECEIVED && ledger.needsSave(it) }
        if (todo.isEmpty()) return
        if (ledger.queuedCount == 0) { saveRun = 0; saveRunDone = 0 }
        folderLost = null
        val gen = batchGen; val key = "${sessions.dir?.name}/$gen"; val tKey = t.toString()
        // the history entry this run adds to: this batch session, this folder
        val historyId = HistoryBatcher.batchId(sessions.dir?.name, gen, batchToken, tKey); val lab = labBatch
        // the whole run counted before any of it can end (the last one to end writes the history)
        pendingSaves.addAndGet(todo.size)
        for (i in todo) {
            ledger.queue(i); trees.queued(tKey); saveRun++
            val fileName = f.name(i); val size = f.size(i)
            saveFails.remove(i)
            saveExec.execute {
                if (gen != batchGen) {
                    // an older batch (New transfer, discarded): nothing more is saved
                    if (pendingSaves.decrementAndGet() == 0) onHistory { flushHistory() }
                    ui.post { treeEnded(tKey) }
                    return@execute
                }
                val src = File(dir, fileName)
                var r: BatchSaver.Result? = null; var lost = false
                try {
                    r = saver.save(t, name, key, src, fileName) { pct -> ui.post { if (gen == batchGen && ledger.isQueued(i)) { saveNow = BatchWords.saving(saveRunDone, saveRun, fileName); saveFrac = BatchWords.saveFraction(saveRunDone, saveRun, pct); schedulePush() } } }
                } catch (_: BatchSaver.FolderLost) { lost = true } catch (e: Throwable) { r = BatchSaver.Result(e.message ?: "write error", fileName, null) }
                val where = saver.where(t)
                // the history entry adds up on the history thread: written every 500 files or 5 s, and at the run's end
                val last = pendingSaves.decrementAndGet() == 0
                if (r?.error == null && r?.uri != null && !lab) {
                    val at = where ?: BatchSaver.label(t); val now = System.currentTimeMillis()
                    onHistory { batcher.saved(historyId, name, at, size, now); if (last || batcher.due(now)) flushHistory() }
                } else if (last) onHistory { flushHistory() }
                ui.post {
                    treeEnded(tKey)
                    if (isDestroyed || gen != batchGen) return@post
                    saveRunDone++; savedMarks++
                    when {
                        lost -> when (trees.lost(tKey, prefs.getString("saveTree", null))) {
                            TreeKeeper.Lost.FORGET -> {
                                ledger.forget(i)
                                folderLost = "LensBolt can no longer write to ${treeWords ?: BatchSaver.label(t)}. Choose the folder again."
                                prefs.edit().remove("saveTree").apply(); treeChanged()
                            }
                            // the rest of the run through the folder just lost: as if never tried (said once above)
                            TreeKeeper.Lost.SAME -> ledger.forget(i)
                            // the folder the user changed away from: this file is not saved, the new folder is kept
                            TreeKeeper.Lost.OTHER -> { ledger.done(i, "the earlier folder can no longer be written"); saveFails[i] = fileName to "the earlier folder can no longer be written" }
                        }
                        r?.error == null -> { ledger.done(i, null); saveOk++; saveWhere = where }
                        else -> { ledger.done(i, r.error); saveFails[i] = fileName to (r.error ?: "") }
                    }
                    // the run's end shows at once (and is announced once); progress on the way at most every PUSH_MS
                    if (ledger.queuedCount == 0) { saveNow = null; pushFiles() } else schedulePush()
                }
            }
        }
        pushFiles()
    }

    // one row through the system save screen
    private fun saveRow(i: Int, uri: Uri) {
        val f = files ?: return; val dir = batchDir ?: return
        val fileName = f.name(i); val size = f.size(i); val sha = f.sha256(i); val gen = batchGen; val batch = batchName; val lab = labBatch
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Throwable) {}
        ledger.queue(i); savedMarks++; saveNow = "Saving $fileName"; saveFrac = 0f; pushFiles()
        saveExec.execute {
            val err = try { saver.copy(File(dir, fileName), uri) { pct -> ui.post { if (gen == batchGen) { saveNow = "Saving $fileName"; saveFrac = pct.coerceIn(0, 100) / 100f; schedulePush() } } } } catch (e: Throwable) { e.message ?: "write error" }
            if (err != null) try { DocumentsContract.deleteDocument(contentResolver, uri) } catch (_: Throwable) {}
            if (err == null && !lab) { val item = HistoryItem(displayName(uri) ?: fileName, size, System.currentTimeMillis(), BatchSaver.whereSaved(uri), uri.toString(), batch, sha); onHistory { dropped(history.add(item)) } }
            ui.post {
                if (isDestroyed || gen != batchGen) return@post
                savedMarks++
                ledger.done(i, err)
                if (err == null) { saveOk++; saveWhere = BatchSaver.whereSaved(uri); saveFails.remove(i) } else saveFails[i] = fileName to err
                if (ledger.queuedCount == 0) saveNow = null
                pushFiles()
            }
        }
    }

    private fun rowFile(i: Int): File? {
        val f = files ?: return null
        val file = File(batchDir ?: return null, f.name(i))
        if (file.isFile) return file
        doneNote = "${f.name(i)} was removed (Android can clear LensBolt's storage)."; pushFiles(); return null
    }

    override fun onFileRow(index: Int, a: RowAction) {
        val f = files ?: return
        if (index !in 0 until f.total || f.state(index) != FileState.RECEIVED) return
        if (a == RowAction.COPY_SHA) {
            val sha = f.sha256(index) ?: return
            (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha))
            screen.announce("SHA-256 of ${f.name(index)} copied")
            return
        }
        val file = rowFile(index) ?: return
        val mime = BatchSaver.mimeOf(file.name)
        when (a) {
            RowAction.SAVE -> {
                rowTarget = index
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE, file.name)
                try { rowSaveLauncher.launch(i) } catch (_: ActivityNotFoundException) { doneNote = "This phone has no save screen. Use Share instead."; pushFiles() }
            }
            RowAction.OPEN -> {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Storage.contentUri(this, file), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { startActivity(i) } catch (_: ActivityNotFoundException) { doneNote = "No app on this phone opens ${file.name}. Save it or Share it instead."; pushFiles() }
            }
            RowAction.SHARE -> {
                val uri = Storage.contentUri(this, file)
                val i = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                i.clipData = ClipData.newRawUri(file.name, uri)
                startActivity(Intent.createChooser(i, file.name))
            }
            RowAction.COPY_SHA, RowAction.SHOW_SHA, RowAction.HIDE_SHA -> {}   // copied above; shown or hidden by the list
        }
    }

    override fun onHistoryRow(index: Int, a: RowAction) {
        val h = historyItems.getOrNull(index) ?: return
        if (a == RowAction.COPY_SHA) {
            val sha = h.sha ?: return
            (getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("SHA-256", sha))
            screen.announce("SHA-256 copied")
            return
        }
        val uri = h.uri?.let(Uri::parse) ?: return
        val mime = BatchSaver.mimeOf(h.name)
        // still there, and still LensBolt's to open: else say so
        val there = try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } == true } catch (_: Throwable) { false }
        if (!there) { showHistory("LensBolt can no longer open ${h.name}: it was moved or deleted, or Android ended the permission."); return }
        val i = if (a == RowAction.SHARE) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).also { it.clipData = ClipData.newRawUri(h.name, uri) }
                else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(if (a == RowAction.SHARE) Intent.createChooser(i, h.name) else i) }
        catch (_: ActivityNotFoundException) { showHistory("No app on this phone opens ${h.name}.") }
        catch (_: SecurityException) { showHistory("LensBolt can no longer open ${h.name}: Android ended the permission.") }
    }

    private fun showHistory(note: String? = null) {
        historyNote = note
        val lab = labHistory
        if (lab != null) { historyItems = lab; screen.setHistory(lab, note); return }
        onHistory {
            val items = try { history.items() } catch (_: Throwable) { emptyList() }
            ui.post { if (!isDestroyed) { historyItems = items; screen.setHistory(items, historyNote) } }
        }
    }

    private fun setPane(p: Pane) {
        pane = p
        screen.showPane(p)
        updateBack()
    }

    override fun onShowPane(p: Pane) {
        when (p) {
            Pane.HISTORY -> { showHistory(); setPane(Pane.HISTORY) }
            Pane.FILES -> { setPane(Pane.FILES); requestPages(true); pushFiles() }
            Pane.NONE -> setPane(Pane.NONE)
        }
    }

    override fun onPane(a: PaneAction) {
        when (a) {
            PaneAction.CLOSE -> setPane(Pane.NONE)
            PaneAction.SAVE_ALL -> saveAll()
            PaneAction.CHANGE_FOLDER -> pickFolder()
            PaneAction.ARRIVE_ON -> if (tree() == null) { pendingArrive = true; pickFolder() } else { prefs.edit().putBoolean("saveArrive", true).apply(); saveAll(quiet = true); pushFiles() }
            PaneAction.ARRIVE_OFF -> { prefs.edit().putBoolean("saveArrive", false).apply(); pushFiles() }
            PaneAction.NEW_TRANSFER -> askNewTransfer()
            PaneAction.CLEAR_HISTORY -> confirm(Asks.clearHistory(), onRisky = clear@{
                    if (labHistory != null) { labHistory = emptyList(); showHistory(); return@clear }
                    onHistory {
                        // the permissions taken for single saves go with the list (Save all's folder keeps its own)
                        val items = try { history.items() } catch (_: Throwable) { emptyList() }
                        history.clear()
                        for (u in History.releasable(items, emptyList(), prefs.getString("saveTree", null))) releaseUri(u)
                        ui.post { if (!isDestroyed) showHistory() }
                    }
                })
        }
    }

    // New transfer from a batch's Done screen: files not saved yet (or still being saved) are asked about first
    private fun askNewTransfer() {
        val ask = if (batchDone && !savedOrShared) BatchAsk.leaving(receivedCount(), ledger.savedCount, ledger.queuedUnsavedCount, newTransfer = true) else null
        if (ask == null) { onAction(Action.NEW_TRANSFER); return }
        confirm(Asks.batch(ask), onRisky = { onAction(Action.NEW_TRANSFER) }, onSafe = { if (!ask.saving) saveAll() })
    }

    // `counted`: a transfer that just completed (it counts towards the support card)
    private fun enterBatchDone(dir: File, secs: Double, stream: Long, counted: Boolean) {
        if (batchDone) return
        batchDone = true; batchDir = dir; doneSecs = secs; doneStream = stream
        if (batchName == null) batchName = dir.name
        if (counted) donate = countTransfer()
        bindCamera()   // the camera stops: nothing more to read
        if (pane == Pane.FILES) pane = Pane.NONE
        screen.showPane(pane)
        show(UiState.BatchDone(batchName!!))
        pageBusy = false
        requestPages(true)   // the final list
        pushFiles()
    }

    // a recreated Done screen of a batch: its list comes from the folder (every file there is verified)
    private fun folderPages(dir: File): (Int, Int) -> String {
        val list = (dir.listFiles()?.filter { it.isFile } ?: emptyList()).sortedBy { it.name }
        return { off, count ->
            val part = list.drop(off).take(count).joinToString(",") { JSONObject().put("name", it.name).put("size", it.length()).put("state", "received").toString() }
            "{\"offset\":$off,\"total\":${list.size},\"files\":[$part]}"
        }
    }

    // ---- lab: canned screens for screenshots ---------------------------------------------------------------

    // lab only (files/lab.on, API 30+ or a debuggable build): `am start -n com.lensbolt.app/.MainActivity --es
    // lab_state <permission|permission_denied|aim|aim_closer|aim_square|locked|receiving|receiving_words|done|
    // problem_space|problem_fps|problem_camera>` renders that state with the camera off (no room in the picture);
    // `--ez lab_details true` shows Details with canned numbers. `done` is a real Done screen over a 3 MB test
    // file (Save / Open / Share work); `--ez lab_copy true` also runs the save copy into a test document,
    // `--ez lab_donate true` shows the support card.
    // New transfer leaves the lab screen.
    private fun showLab(name: String, i: Intent) {
        labHistory = null
        if (pane != Pane.NONE) setPane(Pane.NONE)
        if (name == "batch_done" || name == "batch_receiving" || name == "history") { labBatchScreen(name, i); return }
        if (name == "done") {
            if (batchDone) newSession()
            labState = null
            labDone(i.getBooleanExtra("lab_copy", false))
            // `--ez lab_donate true`: the support card on it (the counts in the preferences are not touched)
            donate = i.getBooleanExtra("lab_donate", false); renderDone()
        } else {
            labState = name
            if (doneFile != null || batchDone || batchName != null) newSession()
            bindCamera()   // wantCamera() is false: the camera stops
            screen.setImageAspect(4f / 3f)
            val receiving = UiState.Receiving("holiday-photos.zip", 14_900_000, 39_321_600, 3.48, 56, Hints.GOOD)
            show(when (name) {
                "permission" -> UiState.Permission(false)
                "permission_denied" -> UiState.Permission(true)
                "aim" -> UiState.Aim(Hints.SEARCH)
                "aim_closer" -> UiState.Aim(Hints.CLOSER)
                "aim_square" -> UiState.Aim(Hints.SQUARE)
                "locked" -> UiState.Locked(null, Hints.GOOD)
                "receiving", "problem_fps" -> receiving
                // a 5 GB file (320 parts) near its end: a four-word code, the longest there is
                "receiving_words" -> UiState.Receiving("site-backup.tar", 4_960_000_000, 5_368_709_120, 3.48, 940, Hints.GOOD,
                    finishWords = "satin lullaby disco apple", finishParts = "Parts 4, 15") // the parts its window names
                "problem_space" -> UiState.Problem("Not enough space",
                    "212 MB free, 1,086 MB needed for this file. Free some space, then try again.", Action.NEW_TRANSFER, "Try again")
                "problem_camera" -> cameraProblemState()
                else -> UiState.Aim(Hints.SEARCH)
            })
            screen.setBanner(if (name == "problem_fps") SenderFps.banner(60.0, 29.8) else null, SenderFps.suggestion(29.8))
        }
        if (i.getBooleanExtra("lab_details", false)) {
            detailsOn = true; screen.setDetailsOn(true)   // this run only: the remembered choice is unchanged
            lastRows = listOf(
                "Camera" to "1440x1080 · 29.8 fps · Auto (Full HD)", "Sender" to "60.0 fps", "Decode" to "65 ms (b/w 62, colour 141)",
                "Captures" to "3,195", "Decoded" to "2,557 · 44 failed", "Dropped" to "592 (18.5%)", "Colour skipped" to "3",
                "Codewords" to "83 / 83", "Format" to "2px bw 1024x1024", "Cell size" to "1.92 camera px · perspective 1.04", "Exposure" to "EV -2")
            screen.setDetails(lastRows)
        }
    }

    // lab: `batch_done` (three real files on disk: Save all, Open and Share work), `batch_receiving` (a list of
    // `--ei lab_files N` files, 40 by default, in every state; `--ez lab_list true` opens it), `history` (canned
    // items, never written to the real history). Lab batches are never added to the history.
    private fun labBatchScreen(name: String, i: Intent) {
        if (doneFile != null || batchDone || batchName != null) newSession()
        if (name == "history") {
            labState = name; bindCamera(); screen.setImageAspect(4f / 3f)
            show(UiState.Aim(Hints.SEARCH))
            val now = System.currentTimeMillis()
            labHistory = listOf(
                HistoryItem("registry-workbook-2026-quarterly-export-final-signed.zip", 2_202_009, now - 120_000, "Download/Transfers/holiday-photos", "content://lab/1", "holiday-photos"),
                HistoryItem("IMG_20261030_204812.jpg", 3_412_771, now - 125_000, "Download/Transfers/holiday-photos", "content://lab/2", "holiday-photos"),
                HistoryItem("site-backup.tar", 5_368_709_120, now - 86_400_000, "Documents", "content://lab/3", null),
                HistoryItem("notes.txt", 1_204, now - 3 * 86_400_000L, "the chosen place", null, null))
            showHistory(); setPane(Pane.HISTORY)
            return
        }
        labBatch = true; batchName = "holiday-photos"
        if (name == "batch_done") {
            labState = null
            val dir = File((sessions.dir ?: Storage.rxDir(this)).apply { mkdirs() }, "holiday-photos").apply { mkdirs() }
            fun make(n: String, size: Int) { val f = File(dir, n); if (f.length() != size.toLong()) { var x = n.hashCode().toLong() or 1L; f.writeBytes(ByteArray(size) { x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); x.toByte() }) } }
            make("registry-workbook-2026-quarterly-export-final-signed.zip", 2_097_152); make("IMG_20261030_204812.jpg", 204_800); make("notes.txt", 1_204)
            pageSource = folderPages(dir)
            val sum = dir.listFiles()!!.sumOf { it.length() }
            donate = i.getBooleanExtra("lab_donate", false)
            enterBatchDone(dir, 12.4, sum, counted = false)
            return
        }
        labState = name
        bindCamera(); screen.setImageAspect(4f / 3f)
        val n = i.getIntExtra("lab_files", 40).coerceIn(1, 100_000)
        val got = (n * 3) / 10
        pageSource = { off, count ->
            val part = (off until minOf(n, off + count)).joinToString(",") { k ->
                val nm = when (k) { 0 -> "registry-workbook-2026-quarterly-export-final-signed.zip"; 1 -> "IMG_20261030_204812.jpg"; 2 -> "notes.txt"; else -> "IMG_2026103%04d.jpg".format(k) }
                val o = JSONObject().put("name", nm).put("size", if (k == 0) 2_202_009 else 180_000 + k * 731)
                when {
                    k < got -> o.put("state", "received")
                    k == got -> o.put("state", "receiving").put("progress", 0.37)
                    k == n - 1 && n > 3 -> o.put("state", "failed").put("reason", "storage full")
                    else -> o.put("state", "waiting")
                }
                o.toString()
            }
            "{\"offset\":$off,\"total\":$n,\"files\":[$part]}"
        }
        batchTotal = n; batchReceived = got; batchFailed = if (n > 3) 1 else 0; batchStream = 67_108_864; batchBytesDone = 18_874_368
        show(UiState.Receiving("holiday-photos", batchBytesDone, batchStream, 3.48, 140, Hints.GOOD,
            batch = BatchWords.receiving(batchReceived, batchTotal, batchFailed, batchBytesDone, batchStream)))
        requestPages(true)
        if (i.getBooleanExtra("lab_list", false)) onShowPane(Pane.FILES)
    }

    private fun labDone(copy: Boolean) {
        labDoneShown = true
        val dir = (sessions.dir ?: Storage.rxDir(this)).apply { mkdirs() }
        val f = File(dir, "lab-test.bin")
        if (!f.isFile) {
            var x = -0x61c8864680b583ebL
            f.writeBytes(ByteArray(3 shl 20) { x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17); x.toByte() })
        }
        // the lab's figure is a measured one (3 MB in 5.5 s = 4.6 Mbit/s, the MSI runs of 2026-11-03), never a made-up rate
        enterDone(f, 5.5, f.length())
        if (copy) copyTo(f, Storage.contentUri(this, File(dir, "lab-copy.bin")))
    }
}
