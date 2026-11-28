package com.keonyrus.tupovision

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

const val DEFAULT_MODEL = "gpt-6.1-sol"

/** Shown until the account's own list is read from the API. */
val FALLBACK_MODELS: List<String> = listOf("gpt-6.1-sol", "gpt-6-sol", "gpt-6-luna")

enum class Phase(val label: String) {
  IDLE("Stopped"),
  CONNECTING("Connecting"),
  LISTENING("Listening"),
  THINKING("Thinking"),
  SPEAKING("Speaking"),
  PAUSED("Paused"),
}

data class UiState(
    val metaInstalled: Boolean = false,
    val permissionsGranted: Boolean = false,
    val registration: String = "—",
    val registered: Boolean = false,
    val deviceCount: Int = 0,
    val hasActiveDevice: Boolean = false,
    val signedIn: Boolean = false,
    val loginInProgress: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val sessionState: String = "—",
    val streamState: String = "—",
    val frameInfo: String = "—",
    val partial: String = "",
    val question: String = "",
    val answer: String = "",
    val latencyMs: Long? = null,
    val frame: Bitmap? = null,
    val error: String? = null,
    val model: String = DEFAULT_MODEL,
    val models: List<String> = FALLBACK_MODELS,
    val glassesMic: Boolean = true,
    val photoMode: Boolean = false,
    val wakeWord: String = "",
    val language: String = "en-US",
    /** True: live voice through Gemini Live. False: ChatGPT plan with on-phone speech. */
    val liveMode: Boolean = false,
    /** MetaClaw: questions go to your own agent through the bridge. Wins over [liveMode]. */
    val clawMode: Boolean = true,
    val clawUrl: String = "",
    val clawTokenSet: Boolean = false,
    /** MetaClaw: wait for the wake phrase with the on-phone Vosk model instead of speech recognition. */
    val offlineWake: Boolean = true,
    /** Prefer a male text-to-speech voice, like the agentring phone line. */
    val maleVoice: Boolean = true,
    /** Send where the phone is with each question to your agent. */
    val shareLocation: Boolean = true,
    /** False when the APK was built without the wake-word model. */
    val offlineWakeAvailable: Boolean = false,
    val geminiKeySet: Boolean = false,
    val bargeIn: Boolean = true,
    /** A gesture on the glasses starts and stops the conversation. Not kept across restarts. */
    val standby: Boolean = false,
    /** MetaClaw: voice released after a while without questions, so "Hey Meta" works again. */
    val resting: Boolean = false,
    val standbyAudio: String = "",
    /** Whether the user has granted the access needed to watch other apps' media sessions. */
    val mediaAccess: Boolean = false,
    val triggerPackage: String = MediaWatch.DEFAULT_TRIGGER,
    val log: List<String> = emptyList(),
)

/**
 * The whole assistant: glasses session and camera, the listen → ask → speak loop, and the state the
 * screen shows. It outlives the activity so the conversation continues with the screen off.
 * Everything here runs on the main thread unless a block says otherwise.
 */
object Engine {
  private const val TAG = "TupoVision"
  private const val PREFS = "settings"
  private const val META_AI_PACKAGE = "com.facebook.stella"
  private const val MAX_HISTORY = 6
  private const val MAX_LOG = 200
  private const val PHOTO_TIMEOUT_MS = 3000L
  private const val STREAM_WAIT_MS = 10_000L
  private const val GEMINI_KEY = "gemini_api_key"
  private const val CLAW_TOKEN = "claw_bridge_token"
  private const val DEFAULT_WAKE = "hey claw, oye claw"
  // If the agent has not answered by then, say a short filler so silence is not mistaken for a hang.
  private const val CLAW_FILLER_MS = 2500L
  // MetaClaw: after this long without a question the glasses microphone is released. While the
  // app holds it as a Bluetooth call, the glasses do not answer "Hey Meta".
  private const val CLAW_IDLE_REST_MS = 2L * 60L * 1000L
  // Long answers (web searches) keep a short "still working" cue going so the wait never feels hung.
  private const val CLAW_KEEPALIVE_FIRST_MS = 8000L
  private const val CLAW_KEEPALIVE_EVERY_MS = 6500L
  // Lighter than the per-question frame: one of these goes up every second.
  private const val LIVE_JPEG_QUALITY = 65

