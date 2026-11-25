// SPDX-License-Identifier: MIT
// Part of MetaClaw. New file, not in upstream Tupo Vision.
package com.keonyrus.tupovision

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the phone is, sent with each question so the agent can answer "near me" questions. */
data class Where(val lat: Double, val lon: Double, val accuracyM: Int, val ageS: Int)

object PhoneLocation {
  // A fix this fresh is good enough to skip asking the GPS again.
  private const val FRESH_MS = 60_000L
  // Do not hold the question back for long; fall back to the last known fix.
  private const val WAIT_MS = 2_000L
  // Older than this says nothing useful about where you are now.
  private const val STALE_MS = 30 * 60_000L

  fun granted(context: Context): Boolean =
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
          PackageManager.PERMISSION_GRANTED ||
          context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
              PackageManager.PERMISSION_GRANTED

  /** Best fix available within about two seconds, or null without permission or signal. */
  @Suppress("MissingPermission")
  suspend fun current(context: Context): Where? {
    if (!granted(context)) return null
    val lm = context.getSystemService(LocationManager::class.java) ?: return null
    val providers = lm.getProviders(true)
    if (providers.isEmpty()) return null
    val last =
        providers
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    if (last != null && ageMs(last) < FRESH_MS) return last.toWhere()
    val provider =
        when {
          LocationManager.FUSED_PROVIDER in providers -> LocationManager.FUSED_PROVIDER
          LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER
          else -> providers.first()
        }
    val fresh =
        withTimeoutOrNull(WAIT_MS) {
          suspendCancellableCoroutine<Location?> { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching {
                  lm.getCurrentLocation(provider, cancel, context.mainExecutor) { cont.resume(it) }
                }
                .onFailure { if (cont.isActive) cont.resume(null) }
          }
        }
    val best = fresh ?: last ?: return null
    return if (ageMs(best) < STALE_MS) best.toWhere() else null
  }

  private fun ageMs(l: Location): Long =
      (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

  private fun Location.toWhere() =
      Where(latitude, longitude, if (hasAccuracy()) accuracy.toInt() else -1, (ageMs(this) / 1000).toInt())
}
