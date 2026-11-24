package com.keonyrus.tupovision

/**
 * When the offline recogniser has heard "hey Claw" / "oye Claw". Plain Kotlin, no Android, so it
 * runs in unit tests and matches tools/wake_test.py line for line.
 *
 * The small Spanish Vosk model has no word "claw": it hears it as "clo" or "clau". Said fast,
 * "hey" and "oye" often come back as "hay" and "hoy". The grammar also lists decoys made of the
 * same sounds ("claro", "clausura", "cola"...) so everyday speech lands on those instead.
 *
 * The approach (restricted grammar with decoys, name position rule, confidence check) follows
 * CJ's Hotword.kt by Ghanshyam Paunikar (MIT); the word lists and thresholds are MetaClaw's own,
 * tuned on synthetic Spanish speech: 124/129 clips right, no false wake in 96 decoy sentences.
 */
object WakeRule {
  /** How the Spanish model writes "Claw". */
  val NAMES = setOf("clo", "clau")
  /** Words that may come right before the name. */
  val CALL = setOf("hey", "oye", "ey", "hay", "hoy")
  val DECOYS =
      listOf(
          "claus", "klaus", "claro", "clara", "clavo", "clase", "clases", "cola", "claudia", "cloe",
          "oiga", "mira", "eloy", "coloca", "clausura", "clonar", "clic", "cloro", "claudio", "clave",
          "no", "lo", "la", "el", "que", "y", "o", "sí", "vale", "hola",
      )
  /** Settled results: the name after a call word needs at least this confidence. */
  const val MIN_CONFIDENCE = 0.5
  /** The name alone at the start, without "hey"/"oye", has to be clearer than that. */
  const val START_CONFIDENCE = 0.6

  /** Vosk grammar: a JSON array of the only words it may hear. (No org.json: unit tests run off Android.) */
  val GRAMMAR: String =
      (NAMES.sorted() + CALL.sorted() + DECOYS + "[unk]").joinToString(",", "[", "]") { "\"$it\"" }

  /** One recognised word; [confidence] is only known in settled results. */
  data class Heard(val word: String, val confidence: Double?)

  /**
   * The name right after a call word. Alone at the very start it only counts in a settled,
   * confident result: partial results are guesses, and "Clausura..." begins as "clau".
   */
  fun called(words: List<Heard>, settled: Boolean): Boolean =
      words.indices.any { i ->
        val name = words[i]
        if (name.word !in NAMES) return@any false
        val confidence = name.confidence ?: 1.0
        val afterCall = i > 0 && words[i - 1].word in CALL && confidence >= MIN_CONFIDENCE
        val atStart = i == 0 && settled && confidence >= START_CONFIDENCE
        afterCall || atStart
      }

  /** Partial results are a plain space-separated string. */
  fun fromPartial(text: String): List<Heard> =
      text.split(' ').filter { it.isNotEmpty() }.map { Heard(it, null) }
}
