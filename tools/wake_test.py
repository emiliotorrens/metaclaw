"""Checks the offline "hey Claw" / "oye Claw" wake phrase against synthetic speech: does it wake on the phrase
and ignore look-alike Spanish sentences?

Needs the model from scripts/get-wake-model.sh, ffmpeg, network for the voices, and:  pip install vosk edge-tts
Run:  python tools/wake_test.py [model_dir] [min_confidence]
Keep NAMES/CALL/DECOYS and the rule in sync with app/.../WakeRule.kt.
Last result: 124/129. No false wake in the 96 decoy clips; the 5 misses are US English voices and one Mexican voice.
Idea and structure from CJ's tools/wake_test.py (MIT, Ghanshyam Paunikar)."""
import asyncio, json, subprocess, sys, tempfile, wave
from pathlib import Path
import edge_tts
from vosk import KaldiRecognizer, Model, SetLogLevel
SetLogLevel(-1)
MODEL = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).resolve().parent.parent / "app/src/main/assets/model-es")
NAMES = {"clo", "clau"}  # how the Spanish model hears "Claw"
CALL = {"hey", "oye", "ey", "hay", "hoy"}  # "hey"/"oye" said fast often decode as "hay"/"hoy"
DECOYS = ["claus", "klaus", "claro", "clara", "clavo", "clase", "clases", "cola", "claudia", "cloe", "oiga", "mira",
          "eloy", "coloca", "clausura", "clonar", "clic", "cloro", "claudio", "clave", "no", "lo", "la", "el", "que", "y", "o", "sí", "vale", "hola"]
GRAMMAR = json.dumps(sorted(NAMES) + sorted(CALL) + DECOYS + ["[unk]"], ensure_ascii=False)
START_CONF = 0.6
MIN_CONF = float(sys.argv[2]) if len(sys.argv) > 2 else 0.5
VOICES = ["es-ES-AlvaroNeural", "es-ES-ElviraNeural", "es-ES-XimenaNeural", "es-MX-JorgeNeural"]
SHOULD_WAKE = ["Hey Clo", "Oye Clo, ¿qué hora es?", "Hey Clau, ¿cuándo sale mi vuelo?", "Oye Clau, ¿qué estoy mirando?",
               "Oye, Clo, léeme esto", "Hey Clo, apunta esto"]
SHOULD_NOT = ["Oye, ¿clavas el clavo?", "Claro que sí", "Hay cola en la tienda", "Oye, mira esto", "Clara viene mañana",
              "Oye, Claudia, ven", "Hoy hay clases", "Eloy come pronto", "Cloe llega tarde", "Coloca la caja ahí",
              "Hey, ¿qué tal?", "Oye, ¿lo has visto?", "Es claro como el agua", "La clave está aquí",
              "Cloro para la piscina", "Claudio me llama", "Clausura del congreso", "Clonar el disco", "Colombia es bonita",
              "Clic en el botón", "Qué calor hace", "Oye, cómo estás", "Hey, mira", "Y lo sabes"]

def called(words, settled=True):
    """The name right after "hey"/"oye" (or how they decode). Alone at the very start it only counts in a
    settled, confident result: partials are guesses and "Clausura..." starts as "clau"."""
    for i, b in enumerate(words):
        if b["word"] not in NAMES: continue
        conf = b.get("conf", 1.0)
        if i > 0 and words[i - 1]["word"] in CALL and conf >= MIN_CONF: return True
        if i == 0 and settled and conf >= START_CONF: return True
    return False

def wakes(model, wav):
    rec = KaldiRecognizer(model, 16000, GRAMMAR); rec.SetWords(True)
    heard = []
    with wave.open(str(wav)) as w:
        while data := w.readframes(2048):
            if rec.AcceptWaveform(data):
                r = json.loads(rec.Result()).get("result", []); heard += [f'{x["word"]}:{x.get("conf",0):.2f}' for x in r]
                if called(r): return True, heard
            elif called([{"word": x} for x in json.loads(rec.PartialResult())["partial"].split()], settled=False):
                return True, heard + ["(partial)"]
    r = json.loads(rec.FinalResult()).get("result", []); heard += [f'{x["word"]}:{x.get("conf",0):.2f}' for x in r]
    return called(r), heard

CACHE = Path(__file__).resolve().parent / "clips"

async def clip(text, voice, tmp):
    import hashlib
    CACHE.mkdir(exist_ok=True)
    wav = CACHE / (hashlib.sha1(f"{voice}|{text}".encode()).hexdigest()[:16] + ".wav")
    if wav.exists(): return wav
    mp3 = tmp / "c.mp3"
    await edge_tts.Communicate(text, voice).save(str(mp3))
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", str(mp3), "-ar", "16000", "-ac", "1", str(wav)], check=True)
    return wav

async def main():
    model = Model(str(MODEL)); ok = total = 0
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        for want, texts in ((True, SHOULD_WAKE), (False, SHOULD_NOT)):
            for text in texts:
                for v in VOICES:
                    got, heard = wakes(model, await clip(text, v, tmp)); total += 1
                    if got == want: ok += 1
                    else: print(f"FAIL want={want} {v}: {text!r} heard={' '.join(heard)}", flush=True)
        # The name said the English way ("claw"), by an English voice, still has to wake the Spanish model.
        for text in ["Hey Claw", "Hey Claw, what time is it?", "Oye Claw, ¿qué hora es?"]:
            for v in ["en-US-GuyNeural", "en-GB-RyanNeural", "en-US-JennyNeural"]:
                got, heard = wakes(model, await clip(text, v, tmp)); total += 1
                if got: ok += 1
                else: print(f"FAIL want=True {v}: {text!r} heard={' '.join(heard)}", flush=True)
    print(f"{ok}/{total}")
asyncio.run(main())
