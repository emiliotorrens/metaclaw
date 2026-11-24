package com.keonyrus.tupovision

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Accent = Color(0xFF19C37D)
private val Warning = Color(0xFFFFB454)

class MainActivity : ComponentActivity() {

  private val androidPermissions =
      registerForActivityResult(RequestMultiplePermissions()) { Engine.refreshEnvironment() }

  // Camera access on the glasses is granted in the Meta AI app, which this launcher opens.
  private val permissionMutex = Mutex()
  private var permissionContinuation: CancellableContinuation<PermissionStatus>? = null
  private val wearablesPermission =
      registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        val status = result.getOrDefault(PermissionStatus.Denied)
        permissionContinuation?.resume(status)
        permissionContinuation = null
      }

  private suspend fun requestWearablesPermission(permission: Permission): PermissionStatus =
      permissionMutex.withLock {
        suspendCancellableCoroutine { continuation ->
          permissionContinuation = continuation
          continuation.invokeOnCancellation { permissionContinuation = null }
          wearablesPermission.launch(permission)
        }
      }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val state by Engine.state.collectAsStateWithLifecycle()
      MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.Black)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Screen(
              state = state,
              onGrantPermissions = ::requestAndroidPermissions,
              onInstallMetaAi = ::openMetaAiInStore,
              onRegister = { Engine.register(this) },
              onUnregister = { Engine.unregister(this) },
              onSignIn = { Engine.signIn(this) },
              onStart = { Engine.start(::requestWearablesPermission) },
              onCopyLog = ::copyLog,
              onNotificationAccess = ::openNotificationAccess,
          )
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    Engine.refreshEnvironment()
  }

  private fun requestAndroidPermissions() {
    val wanted = ArrayList<String>()
    wanted.addAll(Engine.REQUIRED_PERMISSIONS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      wanted.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    androidPermissions.launch(wanted.toTypedArray())
  }

  private fun openMetaAiInStore() {
    val page = "details?id=com.facebook.stella"
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://$page")))
    } catch (e: Exception) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/$page")))
    }
  }

  private fun openNotificationAccess() {
    runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
  }

  private fun copyLog() {
    val clipboard = getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("MetaClaw log", Engine.logText()))
  }
}

@Composable
private fun Screen(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
    onSignIn: () -> Unit,
    onStart: () -> Unit,
    onCopyLog: () -> Unit,
    onNotificationAccess: () -> Unit,
) {
  Column(
      modifier =
          Modifier.fillMaxSize()
              .safeDrawingPadding()
              .verticalScroll(rememberScrollState())
              .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("MetaClaw", style = MaterialTheme.typography.headlineSmall)

    val error = state.error
    if (error != null) {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(error, color = Warning)
          TextButton(onClick = { Engine.clearError() }) { Text("Dismiss") }
        }
      }
    }

    ConversationSection(state, onStart)
    GlassesSection(state, onGrantPermissions, onInstallMetaAi, onRegister, onUnregister)
    ClawSection(state)
    if (!state.clawMode) {
      AssistantSection(state)
      if (!state.liveMode) ChatGptSection(state, onSignIn)
    }
    SettingsSection(state, onNotificationAccess)
    LogSection(state, onCopyLog)
  }
}

