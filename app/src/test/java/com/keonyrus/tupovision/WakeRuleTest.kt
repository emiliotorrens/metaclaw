package com.keonyrus.tupovision

import com.keonyrus.tupovision.WakeRule.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decodings below are what the small Spanish model really returned in tools/wake_test.py. */
class WakeRuleTest {
  private fun settled(vararg pairs: Pair<String, Double>) = pairs.map { Heard(it.first, it.second) }

  @Test
  fun wakesOnHeyOrOyeBeforeTheName() {
    assertTrue(WakeRule.called(WakeRule.fromPartial("hey clo"), settled = false))
    assertTrue(WakeRule.called(WakeRule.fromPartial("oye clau que"), settled = false))
    // "Hey Claw" by an English voice: "hay clo".
    assertTrue(WakeRule.called(settled("hay" to 1.0, "clo" to 0.98), settled = true))
    // "Oye Claw": "hoy clau".
    assertTrue(WakeRule.called(settled("hoy" to 0.99, "clau" to 0.53, "que" to 0.9), settled = true))
  }

  @Test
  fun theHeyCanBeSwallowedIfTheNameIsClear() {
    assertTrue(WakeRule.called(settled("clo" to 0.66), settled = true))
    assertFalse(WakeRule.called(settled("clo" to 0.4), settled = true))
  }

  @Test
  fun partialNameAtTheStartIsNotEnough() {
    // "Clausura del congreso" starts as "clau" in partial results.
    assertFalse(WakeRule.called(WakeRule.fromPartial("clau"), settled = false))
  }

  @Test
  fun ignoresLookAlikes() {
    assertFalse(WakeRule.called(WakeRule.fromPartial("claro que sí"), settled = false))
    assertFalse(WakeRule.called(WakeRule.fromPartial("hay cola"), settled = false))
    assertFalse(WakeRule.called(WakeRule.fromPartial("oye claudia"), settled = false))
    assertFalse(WakeRule.called(settled("sí" to 0.4, "clau" to 0.4, "lo" to 0.45), settled = true))
    assertFalse(WakeRule.called(settled("hey" to 0.9, "clo" to 0.3), settled = true))
  }

  @Test
  fun grammarIsAJsonArrayOfEveryWord() {
    val g = WakeRule.GRAMMAR
    assertTrue(g.startsWith("[\"clau\",\"clo\","))
    assertTrue(g.endsWith(",\"[unk]\"]"))
    assertEquals(WakeRule.NAMES.size + WakeRule.CALL.size + WakeRule.DECOYS.size + 1, g.count { it == ',' } + 1)
  }
}
