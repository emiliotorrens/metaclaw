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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

// MetaClaw palette: deep ink background, warm "claw" orange, mint for done.
private val Ink = Color(0xFF0B0D12)
private val Panel = Color(0xFF151922)
private val PanelHigh = Color(0xFF1D2230)
private val ClawOrange = Color(0xFFFF7A45)
private val Mint = Color(0xFF3DDC97)
private val Sky = Color(0xFF6AA8FF)
private val Muted = Color(0xFF8A93A6)
private val Accent = Mint
private val Warning = Color(0xFFFFB454)

internal fun previewColors() = MetaClawColors

private val MetaClawColors =
    darkColorScheme(
        primary = ClawOrange,
        onPrimary = Color(0xFF1A0E08),
        secondary = Mint,
        background = Ink,
        surface = Panel,
        surfaceVariant = PanelHigh,
        surfaceContainer = Panel,
        surfaceContainerHigh = PanelHigh,
        onSurfaceVariant = Muted,
        outline = Color(0xFF2E3546),
    )

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
      MaterialTheme(colorScheme = MetaClawColors) {
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
    // Optional: only used for "near me" questions to your own agent.
    if (Engine.state.value.clawMode && Engine.state.value.shareLocation) {
      wanted.add(Manifest.permission.ACCESS_COARSE_LOCATION)
      wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
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
internal fun Screen(
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
              .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Header(state)

    val error = state.error
    if (error != null) {
      Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = Warning.copy(alpha = 0.12f)),
      ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
          Text(error, color = Warning, modifier = Modifier.weight(1f))
          TextButton(onClick = { Engine.clearError() }) { Text("OK") }
        }
      }
    }

    ConversationSection(state, onStart)
    val setupDone = state.permissionsGranted && state.metaInstalled && state.registered &&
        (!state.clawMode || (state.clawUrl.isNotEmpty() && state.clawTokenSet))
    GlassesSection(state, setupDone, onGrantPermissions, onInstallMetaAi, onRegister, onUnregister)
    ClawSection(state, setupDone)
    if (!state.clawMode) {
      AssistantSection(state)
      if (!state.liveMode) ChatGptSection(state, onSignIn)
    }
    SettingsSection(state, onNotificationAccess)
    LogSection(state, onCopyLog)
    Text(
        "MetaClaw · based on Tupo Vision (MIT). Not a Meta product.",
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
  }
}

@Composable
private fun Header(state: UiState) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(
            Brush.linearGradient(listOf(ClawOrange, Color(0xFFFFB36B)))),
        contentAlignment = Alignment.Center,
    ) {
      Text("🐾", fontSize = 20.sp)
    }
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
      Text("MetaClaw", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Text(
          if (state.clawMode) "Your agent, in your glasses" else "Tupo Vision assistants",
          style = MaterialTheme.typography.bodySmall,
          color = Muted,
      )
    }
    Pill(
        text = if (state.hasActiveDevice) "Glasses on" else if (state.registered) "Linked" else "Not linked",
        color = if (state.hasActiveDevice) Mint else if (state.registered) Sky else Muted,
    )
  }
}

@Composable
private fun Pill(text: String, color: Color) {
  Row(
      modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f))
          .padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(7.dp).clip(CircleShape).background(color))
    Spacer(Modifier.width(6.dp))
    Text(text, color = color, style = MaterialTheme.typography.labelMedium)
  }
}

private fun phaseColor(phase: Phase): Color =
    when (phase) {
      Phase.IDLE -> Muted
      Phase.CONNECTING -> Sky
      Phase.LISTENING -> Mint
      Phase.THINKING -> ClawOrange
      Phase.SPEAKING -> Color(0xFFB98CFF)
      Phase.PAUSED -> Warning
    }

/** A soft glowing circle that breathes while the conversation is live. */
@Composable
private fun StatusOrb(phase: Phase, running: Boolean) {
  val color by animateColorAsState(phaseColor(phase), label = "orb-color")
  val pulse = rememberInfiniteTransition(label = "orb")
  val speed = if (phase == Phase.THINKING) 700 else 1600
  val scale by pulse.animateFloat(
      initialValue = 0.86f,
      targetValue = if (running && phase != Phase.PAUSED) 1.08f else 0.86f,
      animationSpec = infiniteRepeatable(tween(speed, easing = FastOutSlowInEasing), RepeatMode.Reverse),
      label = "orb-scale",
  )
  Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
    Box(
        Modifier.size(132.dp).scale(scale).clip(CircleShape)
            .background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent))))
    Box(
        Modifier.size(76.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(color, color.copy(alpha = 0.55f)))))
  }
}

