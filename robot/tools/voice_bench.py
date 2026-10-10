#!/usr/bin/env python3
"""The phone's "Test voice speed" (Settings › Voice) on a computer, with the same models, settings and clip.

Runs on the sherpa-onnx 1.13.8 Python wheel (same commit as the app's libsherpa-onnx-jni.so), so the numbers are a
proxy for the phone: x86 is not ARM. Prints numbers only, as the app logs them, plus the decoded text of the
built-in synthetic clip (it is not anyone's speech).

Measures, as the app does (voice/.../VoiceBenchmark.kt):
  - speech recognition (Parakeet v3 int8, 4 threads): load, 3 decodes of the clip (median), real-time factor,
    word accuracy against bench/test-clip.txt, simulated-streaming partial decodes every 0.7 s, and where the
    end-of-utterance VAD (Silero, 0.5 s silence, 20 s max speech) ends the clip;
  - each voice (Kokoro v1.0 fp32 af_heart, Kitten nano 0.8 fp32 expr-voice-5-f), 4 threads: load, time to first audio for the
    5-word first chunk and the 16-word sentence, and real-time factor of the 16-word sentence.

Usage: python3 -I tools/voice_bench.py <robot project dir> <voice models dir>
"""
import resource
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx as so

FIRST_CHUNK = "Sure, here is the weather."
SENTENCE = "The weather today is sunny with a light breeze, and it should stay warm until the evening."
THREADS = 4


def rss_mb() -> float:
    return resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024


def words(s: str) -> list[str]:
    return "".join(c.lower() if c.isalnum() or c.isspace() or c == "'" else " " for c in s).split()


def word_accuracy(ref: str, hyp: str) -> float:
    r, h = words(ref), words(hyp)
    d = list(range(len(h) + 1))
    for i in range(1, len(r) + 1):
        prev, d[0] = d[0], i
        for j in range(1, len(h) + 1):
            cur = min(d[j] + 1, d[j - 1] + 1, prev + (r[i - 1] != h[j - 1]))
            prev, d[j] = d[j], cur
    return max(0.0, 1 - d[len(h)] / max(1, len(r)))


def stt(root: Path, models: Path) -> None:
    d = models / "stt-parakeet-v3"
    clip = np.frombuffer((root / "voice/src/main/assets/bench/test-clip.s16").read_bytes(), "<i2").astype(np.float32) / 32768
    expected = (root / "voice/src/main/assets/bench/test-clip.txt").read_text().strip()
    t0 = time.time()
    rec = so.OfflineRecognizer.from_transducer(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), joiner=str(d / "joiner.int8.onnx"),
        tokens=str(d / "tokens.txt"), model_type="nemo_transducer", num_threads=THREADS, sample_rate=16000,
        feature_dim=80, decoding_method="greedy_search", debug=False, provider="cpu")
    load = time.time() - t0

    def decode(x):
        s = rec.create_stream()
        s.accept_waveform(16000, x)
        t = time.time()
        rec.decode_stream(s)
        return s.result, time.time() - t

    times, res = [], None
    for _ in range(3):
        res, dt = decode(clip)
        times.append(dt)
    dec = statistics.median(times)
    secs = len(clip) / 16000
    print(f"stt load_s={load:.2f} clip_s={secs:.2f} decode_s={dec:.3f} rtf={dec / secs:.3f} "
          f"word_accuracy={word_accuracy(expected, res.text):.2f} lang='{getattr(res, 'lang', '')}' rss_mb={rss_mb():.0f}")
    print(f"stt text: {res.text}")

    # End-of-utterance VAD, as SherpaSpeechToText sets it up.
    vad = so.VoiceActivityDetector(so.VadModelConfig(
        silero_vad=so.SileroVadModelConfig(model=str(root / "voice/src/main/assets/vad/silero_vad.onnx"), threshold=0.5,
                                           min_silence_duration=0.5, min_speech_duration=0.25, window_size=512,
                                           max_speech_duration=20.0),
        sample_rate=16000, num_threads=1), buffer_size_in_seconds=60)
    first, last_true, ended = None, None, None
    for i in range(0, len(clip) - 511, 512):
        vad.accept_waveform(clip[i:i + 512])
        sp = vad.is_speech_detected()
        while not vad.empty():
            vad.pop()
        t = (i + 512) / 16000
        if sp and first is None:
            first = t
        if sp:
            last_true = t
        if first is not None and not sp and ended is None:
            ended = t
    print(f"vad speech_detected_at_s={first} flag_off_at_s={ended} last_on_s={last_true} (clip {secs:.2f} s)")

    # Simulated streaming: decode the growing buffer every 0.7 s once 1 s of speech is in.
    pt = []
    for end in np.arange(1.0, secs, 0.7):
        _, dt = decode(clip[: int(end * 16000)])
        pt.append(dt)
    print(f"partials n={len(pt)} decode_s median={statistics.median(pt):.3f} max={max(pt):.3f}")


def tts(root: Path, models: Path, name: str) -> None:
    espeak = str(root / "voice/src/main/assets/espeak-ng-data")
    if name == "kokoro":
        d = models / "tts-kokoro-v1.0"
        mc = so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(
            model=str(d / "model.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"), data_dir=espeak, lang="en-us"),
            num_threads=THREADS, debug=False, provider="cpu")
        sid = 3
    else:
        d = models / "tts-kitten-nano-0.8"
        mc = so.OfflineTtsModelConfig(kitten=so.OfflineTtsKittenModelConfig(
            model=str(d / "model.fp32.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"), data_dir=espeak),
            num_threads=THREADS, debug=False, provider="cpu")
        sid = 7  # expr-voice-5-f, as the app (VoiceChoice.KITTEN)
    t0 = time.time()
    t = so.OfflineTts(so.OfflineTtsConfig(model=mc, max_num_sentences=1, silence_scale=0.2))
    load = time.time() - t0

    def run(text):
        first = []
        start = time.time()

        def cb(samples, progress):
            if not first:
                first.append(time.time() - start)
            return 1
        gc = so.GenerationConfig()
        gc.sid = sid
        gc.speed = 1.0
        a = t.generate(text, gc, cb)
        return first[0], time.time() - start, len(a.samples) / a.sample_rate

    run(FIRST_CHUNK)  # warm-up, as the phone's first reply after loading is not counted either
    f5, _, _ = run(FIRST_CHUNK)
    f16, total, audio = run(SENTENCE)
    print(f"tts {name} load_s={load:.2f} first_chunk_first_audio_s={f5:.3f} sentence_first_audio_s={f16:.3f} "
          f"sentence_audio_s={audio:.2f} rtf={total / audio:.3f} rss_mb={rss_mb():.0f}")


def main() -> None:
    root, models = Path(sys.argv[1]), Path(sys.argv[2])
    print(f"sherpa-onnx {so.__version__} threads={THREADS}")
    stt(root, models)
    for name in ("kokoro", "kitten"):
        tts(root, models, name)


if __name__ == "__main__":
    main()
