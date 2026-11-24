package com.keonyrus.tupovision

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

/**
 * Offline "hey Claw" / "oye Claw" with Vosk and its small Spanish model in assets/model-es
 * (fetched by scripts/get-wake-model.sh). Audio never leaves the phone while it waits: only after
 * the wake phrase does the phone's speech recognition take the question.
 *
 * Structure adapted from CJ's Hotword.kt by Ghanshyam Paunikar (MIT); see NOTICE.md.
 */
class Hotword(private val context: Context) {
  private var model: Model? = null

  /** True when the APK was built with the wake-word model. */
  fun available(): Boolean = runCatching { context.assets.list(ASSET)?.isNotEmpty() == true }.getOrDefault(false)

  private suspend fun load(): Model =
      model
          ?: suspendCancellableCoroutine { cont ->
            StorageService.unpack(
                context,
                ASSET,
                "model",
                { m ->
                  model = m
                  cont.resume(m)
                },
                { e -> cont.resumeWithException(e) },
            )
          }

  /**
   * Listens on [device] (the glasses, or the phone mic when null) until the wake phrase is heard,
   * then frees the microphone and returns what was heard. Cancel the coroutine to stop listening.
   */
  @SuppressLint("MissingPermission") // RECORD_AUDIO is granted before the conversation starts
  suspend fun await(device: AudioDeviceInfo?): String {
    val model = load()
    return withContext(Dispatchers.IO) {
      val minBuffer =
          AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
      val record =
          AudioRecord(
              MediaRecorder.AudioSource.VOICE_RECOGNITION,
              RATE,
              AudioFormat.CHANNEL_IN_MONO,
              AudioFormat.ENCODING_PCM_16BIT,
              maxOf(minBuffer, FRAME * 4),
          )
      if (record.state != AudioRecord.STATE_INITIALIZED) {
        record.release()
        throw IOException("The microphone is busy or not allowed")
      }
      device?.let { record.preferredDevice = it }
      try {
        Recognizer(model, RATE.toFloat(), WakeRule.GRAMMAR).use { recognizer ->
          recognizer.setWords(true)
          record.startRecording()
          val frame = ShortArray(FRAME)
          var last = ""
          while (isActive) {
            val n = record.read(frame, 0, FRAME)
            if (n <= 0) continue
            val settled = recognizer.acceptWaveForm(frame, n)
            val words =
                if (settled) settledWords(recognizer.result)
                else WakeRule.fromPartial(JSONObject(recognizer.partialResult).optString("partial"))
            val heard = words.joinToString(" ") { it.word }
            // Words only, never audio, so a missed wake phrase can be diagnosed from logcat.
            if (heard.isNotBlank() && heard != last) Log.d(TAG, "heard: $heard")
            last = heard
            if (WakeRule.called(words, settled)) return@withContext heard
          }
          ""
        }
      } finally {
        runCatching { record.stop() }
        record.release()
      }
    }
  }

  companion object {
    private const val TAG = "Hotword"
    private const val ASSET = "model-es"
    const val RATE = 16_000
    // 128 ms of audio per read.
    private const val FRAME = 2048

    private fun settledWords(json: String): List<WakeRule.Heard> {
      val result = JSONObject(json).optJSONArray("result") ?: return emptyList()
      return (0 until result.length())
          .map { result.getJSONObject(it) }
          .map { WakeRule.Heard(it.optString("word"), it.optDouble("conf", 0.0)) }
    }
  }
}