@Composable
private fun Bubble(who: String, text: String, mine: Boolean) {
  Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
    Column(
        modifier = Modifier.fillMaxWidth(0.86f)
            .clip(RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
            .background(if (mine) ClawOrange.copy(alpha = 0.16f) else PanelHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
      Text(who, style = MaterialTheme.typography.labelSmall, color = if (mine) ClawOrange else Mint)
      Text(text, style = MaterialTheme.typography.bodyMedium)
    }
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
  Card(
      modifier = Modifier.fillMaxWidth().animateContentSize(),
      shape = RoundedCornerShape(28.dp),
      colors = CardDefaults.cardColors(containerColor = Panel),
  ) {
    Column(
        Modifier.padding(20.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      StatusOrb(state.phase, state.running)
      Text(
          if (state.running) state.phase.label else if (ready) "Ready" else "Not set up",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.SemiBold,
          color = phaseColor(state.phase),
      )
      Text(
          when {
            !ready && !state.running ->
                "Finish the setup below: permissions, linking the glasses and " +
                    when {
                      state.clawMode -> "the bridge."
                      state.liveMode -> "a Gemini API key."
                      else -> "ChatGPT sign-in."
                    }
            !state.running -> if (state.clawMode && state.offlineWake) "Start, then say “Oye Claw”." else "Ready when you are."
            state.phase == Phase.LISTENING && state.clawMode && state.offlineWake ->
                "Say “Oye Claw”, wait for the beep, then ask."
            state.resting -> "Resting, so “Hey Meta” works. Tap Listen, or Listen in the notification."
            state.phase == Phase.PAUSED -> "Tap the temple to continue."
            else -> "One tap on the temple pauses, another resumes."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = Muted,
          textAlign = TextAlign.Center,
      )
      if (state.running && state.resting) {
        Button(
            onClick = { Engine.resumeListening() },
            shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
          Text("Listen", style = MaterialTheme.typography.titleMedium)
        }
      }
      Button(
          onClick = { if (state.running) Engine.stop() else onStart() },
          enabled = state.running || ready,
          shape = RoundedCornerShape(50),
          colors =
              if (state.running)
                  ButtonDefaults.buttonColors(containerColor = PanelHigh, contentColor = Color.White)
              else ButtonDefaults.buttonColors(),
          modifier = Modifier.fillMaxWidth().height(56.dp),
      ) {
        Text(
            if (state.running) "Stop" else "Start conversation",
            style = MaterialTheme.typography.titleMedium,
        )
      }
      val latency = state.latencyMs
      if (latency != null) {
        Text(
            "First sound after %.1f s".format(latency / 1000.0),
            style = MaterialTheme.typography.labelMedium,
            color = Muted,
        )
      }
    }
    if (state.partial.isNotEmpty() || state.question.isNotEmpty() || state.answer.isNotEmpty()) {
      Column(
          Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (state.question.isNotEmpty()) Bubble("You", state.question, mine = true)
        if (state.partial.isNotEmpty()) Bubble("Hearing…", state.partial, mine = true)
        if (state.answer.isNotEmpty()) {
          Bubble(
              when {
                state.clawMode -> "Claw"
                state.liveMode -> "Gemini"
                else -> "GPT"
              },
              state.answer,
              mine = false,
          )
        }
      }
    }
    val frame = state.frame
    if (frame != null) {
      Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        Text(
            if (state.liveMode && !state.clawMode) "What the model sees now" else "Sent with the question",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        Spacer(Modifier.height(6.dp))
        Image(
            bitmap = frame.asImageBitmap(),
            contentDescription = "Frame from the glasses camera",
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(18.dp)),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (state.running) {
      Text(
          "session ${state.sessionState} · stream ${state.streamState} · ${state.frameInfo}",
          style = MaterialTheme.typography.labelSmall,
          color = Muted.copy(alpha = 0.7f),
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
      )
    }
  }
}

@Composable
private fun GlassesSection(
    state: UiState,
    setupDone: Boolean,
    onGrantPermissions: () -> Unit,
    onInstallMetaAi: () -> Unit,
    onRegister: () -> Unit,
    onUnregister: () -> Unit,
) {
  Section("Glasses", summary = if (state.registered) "Linked" else "Set up", done = state.registered && state.permissionsGranted, startOpen = !setupDone) {
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
        color = Muted,
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
private fun ClawSection(state: UiState, setupDone: Boolean) {
  Section("Your agent", summary = if (state.clawMode) "MetaClaw bridge" else "Off", done = !state.clawMode || (state.clawUrl.isNotEmpty() && state.clawTokenSet), startOpen = !setupDone) {
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
      SettingSwitch(
          title = "Send my location",
          subtitle =
              "Each question to your agent carries where the phone is, so \"what is near me\" " +
                  "works. It only goes to your own bridge.",
          checked = state.shareLocation,
          enabled = !state.running,
          onChange = { Engine.setShareLocation(it) },
      )
      var url by remember(state.clawUrl) { mutableStateOf(state.clawUrl) }
      OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text("Bridge address (https), e.g. https://my-pc.tailnet.ts.net:8443") },
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
          color = Muted,
      )
    }
  }
}

@Composable
private fun AssistantSection(state: UiState) {
  Section("Assistant", summary = if (state.liveMode) "Gemini Live" else "ChatGPT") {
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
            color = Muted,
        )
      }
    }
  }
}

@Composable
private fun ChatGptSection(state: UiState, onSignIn: () -> Unit) {
  Section("ChatGPT", summary = if (state.signedIn) state.model else "Sign in", done = state.signedIn) {
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
          color = Muted,
      )
      TextButton(onClick = { Engine.signOut() }) { Text("Sign out") }
    } else {
      Button(onClick = onSignIn) { Text("Continue with ChatGPT") }
      if (state.loginInProgress) {
        Text(
            "Waiting for the sign-in in the browser. If the tab was closed, tap the button again.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
      }
      Text(
          "Needs a Go, Plus or Pro plan. On the sign-in screen, allow the app to use your plan.",
          style = MaterialTheme.typography.bodySmall,
          color = Muted,
      )
    }
  }
}

@Composable
private fun SettingsSection(state: UiState, onNotificationAccess: () -> Unit) {
  Section("Settings", summary = "Voice, wake phrase, standby") {
    Text(
        "Change these while the conversation is stopped; they apply from the next start.",
        style = MaterialTheme.typography.bodySmall,
        color = Muted,
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
          color = Muted,
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
        subtitle =
            if (state.clawMode)
                "Takes a real photo instead of grabbing a video frame, and sends it with every " +
                    "question, not only “what am I looking at”. Sharper for small text, ~1-2 s slower."
            else "Sharper for small text, but slower.",
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
    if (state.clawMode) {
      SettingSwitch(
          title = "Offline wake phrase",
          subtitle =
              if (state.offlineWakeAvailable)
                  "Waits for “hey Claw” / “oye Claw” on the phone (Vosk, Spanish), no audio leaves it. " +
                      "After the beep, ask your question."
              else "This build has no wake-word model (scripts/get-wake-model.sh).",
          checked = state.offlineWake && state.offlineWakeAvailable,
          enabled = !state.running && state.offlineWakeAvailable,
          onChange = { Engine.setOfflineWake(it) },
      )
      SettingSwitch(
          title = "Male voice",
          subtitle = "Uses a male voice of the phone's text-to-speech when it has one. The choice shows in the log.",
          checked = state.maleVoice,
          enabled = !state.running,
          onChange = { Engine.setMaleVoice(it) },
      )
    }
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
  Section("Log", summary = state.log.lastOrNull()?.substringAfter("  ")?.take(40) ?: "Empty") {
    TextButton(onClick = onCopyLog) { Text("Copy log") }
    Text(
        state.log.takeLast(60).joinToString("\n"),
        color = Muted,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
  }
}

@Composable
private fun Section(
    title: String,
    summary: String = "",
    done: Boolean? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
  var open by rememberSaveable(title) { mutableStateOf(startOpen) }
  val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
  Card(
      modifier = Modifier.fillMaxWidth().animateContentSize(),
      shape = RoundedCornerShape(22.dp),
      colors = CardDefaults.cardColors(containerColor = Panel),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { open = !open }
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (done != null) {
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(if (done) Mint.copy(alpha = 0.18f) else PanelHigh),
            contentAlignment = Alignment.Center,
        ) {
          Text(if (done) "✓" else "•", color = if (done) Mint else Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(12.dp))
      }
      Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
      if (summary.isNotEmpty() && !open) {
        Text(summary, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 180.dp))
      }
      Text("⌄", color = Muted, fontSize = 18.sp, modifier = Modifier.rotate(turn))
    }
    if (open) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
      Column(
          Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        content()
      }
    }
  }
}

@Composable
private fun StatusLine(ok: Boolean, text: String) {
  Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Mint else Muted.copy(alpha = 0.6f)))
    Spacer(Modifier.width(10.dp))
    Text(text, color = if (ok) Color.White else Muted, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
      Text(title, style = MaterialTheme.typography.bodyLarge)
      Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(checkedTrackColor = ClawOrange, checkedThumbColor = Color.White),
    )
  }
}