@Composable
private fun ConversationSection(state: UiState, onStart: () -> Unit) {
  val assistantReady =
      when {
        state.clawMode -> state.clawUrl.isNotEmpty() && state.clawTokenSet
        state.liveMode -> state.geminiKeySet
        else -> state.signedIn
      }
  val ready = assistantReady && state.registered && state.permissionsGranted
  Section("Conversation") {
    Button(
        onClick = { if (state.running) Engine.stop() else onStart() },
        enabled = state.running || ready,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
      Text(if (state.running) "Stop" else "Start conversation")
    }
    if (!ready && !state.running) {
      Text(
          "Finish the steps below first: permissions, linking the glasses, and " +
              when {
                state.clawMode -> "the bridge address and token."
                state.liveMode -> "a Gemini API key."
                else -> "ChatGPT sign-in."
              },
          style = MaterialTheme.typography.bodySmall,
      )
    }
    Text(state.phase.label, style = MaterialTheme.typography.titleMedium, color = Accent)
    if (state.partial.isNotEmpty()) Text("… ${state.partial}")
    if (state.question.isNotEmpty()) Text("You: ${state.question}")
    if (state.answer.isNotEmpty()) {
      Text(
          (when {
            state.clawMode -> "Claw: "
            state.liveMode -> "Gemini: "
            else -> "GPT: "
          }) + state.answer)
    }
    val latency = state.latencyMs
    if (latency != null) {
      Text(
          "Time to first sound: %.1f s".format(latency / 1000.0),
          style = MaterialTheme.typography.bodySmall,
      )
    }
    val frame = state.frame
    if (frame != null) {
      Text(
          if (state.liveMode && !state.clawMode) "What the model sees now:"
          else "Frame sent with the question:",
          style = MaterialTheme.typography.bodySmall,
      )
      Image(
          bitmap = frame.asImageBitmap(),
          contentDescription = "Frame from the glasses camera",
          modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
          contentScale = ContentScale.Fit,
      )
    }
    if (state.running) {
      Text(
          "Session: ${state.sessionState} · stream: ${state.streamState} · " +
              "frame: ${state.frameInfo}",
          style = MaterialTheme.typography.bodySmall,
      )
      Text(
          "One tap on the temple of the glasses pauses, a second tap resumes.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun GlassesSection(
    state: UiState,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
) {
  Section("1. Glasses") {
    StatusLine(state.permissionsGranted, "Permissions: Bluetooth and microphone")
    if (!state.permissionsGranted) {
      OutlinedButton(onClick = onGrantPermissions) { Text("Grant permissions") }
    }
    StatusLine(state.metaInstalled, "Meta AI app installed")
    if (!state.metaInstalled) {
      OutlinedButton(onClick = onInstallMetaAi) { Text("Open Meta AI in Google Play") }
    }
    Text(
        "Turn on Developer Mode in Meta AI first, with the glasses on and connected: " +
            "Settings → App info, tap App version five times, switch Developer Mode on, " +
            "Enable. On the same screen tap Install next to Device Access Toolkit if it is " +
            "not installed. If Meta AI says “Error while opening link”, Developer Mode is " +
            "off: switch it off and on again, restart Meta AI and link once more.",
        style = MaterialTheme.typography.bodySmall,
    )
    StatusLine(state.registered, "App linked to the glasses (${state.registration})")
    if (state.registered) {
      StatusLine(state.hasActiveDevice, "Glasses connected (devices: ${state.deviceCount})")
      TextButton(onClick = onUnregister) { Text("Unlink") }
    } else {
      OutlinedButton(onClick = onRegister, enabled = state.permissionsGranted) {
        Text("Link glasses through Meta AI")
      }
    }
  }
}

/** MetaClaw: where your own agent lives. New section, not in upstream Tupo Vision. */
@Composable
private fun ClawSection(state: UiState) {
  Section("2. Your agent (MetaClaw bridge)") {
    SettingSwitch(
        title = "Answer with my agent",
        subtitle =
            "Questions go to the bridge running on your own machine, and from there to your " +
                "agent. Off: the original Tupo Vision assistants (ChatGPT or Gemini Live).",
        checked = state.clawMode,
        enabled = !state.running,
        onChange = { Engine.setClawMode(it) },
    )
    if (state.clawMode) {
      var url by remember(state.clawUrl) { mutableStateOf(state.clawUrl) }
      OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Bridge address, e.g. http://100.x.y.z:8791") },
          singleLine = true,
          enabled = !state.running,
          modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { Engine.setClawUrl(url) },
            enabled = url.isNotBlank() && url != state.clawUrl,
        ) {
          Text("Save address")
        }
        OutlinedButton(
            onClick = { Engine.testClaw() },
            enabled = state.clawUrl.isNotEmpty() && state.clawTokenSet,
        ) {
          Text("Test")
        }
      }
      StatusLine(state.clawTokenSet, "Bridge token saved on this phone")
      if (state.clawTokenSet) {
        TextButton(onClick = { Engine.clearClawToken() }) { Text("Delete token") }
      } else {
        var draft by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Bridge token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
              Engine.setClawToken(draft)
              draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
          Text("Save token")
        }
      }
      Text(
          "Keep the bridge on a private network such as Tailscale. The token is stored " +
              "encrypted on this phone and only sent to the bridge.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun AssistantSection(state: UiState) {
  Section("2. Assistant") {
    SettingSwitch(
        title = "Live voice (Gemini Live)",
        subtitle =
            "The model hears and speaks by itself, you can interrupt it, and it sees the " +
                "camera all the time. Needs a Gemini API key. Off: GPT on your ChatGPT plan.",
        checked = state.liveMode,
        enabled = !state.running,
        onChange = { Engine.setLiveMode(it) },
    )
    if (state.liveMode) {
      StatusLine(state.geminiKeySet, "Gemini API key saved on this phone")
      if (state.geminiKeySet) {
        TextButton(onClick = { Engine.clearGeminiKey() }) { Text("Delete key") }
      } else {
        var draft by remember { mutableStateOf("") }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("Key from aistudio.google.com") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            // A password field keeps the key out of keyboard suggestions and learning.
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
              Engine.setGeminiKey(draft)
              draft = ""
            },
            enabled = draft.isNotBlank(),
        ) {
          Text("Save key")
        }
        Text(
            "The key is stored encrypted on this phone only and is sent only to Google.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
    }
  }
}

@Composable
private fun ChatGptSection(state: UiState, onSignIn: () -> Unit) {
  Section("ChatGPT") {
    StatusLine(state.signedIn, "ChatGPT plan connected")
    if (state.signedIn) {
      var expanded by remember { mutableStateOf(false) }
      Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Model: ${state.model}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
          state.models.forEach { id ->
            DropdownMenuItem(
                text = { Text(id) },
                onClick = {
                  Engine.setModel(id)
                  expanded = false
                },
            )
          }
        }
      }
      Text(
          "Requests count against your plan limits. Usage per app: chatgpt.com/settings/usage",
          style = MaterialTheme.typography.bodySmall,
      )
      TextButton(onClick = { Engine.signOut() }) { Text("Sign out") }
    } else {
      Button(onClick = onSignIn) { Text("Continue with ChatGPT") }
      if (state.loginInProgress) {
        Text(
            "Waiting for the sign-in in the browser. If the tab was closed, tap the button again.",
            style = MaterialTheme.typography.bodySmall,
        )
      }
      Text(
          "Needs a Go, Plus or Pro plan. On the sign-in screen, allow the app to use your plan.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
  }
}

@Composable
private fun SettingsSection(state: UiState, onNotificationAccess: () -> Unit) {
  Section("Settings") {
    Text(
        "Change these while the conversation is stopped; they apply from the next start.",
        style = MaterialTheme.typography.bodySmall,
    )
    SettingSwitch(
        title = "Standby (experimental)",
        subtitle =
            "Start and stop from the glasses. In Meta AI, assign Tap and hold to Music. " +
                "When the music app starts while the phone is locked or its screen is off, " +
                "this app pauses it and toggles the conversation. Music you start with the " +
                "phone in your hand, or that plays through a car or other headphones, is " +
                "left alone. Switch on with the glasses on." +
                if (state.standbyAudio.isNotEmpty()) "\nFollowing: ${state.standbyAudio}" else "",
        checked = state.standby,
        enabled = true,
        onChange = { Engine.setStandby(it) },
    )
    if (!state.mediaAccess) {
      OutlinedButton(onClick = onNotificationAccess) { Text("Grant notification access") }
      Text(
          "Android only lets an app see which music app is playing with this access. MetaClaw " +
              "reads no notifications. If the switch there is greyed out, open " +
              "Android Settings → Apps → MetaClaw → ⋮ → Allow restricted settings first.",
          style = MaterialTheme.typography.bodySmall,
      )
    }
    OutlinedTextField(
        value = state.triggerPackage,
        onValueChange = { Engine.setTriggerPackage(it.trim()) },
        label = { Text("Standby music app (package)") },
        supportingText = { Text("Spotify is com.spotify.music. Change with standby off.") },
        singleLine = true,
        enabled = !state.standby,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingSwitch(
        title = "Glasses microphone",
        subtitle = "Turn off if the glasses hear worse than the phone. The phone listens then.",
        checked = state.glassesMic,
        enabled = !state.running,
        onChange = { Engine.setGlassesMic(it) },
    )
    SettingSwitch(
        title = "Interrupt by voice",
        subtitle =
            "Live voice only. Turn off if the model cuts itself off: " +
                "it then stops listening while it speaks.",
        checked = state.bargeIn,
        enabled = !state.running,
        onChange = { Engine.setBargeIn(it) },
    )
    SettingSwitch(
        title = "Photo instead of video frame",
        subtitle = "ChatGPT mode only. Sharper for small text, but slower.",
        checked = state.photoMode,
        enabled = !state.running,
        onChange = { Engine.setPhotoMode(it) },
    )
    OutlinedTextField(
        value = state.language,
        onValueChange = { Engine.setLanguage(it.trim()) },
        label = { Text("Speech language") },
        supportingText = {
          Text("ChatGPT mode only: language tag for recognition and voice, e.g. en-US, ru-RU.")
        },
        singleLine = true,
        enabled = !state.running,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.wakeWord,
        onValueChange = { Engine.setWakeWord(it) },
        label = { Text("Wake phrases (comma separated, empty = always listen)") },
        supportingText = {
          Text("Only answer phrases that contain one of these, e.g. “hey claw, oye claw”.")
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun LogSection(state: UiState, onCopyLog: () -> Unit) {
  Section("Log") {
    TextButton(onClick = onCopyLog) { Text("Copy log") }
    Text(
        state.log.takeLast(60).joinToString("\n"),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
  }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      content()
    }
  }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
  Text(
      (if (ok) "✓ " else "○ ") + text,
      color = if (ok) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title)
      Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
  }
}