  val REQUIRED_PERMISSIONS: Array<String> =
      arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO)

  private lateinit var app: Application
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val _state = MutableStateFlow(UiState())
  val state: StateFlow<UiState> = _state.asStateFlow()

  private val logLines = ArrayDeque<String>()
  private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

  // Glasses
  private var wearablesReady = false
  private val selector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var session: DeviceSession? = null
  private var camera: Camera? = null
  private var stream: Stream? = null
  private val sessionJobs = ArrayList<Job>()
  private var sessionStarted = false
  private var streaming = false
  private var serviceStarted = false

  // Voice
  private var stt: Stt? = null
  private var tts: Tts? = null
  private var voiceActive = false
  private var turnJob: Job? = null
  private var idleJob: Job? = null
  private var lastActivityAt = 0L
  private var errorStreak = 0
  // Offline wake phrase: the listener, and whether the current utterance follows a wake phrase.
  private var hotword: Hotword? = null
  private var wakeJob: Job? = null
  private var wokeOffline = false
  // Set when the offline model failed this session; speech recognition takes over until restart.
  private var offlineWakeBroken = false
  private val history = ArrayDeque<Turn>()

  // Live voice
  private var live: GeminiLive? = null
  private var liveHandle: String? = null
  // 0: full setup resuming the last session, 1: full setup from scratch, 2: bare minimum.
  private var liveAttempt = 0
  private var liveDrops = 0
  private var liveTurnDone = true

  // ---- setup ----

  fun attach(application: Application) {
    app = application
    val prefs = prefs()
    update {
      it.copy(
          signedIn = ChatGptAuth.isSignedIn(app),
          model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
          glassesMic = prefs.getBoolean("glasses_mic", true),
          photoMode = prefs.getBoolean("photo_mode", false),
          wakeWord = prefs.getString("wake_word", DEFAULT_WAKE) ?: DEFAULT_WAKE,
          language = prefs.getString("language", null) ?: Locale.getDefault().toLanguageTag(),
          geminiKeySet = SecureStore.get(app, GEMINI_KEY) != null,
          liveMode = prefs.getBoolean("live_mode", false),
          clawMode = prefs.getBoolean("claw_mode", true),
          clawUrl = prefs.getString("claw_url", "") ?: "",
          clawTokenSet = SecureStore.get(app, CLAW_TOKEN) != null,
          offlineWake = prefs.getBoolean("offline_wake", true),
          maleVoice = prefs.getBoolean("male_voice", true),
          shareLocation = prefs.getBoolean("share_location", true),
          offlineWakeAvailable = Hotword(app).available(),
          bargeIn = prefs.getBoolean("barge_in", true),
          triggerPackage =
              prefs.getString("trigger_package", null) ?: MediaWatch.DEFAULT_TRIGGER,
      )
    }
    refreshEnvironment()
    if (_state.value.signedIn) refreshModels()
  }

  /** Re-reads what can change while the app is in the background: installs and permissions. */
  fun refreshEnvironment() {
    val metaInstalled =
        runCatching { app.packageManager.getPackageInfo(META_AI_PACKAGE, 0) }.isSuccess
    val granted =
        REQUIRED_PERMISSIONS.all {
          app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    update {
      it.copy(
          metaInstalled = metaInstalled,
          permissionsGranted = granted,
          mediaAccess = MediaWatch.hasAccess(app),
      )
    }
    if (granted) ensureWearables()
  }

  /** The SDK may only be touched after Bluetooth permission is granted. */
  private fun ensureWearables() {
    if (wearablesReady) return
    wearablesReady = true
    Wearables.initialize(app).onFailure { error, _ ->
      log("Glasses SDK failed to start: ${error.description}")
    }
    scope.launch {
      Wearables.registrationState.collect { value ->
        update {
          it.copy(
              registration = value.toString(),
              registered = value == RegistrationState.REGISTERED,
          )
        }
      }
    }
    scope.launch {
      Wearables.devices.collect { devices -> update { it.copy(deviceCount = devices.size) } }
    }
    scope.launch {
      selector.activeDeviceFlow().collect { device ->
        update { it.copy(hasActiveDevice = device != null) }
      }
    }
  }

  fun register(activity: Activity) {
    if (!wearablesReady) {
      fail("Grant the Bluetooth and microphone permissions first")
      return
    }
    Wearables.startRegistration(activity)
  }

  fun unregister(activity: Activity) {
    if (wearablesReady) Wearables.startUnregistration(activity)
  }

  // ---- ChatGPT ----

  private var loginJob: Job? = null

  /** A second call while a sign-in is pending drops it and starts a fresh one. */
  fun signIn(activity: Activity) {
    loginJob?.cancel()
    update { it.copy(loginInProgress = true, error = null) }
    val job =
        scope.launch(start = CoroutineStart.LAZY) {
          try {
            ChatGptAuth.login(
                context = app,
                openBrowser = { url -> openInBrowser(activity, url) },
                onCodeReceived = { bringToFront() },
                log = { message -> log(message) },
            )
            update { it.copy(signedIn = true) }
            log("ChatGPT connected")
            bringToFront()
            refreshModels()
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            val offline = e is java.net.UnknownHostException || e is java.net.ConnectException
            if (offline) {
              // Measured before returning to the screen, so it shows what the app saw.
              log("Network as the app sees it: " + NetDiag.report(app))
              fail(
                  "ChatGPT sign-in: the browser signed in, but the app itself could not reach " +
                      "auth.openai.com. Details are in the log below.",
              )
            } else {
              fail("ChatGPT sign-in: ${e.message}")
            }
            bringToFront()
          } finally {
            if (loginJob === coroutineContext[Job]) update { it.copy(loginInProgress = false) }
          }
        }
    loginJob = job
    job.start()
  }

  fun signOut() {
    if (_state.value.running) stop()
    ChatGptAuth.signOut(app)
    update { it.copy(signedIn = false) }
    log("Signed out of ChatGPT. You can also revoke the app in ChatGPT settings.")
  }

  private fun openInBrowser(activity: Activity, url: Uri) {
    try {
      CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(activity, url)
    } catch (e: Exception) {
      activity.startActivity(Intent(Intent.ACTION_VIEW, url))
    }
  }

  /** Closes the sign-in tab by returning to the app's own screen. */
  private fun bringToFront() {
    runCatching {
      app.startActivity(
          Intent(app, MainActivity::class.java)
              .addFlags(
                  Intent.FLAG_ACTIVITY_NEW_TASK or
                      Intent.FLAG_ACTIVITY_CLEAR_TOP or
                      Intent.FLAG_ACTIVITY_SINGLE_TOP,
              ),
      )
    }
  }

  private fun refreshModels() {
    scope.launch {
      try {
        val token = ChatGptAuth.accessToken(app)
        val ids = Responses.listModels(token).filter { it.startsWith("gpt") }
        if (ids.isNotEmpty()) {
          update { state ->
            state.copy(
                models = ids,
                model = if (state.model in ids) state.model else pickDefault(ids),
            )
          }
          log("Models available on your plan: ${ids.size}")
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log("Could not load the model list: ${e.message}")
      }
    }
  }

  private fun pickDefault(ids: List<String>): String =
      ids.firstOrNull { it == DEFAULT_MODEL } ?: ids.firstOrNull { "sol" in it } ?: ids.first()

  // ---- settings ----

  fun setModel(value: String) {
    update { it.copy(model = value) }
    prefs().edit().putString("model", value).apply()
  }

  fun setGlassesMic(value: Boolean) {
    update { it.copy(glassesMic = value) }
    prefs().edit().putBoolean("glasses_mic", value).apply()
  }

  fun setPhotoMode(value: Boolean) {
    update { it.copy(photoMode = value) }
    prefs().edit().putBoolean("photo_mode", value).apply()
  }

  fun setWakeWord(value: String) {
    update { it.copy(wakeWord = value) }
    prefs().edit().putString("wake_word", value).apply()
  }

  fun setLanguage(value: String) {
    update { it.copy(language = value) }
    prefs().edit().putString("language", value).apply()
  }

  fun setLiveMode(value: Boolean) {
    update { it.copy(liveMode = value) }
    prefs().edit().putBoolean("live_mode", value).apply()
  }

  fun setClawMode(value: Boolean) {
    update { it.copy(clawMode = value) }
    prefs().edit().putBoolean("claw_mode", value).apply()
  }

  fun setShareLocation(value: Boolean) {
    update { it.copy(shareLocation = value) }
    prefs().edit().putBoolean("share_location", value).apply()
  }

  fun setMaleVoice(value: Boolean) {
    update { it.copy(maleVoice = value) }
    prefs().edit().putBoolean("male_voice", value).apply()
  }

  fun setOfflineWake(value: Boolean) {
    update { it.copy(offlineWake = value) }
    prefs().edit().putBoolean("offline_wake", value).apply()
  }

  fun setClawUrl(value: String) {
    val url = Claw.normalizeUrl(value)
    update { it.copy(clawUrl = url) }
    prefs().edit().putString("claw_url", url).apply()
  }

  /** The token stays on this phone, encrypted; it is only ever sent to your bridge. */
  fun setClawToken(value: String) {
    val token = value.trim()
    if (token.isEmpty()) return
    SecureStore.put(app, CLAW_TOKEN, token)
    update { it.copy(clawTokenSet = true) }
    log("Bridge token saved")
  }

  fun clearClawToken() {
    if (_state.value.running && _state.value.clawMode) stop()
    SecureStore.put(app, CLAW_TOKEN, null)
    update { it.copy(clawTokenSet = false) }
    log("Bridge token deleted")
  }

  fun testClaw() {
    val url = _state.value.clawUrl
    val token = SecureStore.get(app, CLAW_TOKEN)
    if (url.isEmpty() || token == null) {
      fail("Set the bridge address and token first")
      return
    }
    scope.launch {
      val problem = Claw.check(url, token)
      if (problem == null) log("Bridge reachable at $url") else fail("Bridge: $problem")
    }
  }

  fun setBargeIn(value: Boolean) {
    update { it.copy(bargeIn = value) }
    prefs().edit().putBoolean("barge_in", value).apply()
  }

  /** The key stays on this phone, encrypted; it is only ever sent to Google. */
  fun setGeminiKey(value: String) {
    val key = value.trim()
    if (key.isEmpty()) return
    SecureStore.put(app, GEMINI_KEY, key)
    update { it.copy(geminiKeySet = true) }
    setLiveMode(true)
    log("Gemini key saved, live voice is on")
  }

  fun clearGeminiKey() {
    if (_state.value.running && _state.value.liveMode) stop()
    SecureStore.put(app, GEMINI_KEY, null)
    update { it.copy(geminiKeySet = false) }
    setLiveMode(false)
    log("Gemini key deleted")
  }

  fun setTriggerPackage(value: String) {
    update { it.copy(triggerPackage = value) }
    prefs().edit().putString("trigger_package", value).apply()
  }

  /**
   * Must be switched on from the screen: the background service has to start while the app is
   * visible to be allowed to use the microphone later, when a gesture starts the conversation.
   */
  fun setStandby(value: Boolean) {
    if (value == _state.value.standby) return
    if (!value) {
      MediaWatch.stop()
      update { it.copy(standby = false, standbyAudio = "") }
      if (!_state.value.running && serviceStarted) {
        KeepAliveService.stop(app)
        serviceStarted = false
      }
      log("Standby off")
      return
    }
    if (!wearablesReady) {
      fail("Grant the Bluetooth and microphone permissions first")
      return
    }
    // Standby follows one audio device, so a car or other headphones cannot set it off.
    val glasses = MediaWatch.bluetoothOutputs(app).firstOrNull()
    if (glasses == null) {
      fail("Put the glasses on first: standby follows the Bluetooth audio device in use now")
      return
    }
    if (!MediaWatch.start(app, _state.value.triggerPackage.trim(), glasses)) {
      fail("Standby needs notification access. Grant it with the button under the switch.")
      return
    }
    try {
      if (!serviceStarted) KeepAliveService.start(app)
      serviceStarted = true
    } catch (e: Exception) {
      MediaWatch.stop()
      fail("Could not start standby: ${e.message}")
      return
    }
    update { it.copy(standby = true, standbyAudio = glasses) }
    log("Standby on, watching ${_state.value.triggerPackage.trim()} while music goes to $glasses")
  }

  /** Called when the standby gesture arrives from the glasses. */
  fun toggleFromGesture() {
    if (_state.value.running && _state.value.resting) {
      log("Gesture: listening again")
      MediaWatch.beep(starting = true)
      resumeListening()
      return
    }
    if (_state.value.running) {
      log("Gesture: stopping")
      MediaWatch.beep(starting = false)
      stop()
    } else {
      log("Gesture: starting")
      MediaWatch.beep(starting = true)
      start(null)
    }
  }

  fun clearError() {
    update { it.copy(error = null) }
  }

  fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

  // ---- conversation: glasses side ----

  /**
   * Starts the conversation. [requestPermission] opens the Meta AI app to ask for camera access,
   * which needs the activity, so the screen passes it in. A start from a gesture has no screen
   * and passes null; camera access must already have been granted then.
   */
  fun start(requestPermission: (suspend (Permission) -> PermissionStatus)?) {
    if (_state.value.running) return
    val claw = _state.value.clawMode
    if (claw && (_state.value.clawUrl.isEmpty() || !_state.value.clawTokenSet)) {
      fail("Set the bridge address and token first")
      return
    }
    if (!claw && _state.value.liveMode && !_state.value.geminiKeySet) {
      fail("Save a Gemini API key first")
      return
    }
    if (!claw && !_state.value.liveMode && !_state.value.signedIn) {
      fail("Sign in to ChatGPT first")
      return
    }
    if (!wearablesReady) {
      fail("Grant the Bluetooth and microphone permissions first")
      return
    }
    if (claw) Claw.newConversation()
    update { it.copy(running = true, phase = Phase.CONNECTING, error = null) }
    scope.launch {
      var granted = false
      Wearables.checkPermissionStatus(Permission.CAMERA)
          .onSuccess { status ->
            granted = status == PermissionStatus.Granted
            // A start from the glasses cannot ask, so the answer itself is worth seeing.
            if (requestPermission == null) log("Gesture: Meta AI reports camera access as $status")
          }
          .onFailure { error, _ -> log("Camera permission check: ${error.description}") }
      if (!granted && requestPermission != null) {
        granted = requestPermission(Permission.CAMERA) == PermissionStatus.Granted
      }
      if (!_state.value.running) return@launch
      if (!granted) {
        if (requestPermission == null) MediaWatch.beep(starting = false)
        fail(
            if (requestPermission == null) {
              "No standing camera access. Start once from the app and pick Always allow in Meta AI."
            } else {
              "Meta AI did not grant access to the glasses camera"
            },
        )
        shutdown()
        return@launch
      }
      try {
        if (!serviceStarted) KeepAliveService.start(app)
        serviceStarted = true
      } catch (e: Exception) {
        log("Could not start background mode: ${e.message}")
      }
      openSession()
    }
  }

  fun stop() {
    if (!_state.value.running) return
    log("Stopped")
    shutdown()
  }

  private fun openSession() {
    Wearables.createSession(selector)
        .fold(
            onSuccess = { created ->
              session = created
              // Subscribe before start() so no transition is missed.
              sessionJobs +=
                  scope.launch { created.state.collect { onSessionState(created, it) } }
              sessionJobs +=
                  scope.launch {
                    created.errors.collect { error ->
                      log("Session error: ${error.description}")
                      update { it.copy(error = "Glasses: ${error.description}") }
                    }
                  }
              created.start()
            },
            onFailure = { error, _ ->
              fail("Could not open a session with the glasses: ${error.description}")
              shutdown()
            },
        )
  }

  private fun onSessionState(current: DeviceSession, value: DeviceSessionState) {
    update { it.copy(sessionState = value.toString()) }
    when (value) {
      DeviceSessionState.STARTED -> {
        sessionStarted = true
        if (camera == null) attachCamera(current) else resumeIfReady()
      }
      DeviceSessionState.PAUSED -> {
        sessionStarted = false
        pauseVoice("The glasses paused the session")
      }
      DeviceSessionState.STOPPED -> {
        if (_state.value.running) {
          log("The glasses ended the session")
          shutdown()
        }
      }
      else -> Unit
    }
  }

  private fun attachCamera(current: DeviceSession) {
    current
        .addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 7))
        .fold(
            onSuccess = { added ->
              camera = added
              val video = added.stream
              stream = video
              sessionJobs +=
                  scope.launch(Dispatchers.Default) {
                    video.videoStream.collect { frame -> Frames.offer(frame) }
                  }
              sessionJobs += scope.launch { video.state.collect { onStreamState(it) } }
              sessionJobs +=
                  scope.launch {
                    video.errorStream.collect { error ->
                      log("Video stream error: ${error.description}")
                    }
                  }
              video.start().onFailure { error, _ ->
                log("Video stream did not start: ${error.description}")
              }
              // Voice still works if the picture never arrives; say so and carry on without it.
              sessionJobs +=
                  scope.launch {
                    delay(STREAM_WAIT_MS)
                    if (_state.value.running && !streaming && !voiceActive) {
                      log("No video from the glasses after 10 seconds, continuing without it")
                      startVoice()
                    }
                  }
            },
            onFailure = { error, _ ->
              fail("Glasses camera unavailable: ${error.description}")
              shutdown()
            },
        )
  }

  private fun onStreamState(value: StreamState) {
    update { it.copy(streamState = value.toString()) }
    when (value) {
      StreamState.STREAMING -> {
        streaming = true
        resumeIfReady()
      }
      // A single tap on the temple pauses the stream and a second one resumes it; the
      // conversation follows, which makes the tap a hands-free pause button.
      StreamState.PAUSED -> {
        streaming = false
        pauseVoice("Paused. Tap the temple to continue")
      }
      StreamState.STOPPED,
      StreamState.CLOSED -> streaming = false
      else -> Unit
    }
  }

  private fun resumeIfReady() {
    if (_state.value.running && sessionStarted && streaming && !voiceActive) startVoice()
  }

  private fun shutdown() {
    voiceActive = false
    idleJob?.cancel()
    idleJob = null
    turnJob?.cancel()
    turnJob = null
    wakeJob?.cancel()
    wakeJob = null
    offlineWakeBroken = false
    stt?.destroy()
    stt = null
    tts?.stop()
    live?.stop()
    live = null
    liveHandle = null
    liveAttempt = 0
    liveDrops = 0
    liveTurnDone = true
    AudioRouter.disable(app)
    sessionJobs.forEach { it.cancel() }
    sessionJobs.clear()
    runCatching { camera?.stop() }
    runCatching { session?.stop() }
    camera = null
    stream = null
    session = null
    sessionStarted = false
    streaming = false
    history.clear()
    Frames.reset()
    // In standby the service stays up, waiting for the next gesture.
    if (serviceStarted && !_state.value.standby) {
      KeepAliveService.stop(app)
      serviceStarted = false
    }
    update {
      it.copy(
          running = false,
          resting = false,
          phase = Phase.IDLE,
          partial = "",
          sessionState = "—",
          streamState = "—",
      )
    }
  }

  // ---- conversation: voice side ----

  private fun startVoice() {
    if (voiceActive) return
    voiceActive = true
    if (_state.value.liveMode && !_state.value.clawMode) {
      startLive()
      return
    }
    lastActivityAt = SystemClock.elapsedRealtime()
    if (_state.value.resting) update { it.copy(resting = false) }
    if (_state.value.clawMode) watchIdle()
    scope.launch {
      val settings = _state.value
      val routed = settings.glassesMic && AudioRouter.enable(app)
      if (settings.glassesMic && !routed) {
        log("Glasses microphone unavailable over Bluetooth, using the phone microphone")
      }
      if (!voiceActive) return@launch

      val speech = tts ?: Tts(app) { ok -> if (!ok) log("Text-to-speech failed to start") }.also { tts = it }
      withTimeoutOrNull(3000) { while (!speech.ready) delay(50) }
      if (!speech.configure(speechLanguage(), routed)) {
        log("The phone has no text-to-speech voice for ${speechLanguage()}")
      }
      if (settings.maleVoice) {
        val chosen = speech.preferMaleVoice(speechLanguage())
        if (chosen != null) log("Voice: $chosen")
        else log("No male voice found. Available: ${speech.voiceNames(speechLanguage()).joinToString()}")
      }

      val recognizer = stt ?: Stt(app, sttCallbacks).also { stt = it }
      if (!recognizer.isAvailable()) {
        fail("This phone has no speech recognition service")
        shutdown()
        return@launch
      }
      if (voiceActive) listen()
    }
  }

  private fun watchIdle() {
    idleJob?.cancel()
    idleJob =
        scope.launch {
          while (voiceActive) {
            delay(5000)
            val busy = turnJob?.isActive == true || tts?.isSpeaking == true
            if (busy) lastActivityAt = SystemClock.elapsedRealtime()
            if (!busy && SystemClock.elapsedRealtime() - lastActivityAt >= CLAW_IDLE_REST_MS) {
              pauseVoice("Resting after ${CLAW_IDLE_REST_MS / 60000} min without questions: " +
                  "the glasses microphone is free for Hey Meta. Tap Listen to continue.")
              update { it.copy(resting = true) }
              MediaWatch.beep(starting = false)
              return@launch
            }
          }
        }
  }

  /** MetaClaw: back from resting, from the screen, the notification or a standby gesture. */
  fun resumeListening() {
    if (!_state.value.running || voiceActive) return
    if (!sessionStarted || !streaming) {
      log("Cannot listen yet: the glasses session is not streaming")
      return
    }
    log("Listening again")
    startVoice()
  }

  private fun pauseVoice(reason: String) {
    if (!voiceActive) return
    voiceActive = false
    idleJob?.cancel()
    idleJob = null
    turnJob?.cancel()
    turnJob = null
    wakeJob?.cancel()
    wakeJob = null
    stt?.cancel()
    tts?.stop()
    // The live session is closed too; its handle lets the next one pick the conversation up.
    live?.stop()
    live = null
    liveAttempt = 0
    // Free the hands-free link so music can play through the glasses while paused.
    AudioRouter.disable(app)
    log(reason)
    update { it.copy(phase = Phase.PAUSED, partial = "") }
  }

  private fun listen() {
    if (!voiceActive) return
    wokeOffline = false
    if (useOfflineWake()) {
      listenForWake()
      return
    }
    startRecognizer()
  }

  private fun useOfflineWake(): Boolean {
    val s = _state.value
    return s.clawMode && s.offlineWake && s.offlineWakeAvailable && !offlineWakeBroken &&
        wakePhrases(s.wakeWord).isNotEmpty()
  }

  /**
   * Waits for "hey Claw" / "oye Claw" on the phone, without sending audio anywhere, then beeps and
   * lets speech recognition take the question.
   */
  private fun listenForWake() {
    if (wakeJob?.isActive == true) return
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    val listener = hotword ?: Hotword(app).also { hotword = it }
    val input = AudioRouter.inputDevice(app)
    log("Waiting for the wake phrase on the ${if (input != null) "glasses" else "phone"} microphone")
    wakeJob =
        scope.launch {
          val heard =
              try {
                listener.await(input) { log("Wake model heard: $it") }
              } catch (e: CancellationException) {
                throw e
              } catch (e: Exception) {
                offlineWakeBroken = true
                log("Offline wake phrase failed (${e.message}), using speech recognition")
                if (voiceActive) startRecognizer()
                return@launch
              }
          if (!voiceActive || heard.isEmpty()) return@launch
          log("Wake phrase heard on the phone")
          beep()
          wokeOffline = true
          startRecognizer()
        }
  }

  /** A short pip so you know it is listening for the question. */
  private fun beep() {
    runCatching {
      val stream =
          if (AudioRouter.isRouted) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
      val tone = ToneGenerator(stream, 80)
      tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
      scope.launch {
        delay(400)
        tone.release()
      }
    }
  }

  private fun startRecognizer() {
    if (!voiceActive) return
    update { it.copy(phase = Phase.LISTENING, partial = "") }
    try {
      stt?.start(speechLanguage())
    } catch (e: Exception) {
      log("Speech recognition did not start: ${e.message}")
      listenAfter(1000)
    }
  }

  private fun speechLanguage(): String =
      _state.value.language.ifBlank { Locale.getDefault().toLanguageTag() }

  private fun listenAfter(delayMs: Long) {
    scope.launch {
      delay(delayMs)
      if (voiceActive && turnJob?.isActive != true && wakeJob?.isActive != true) listen()
    }
  }

  private val sttCallbacks =
      object : Stt.Callbacks {
        override fun onSpeechStart() {}

        override fun onPartial(text: String) {
          update { it.copy(partial = text) }
        }

        override fun onFinal(text: String) {
          errorStreak = 0
          onUtterance(text)
        }

        override fun onError(code: Int) {
          when (code) {
            // Nobody spoke: just listen again.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listenAfter(150)
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
              stt?.cancel()
              listenAfter(500)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
              fail("No access to the microphone")
              shutdown()
            }
            else -> {
              errorStreak += 1
              log("Speech recognition error $code")
              listenAfter(if (errorStreak > 5) 3000 else 800)
            }
          }
        }
      }

  private fun onUtterance(text: String) {
    val heard = text.trim()
    if (heard.isEmpty()) {
      listen()
      return
    }
    var question = heard
    val phrases = wakePhrases(_state.value.wakeWord)
    if (phrases.isNotEmpty()) {
      // With wake phrases set, everything that does not contain one of them is ignored.
      val normalized = normalizeWake(heard)
      val match =
          phrases
              .mapNotNull { p -> Regex("\\b" + Regex.escape(p) + "\\b").find(normalized) }
              .minByOrNull { it.range.first }
      // After an offline wake phrase the question comes on its own, without the phrase.
      if (match == null && !wokeOffline) {
        listen()
        return
      }
      if (match != null) {
        question =
            normalized.substring(match.range.last + 1).trim().trimStart(',', '.', '!', '?', ' ')
      }
      if (question.isEmpty()) {
        listen()
        return
      }
    }
    lastActivityAt = SystemClock.elapsedRealtime()
    turnJob = scope.launch { runTurn(question) }
  }

  private suspend fun runTurn(question: String) {
    if (_state.value.clawMode) {
      runClawTurn(question)
      return
    }
    val startedAt = SystemClock.elapsedRealtime()
    update {
      it.copy(
          phase = Phase.THINKING,
          question = question,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }

    val jpeg = withContext(Dispatchers.Default) { acquireFrame() }
    val preview = withContext(Dispatchers.Default) { jpeg?.let { Frames.preview(it) } }
    update { it.copy(frame = preview, frameInfo = Frames.info) }
    if (jpeg == null) log("No frame from the glasses, answering without a picture")

    val answer = StringBuilder()
    val chunker = SentenceChunker()
    var spoke = false
    fun say(sentence: String) {
      val clean = cleanForSpeech(sentence)
      if (clean.isEmpty()) return
      if (!spoke) {
        spoke = true
        val latency = SystemClock.elapsedRealtime() - startedAt
        update { it.copy(phase = Phase.SPEAKING, latencyMs = latency) }
      }
      tts?.speak(clean)
    }

    try {
      val settings = _state.value
      var forceRefresh = false
      var effort: String? = "low"
      var tries = 0
      while (true) {
        tries += 1
        try {
          val token = ChatGptAuth.accessToken(app, forceRefresh)
          val body =
              Responses.buildBody(
                  model = settings.model,
                  instructions = instructions(jpeg != null),
                  history = history.toList(),
                  question = question,
                  jpeg = jpeg,
                  reasoningEffort = effort,
              )
          Responses.stream(token, body).collect { delta ->
            answer.append(delta)
            update { it.copy(answer = answer.toString()) }
            chunker.push(delta).forEach { say(it) }
          }
          break
        } catch (e: ApiException) {
          val canRetry = tries < 3 && answer.isEmpty()
          if (canRetry && e.status == 401) {
            forceRefresh = true
            continue
          }
          // Not every model takes a reasoning effort; ask again without it.
          val aboutReasoning = (e.message ?: "").contains("reasoning", ignoreCase = true)
          if (canRetry && e.status == 400 && effort != null && aboutReasoning) {
            effort = null
            continue
          }
          throw e
        }
      }
      chunker.flush()?.let { say(it) }

      val text = answer.toString().trim()
      if (text.isNotEmpty()) {
        history.addLast(Turn(question, text))
        while (history.size > MAX_HISTORY) history.removeFirst()
      }
      awaitSpeechEnd()
    } catch (e: CancellationException) {
      throw e
    } catch (e: AuthException) {
      fail(e.message ?: "Sign in to ChatGPT")
      update { it.copy(signedIn = ChatGptAuth.isSignedIn(app)) }
      shutdown()
      return
    } catch (e: Exception) {
      val limit = e is ApiException && e.status == 429
      fail(
          if (limit) "ChatGPT plan limit reached. See chatgpt.com/settings/usage"
          else "ChatGPT request failed: ${e.message}",
      )
      tts?.speak(spokenError(limit))
      awaitSpeechEnd()
    }
    listen()
  }

  /** Comma-separated wake phrases from settings, lower case, sound-alikes folded. */
  private fun wakePhrases(raw: String): List<String> =
      raw.split(',').map { normalizeWake(it).trim() }.filter { it.isNotEmpty() }

  /**
   * Speech recognition in Spanish rarely writes "Claw": it hears "clo", "clau", "claus"... and
   * "hey" comes back as "ey" or "hei". Folding those before matching makes "hey Claw" and
   * "oye Claw" work without an offline hotword model. Only the wake word itself is folded.
   */
  private fun normalizeWake(text: String): String =
      text.lowercase()
          .replace(Regex("[,¡!¿?.]"), " ")
          .replace(Regex("\\s+"), " ")
          .replace(Regex("\\b(cl[oó]|cl[aá]u[sx]?|cl[aá]o|kl[aá]u[sx]?|klaw|cl[aá]w|clow)\\b"), "claw")
          .replace(Regex("\\b(ey|ei|hei|hej|eh|hay) claw\\b"), "hey claw")
          .replace(Regex("\\b(oie|olle|oiga|oi) claw\\b"), "oye claw")
          .trim()

  /** MetaClaw: one question to the bridge, the whole answer spoken when it arrives. */
  private suspend fun runClawTurn(question: String) {
    val startedAt = SystemClock.elapsedRealtime()
    update {
      it.copy(
          phase = Phase.THINKING,
          question = question,
          answer = "",
          partial = "",
          latencyMs = null,
          error = null,
      )
    }
    val jpeg = if (wantsPicture(question)) withContext(Dispatchers.Default) { acquireFrame() } else null
    if (jpeg != null) {
      val preview = withContext(Dispatchers.Default) { Frames.preview(jpeg) }
      update { it.copy(frame = preview, frameInfo = Frames.info) }
    }
    val settings = _state.value
    val token = SecureStore.get(app, CLAW_TOKEN)
    if (token == null) {
      fail("No bridge token")
      shutdown()
      return
    }
    val filler =
        scope.launch {
          delay(CLAW_FILLER_MS)
          tts?.speak(fillerPhrase())
          delay(CLAW_KEEPALIVE_FIRST_MS - CLAW_FILLER_MS)
          var n = 0
          while (true) {
            tts?.speak(keepAlivePhrase(n++))
            delay(CLAW_KEEPALIVE_EVERY_MS)
          }
        }
    try {
      val where = if (settings.shareLocation) PhoneLocation.current(app) else null
      val answer = Claw.ask(settings.clawUrl, token, question, speechLanguage(), jpeg, where)
      filler.cancel()
      awaitSpeechEnd()
      val latency = SystemClock.elapsedRealtime() - startedAt
      update { it.copy(phase = Phase.SPEAKING, answer = answer, latencyMs = latency) }
      val chunker = SentenceChunker()
      chunker.push(answer).forEach { s -> cleanForSpeech(s).takeIf { it.isNotEmpty() }?.let { tts?.speak(it) } }
      chunker.flush()?.let { s -> cleanForSpeech(s).takeIf { it.isNotEmpty() }?.let { tts?.speak(it) } }
      awaitSpeechEnd()
    } catch (e: CancellationException) {
      filler.cancel()
      throw e
    } catch (e: Exception) {
      filler.cancel()
      val unauthorized = e is ClawException && e.status == 401
      fail(if (unauthorized) "Bridge rejected the token" else "Bridge request failed: ${e.message}")
      tts?.speak(spokenError(false))
      awaitSpeechEnd()
    }
    listen()
  }

  /**
   * Frames cost upload time, so in Claw mode one is only sent when the question is about what
   * is in front of the user, or always when photo mode is on.
   */
  private fun wantsPicture(question: String): Boolean {
    if (_state.value.photoMode) return true
    return VISUAL_CUES.containsMatchIn(question)
  }

  private val VISUAL_CUES =
      Regex(
          "\\b(mira|miro|mirando|ves|veo|esto|aquí|aqui|delante|lee|leer|léeme|leeme|traduce|" +
              "cartel|foto|look|see|this|read|translate|in front)\\b",
          RegexOption.IGNORE_CASE,
      )

  private fun fillerPhrase(): String =
      if (speechLanguage().startsWith("es", ignoreCase = true)) "Un momento." else "One moment."

  private fun keepAlivePhrase(n: Int): String {
    val phrases =
        if (speechLanguage().startsWith("es", ignoreCase = true))
            listOf("Sigo buscando.", "Ya casi.", "Sigo con ello.")
        else listOf("Still working on it.", "Almost there.", "Still on it.")
    return phrases[n % phrases.size]
  }

  /** Spoken in the recognition language; the text-to-speech voice cannot read another one. */
  private fun spokenError(limit: Boolean): String {
    val russian = speechLanguage().startsWith("ru", ignoreCase = true)
    val spanish = speechLanguage().startsWith("es", ignoreCase = true)
    return when {
      limit && spanish -> "Límite alcanzado."
      spanish -> "Ha fallado la consulta."
      limit && russian -> "Лимит подписки исчерпан."
      limit -> "Plan limit reached."
      russian -> "Ошибка запроса."
      else -> "Request failed."
    }
  }

  /** Listening must not start while the answer is still playing, or it would hear itself. */
  private suspend fun awaitSpeechEnd() {
    val speech = tts ?: return
    delay(150)
    while (speech.isSpeaking) delay(100)
    delay(250)
  }

  /** Runs off the main thread. Video frames are instant; a photo is sharper but takes longer. */
  private suspend fun acquireFrame(): ByteArray? {
    val photoFirst = _state.value.photoMode
    if (photoFirst) capturePhotoJpeg()?.let { return it }
    Frames.latestJpeg()?.let { return it }
    return if (photoFirst) null else capturePhotoJpeg()
  }

  private suspend fun capturePhotoJpeg(): ByteArray? {
    val video = stream ?: return null
    if (!streaming) return null
    var result: ByteArray? = null
    withTimeoutOrNull(PHOTO_TIMEOUT_MS) {
      video
          .capturePhoto()
          .onSuccess { photo ->
            result =
                when (photo) {
                  is PhotoData.Bitmap -> Frames.bitmapToJpeg(photo.bitmap)
                  is PhotoData.HEIC -> Frames.heicToJpeg(photo.data)
                }
          }
          .onFailure { error, _ -> log("Photo from the glasses: ${error.description}") }
    }
    return result
  }

  private fun instructions(hasImage: Boolean): String {
    val picture =
        if (hasImage) {
          "A frame from the glasses camera is attached to the question: it is what the user " +
              "sees right now. Use it when the question is about what is in front of them, and " +
              "do not describe it unasked. If what they ask about is not visible or not " +
              "legible in the frame, say so."
        } else {
          "There is no camera frame right now. If the question is about what is in front of " +
              "the user, say that you cannot see it."
        }
    return "You are a voice assistant in smart glasses. The user talks to you out loud and your " +
        "answer is read aloud. Answer in the language of the question, briefly and to the " +
        "point: one to three sentences unless asked for more. No lists, markup, emoji or links. " +
        picture
  }

  private fun cleanForSpeech(text: String): String =
      text.replace(Regex("[*#`_>|]"), "").replace(Regex("\\s+"), " ").trim()

  // ---- conversation: live voice ----

  private fun startLive() {
    scope.launch {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        fail("No Gemini API key")
        shutdown()
        return@launch
      }
      val wantGlasses = _state.value.glassesMic
      val onGlasses = AudioRouter.enableCall(app, wantGlasses)
      if (wantGlasses && !onGlasses) {
        log("Glasses audio unavailable over Bluetooth, using the phone")
      }
      if (voiceActive) connectLive(key)
    }
  }

  private fun connectLive(key: String) {
    live?.stop()
    val events = LiveEvents()
    val session =
        GeminiLive(
            apiKey = key,
            instructions = liveInstructions(),
            resumeHandle = if (liveAttempt == 0) liveHandle else null,
            fullSetup = liveAttempt < 2,
            bargeIn = _state.value.bargeIn,
            inputDevice = AudioRouter.inputDevice(app),
            callAudio = AudioRouter.isRouted,
            frameSource = { Frames.latestJpeg(LIVE_JPEG_QUALITY) },
            events = events,
        )
    events.owner = session
    live = session
    update { it.copy(phase = Phase.CONNECTING, partial = "") }
    session.start()
  }

  /** Session callbacks come from background threads; each is replayed on the main thread. */
  private class LiveEvents : GeminiLive.Events {
    var owner: GeminiLive? = null

    /** Drops callbacks from a session that has since been replaced or stopped. */
    private fun onMain(block: () -> Unit) {
      scope.launch { if (live === owner && voiceActive) block() }
    }

    override fun onReady() = onMain {
      liveDrops = 0
      liveTurnDone = true
      log(if (liveAttempt < 2) "Gemini Live connected" else "Gemini Live connected, basic setup")
      update { it.copy(phase = Phase.LISTENING, error = null) }
    }

    override fun onUserText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = delta, answer = "") }
      } else {
        update { it.copy(question = it.question + delta) }
      }
    }

    override fun onModelText(delta: String) = onMain {
      if (liveTurnDone) {
        liveTurnDone = false
        update { it.copy(question = "", answer = delta) }
      } else {
        update { it.copy(answer = it.answer + delta) }
      }
    }

    override fun onModelSpeaking(speaking: Boolean) = onMain {
      update { it.copy(phase = if (speaking) Phase.SPEAKING else Phase.LISTENING) }
    }

    override fun onTurnComplete() = onMain { liveTurnDone = true }

    override fun onResumeHandle(handle: String) = onMain { liveHandle = handle }

    override fun onGoAway() = onMain {
      // The server ends every connection after about ten minutes and says so in advance.
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key != null) {
        log("Gemini is moving the conversation to a new connection")
        liveAttempt = 0
        connectLive(key)
      }
    }

    override fun onFrameSent(jpeg: ByteArray) {
      val preview = Frames.preview(jpeg)
      onMain { update { it.copy(frame = preview, frameInfo = Frames.info) } }
    }

    override fun onClosed(reason: String, wasReady: Boolean) = onMain {
      val key = SecureStore.get(app, GEMINI_KEY)
      if (key == null) {
        shutdown()
        return@onMain
      }
      val badKey =
          reason.contains("API key", ignoreCase = true) ||
              reason.contains("HTTP 401") ||
              reason.contains("HTTP 403")
      val outOfQuota =
          reason.contains("quota", ignoreCase = true) ||
              reason.contains("RESOURCE_EXHAUSTED") ||
              reason.contains("HTTP 429")
      when {
        badKey -> {
          fail("Gemini rejected the API key: $reason")
          shutdown()
        }
        outOfQuota -> {
          fail("Gemini quota reached: $reason")
          shutdown()
        }
        // Refused before the session opened: retry with fewer options each time.
        !wasReady -> {
          liveAttempt += 1
          if (liveAttempt > 2) {
            fail("Gemini refused the connection: $reason")
            shutdown()
          } else {
            log("Gemini closed the connection ($reason), retrying with fewer options")
            connectLive(key)
          }
        }
        else -> {
          liveDrops += 1
          if (liveDrops > 5) {
            fail("The connection to Gemini keeps dropping: $reason")
            shutdown()
          } else {
            log("Connection to Gemini lost ($reason), reconnecting")
            liveAttempt = 0
            val dropped = owner
            scope.launch {
              delay(400L * liveDrops)
              if (voiceActive && live === dropped) connectLive(key)
            }
          }
        }
      }
    }

    override fun onLog(message: String) {
      log(message)
    }
  }

  private fun liveInstructions(): String =
      "You are a voice assistant in smart glasses. You hear the user and see video from the " +
          "camera of their glasses: it is what is in front of them right now. Talk naturally " +
          "and briefly, like a person in conversation: one to three sentences unless asked " +
          "for more. Always answer in the language the user speaks to you. Use the video when " +
          "asked about what is visible, and do not describe it unasked. If what they ask " +
          "about is not visible or not legible, say so. Respond only to speech addressed to you."

  // ---- plumbing ----

  private fun prefs(): SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  private fun update(block: (UiState) -> UiState) {
    _state.update(block)
  }

  private fun fail(message: String) {
    log(message)
    update { it.copy(error = message) }
  }

  /** Safe from any thread. The log is on screen because there is no other way to see inside. */
  fun log(message: String) {
    Log.i(TAG, message)
    val snapshot =
        synchronized(logLines) {
          logLines.addLast("${clock.format(Date())}  $message")
          while (logLines.size > MAX_LOG) logLines.removeFirst()
          logLines.toList()
        }
    update { it.copy(log = snapshot) }
  }
}
