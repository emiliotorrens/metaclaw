// SPDX-License-Identifier: MIT
// Part of ClawSpecs. New file, not in upstream Tupo Vision.
package com.keonyrus.tupovision

import android.util.Base64
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ClawException(val status: Int, message: String) : IOException(message)

/**
 * Client for the ClawSpecs bridge (see `bridge/` in this repository): a small HTTP service on
 * your own machine that hands the question, and optionally a camera frame, to your agent and
 * returns its answer as plain text for speech.
 *
 * Protocol: `POST {base}/ask` with `Authorization: Bearer <token>` and a JSON body
 * `{question, conversation_id, language, image_jpeg_b64?}`; the reply is `{answer}`.
 */
object Claw {
  private val JSON = "application/json; charset=utf-8".toMediaType()

  private val http =
      OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          // An agent that uses tools can take a while; the screen and a spoken filler cover it.
          .readTimeout(120, TimeUnit.SECONDS)
          .writeTimeout(30, TimeUnit.SECONDS)
          .build()

  /** One id per conversation, so the agent keeps the thread across questions. */
  @Volatile var conversationId: String = UUID.randomUUID().toString()
    private set

  fun newConversation() {
    conversationId = UUID.randomUUID().toString()
  }

  fun normalizeUrl(raw: String): String = raw.trim().trimEnd('/')

  suspend fun ask(
      baseUrl: String,
      token: String,
      question: String,
      language: String,
      jpeg: ByteArray?,
  ): String {
    val body =
        JSONObject()
            .put("question", question)
            .put("conversation_id", conversationId)
            .put("language", language)
    if (jpeg != null) {
      val encoded = withContext(Dispatchers.Default) { Base64.encodeToString(jpeg, Base64.NO_WRAP) }
      body.put("image_jpeg_b64", encoded)
    }
    val request =
        Request.Builder()
            .url(normalizeUrl(baseUrl) + "/ask")
            .header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody(JSON))
            .build()
    val text = execute(request)
    val answer = runCatching { JSONObject(text).optString("answer") }.getOrDefault("")
    if (answer.isBlank()) throw ClawException(200, "The bridge returned no answer")
    return answer.trim()
  }

  /** Returns null when the bridge answers, or a short reason when it does not. */
  suspend fun check(baseUrl: String, token: String): String? =
      try {
        val request =
            Request.Builder()
                .url(normalizeUrl(baseUrl) + "/health")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
        execute(request)
        null
      } catch (e: ClawException) {
        if (e.status == 401) "Wrong token" else "HTTP ${e.status}: ${e.message}"
      } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
      }

  private suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
    val call = http.newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
          }

          override fun onResponse(call: Call, response: Response) {
            response.use {
              val text = it.body?.string() ?: ""
              if (!cont.isActive) return
              if (it.isSuccessful) {
                cont.resume(text)
              } else {
                val reason =
                    runCatching { JSONObject(text).optString("error") }.getOrNull()
                        ?.takeIf { r -> r.isNotBlank() } ?: text.take(200)
                cont.resumeWithException(ClawException(it.code, reason))
              }
            }
          }
        },
    )
  }
}
