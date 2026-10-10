#!/usr/bin/env python3
"""Writes the built-in speech clip for Settings › Voice › "Test voice speed".

The clip is synthetic speech: Kokoro v1.0 fp32 (voice af_heart, sid 3) reads SENTENCE on this computer through
the sherpa-onnx 1.13.8 Python wheel (the same commit as the app's libsherpa-onnx-jni.so). It is resampled to
16 kHz (windowed-sinc, 24 kHz x 2 / 3) and stored as raw signed 16-bit little-endian mono, so the app can decode it
with no audio library and nothing is ever written to disk on the phone. Kokoro is not deterministic, so a new run
gives a slightly different (equally valid) clip; the expected text never changes.

Needs: pip install sherpa-onnx==1.13.8 numpy, the Kokoro files (tools/fetch_voice_models.sh <dir> tts-kokoro-v1.0)
and espeak-ng-data (tools/fetch_third_party.sh --install puts the subset in voice/src/main/assets/espeak-ng-data).

Usage: python3 -I tools/make_test_clip.py <robot project dir> <voice models dir>
"""
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx as so

# Kept in step with the app (bench/test-clip.txt is what the benchmark compares the transcript with).
SENTENCE = "Please remind me to water the plants when I get home this evening, and turn on the flashlight."
SID = 3  # af_heart
RATE = 16000
MAX_BYTES = 200_000


def resample_24k_to_16k(x: np.ndarray) -> np.ndarray:
    """Up by 2 (zero stuffing), low-pass below 8 kHz at 48 kHz (Blackman-windowed sinc), down by 3."""
    up = np.zeros(len(x) * 2, np.float64)
    up[::2] = x
    taps = 255
    n = np.arange(taps) - (taps - 1) / 2
    cutoff = 7600 / 48000  # cycles per sample, a little under 8 kHz
    h = 2 * cutoff * np.sinc(2 * cutoff * n) * np.blackman(taps)
    h *= 2 / h.sum()  # gain 2 makes up for the zeros
    return np.convolve(up, h, mode="same")[::3].astype(np.float32)


def main() -> None:
    root, models = Path(sys.argv[1]), Path(sys.argv[2])
    k = models / "tts-kokoro-v1.0"
    espeak = root / "voice" / "src" / "main" / "assets" / "espeak-ng-data"
    tts = so.OfflineTts(so.OfflineTtsConfig(
        model=so.OfflineTtsModelConfig(
            kokoro=so.OfflineTtsKokoroModelConfig(model=str(k / "model.onnx"), voices=str(k / "voices.bin"),
                                                  tokens=str(k / "tokens.txt"), data_dir=str(espeak), lang="en-us"),
            num_threads=4, debug=False, provider="cpu"),
        max_num_sentences=1))
    gc = so.GenerationConfig()
    gc.sid = SID
    gc.speed = 1.0
    audio = tts.generate(SENTENCE, gc)
    x = np.asarray(audio.samples, dtype=np.float32)
    if audio.sample_rate != 24000:
        sys.exit(f"unexpected sample rate {audio.sample_rate}")
    y = resample_24k_to_16k(x)
    # 0.25 s of silence before and 0.6 s after, as a person pausing; the end lets the VAD see the silence.
    y = np.concatenate([np.zeros(RATE // 4, np.float32), y, np.zeros(int(RATE * 0.6), np.float32)])
    peak = float(np.max(np.abs(y))) or 1.0
    pcm = np.clip(y / peak * 0.7, -1, 1)
    data = (pcm * 32767).astype("<i2").tobytes()
    if len(data) > MAX_BYTES:
        sys.exit(f"clip too large: {len(data)} bytes")
    out = root / "voice" / "src" / "main" / "assets" / "bench"
    out.mkdir(parents=True, exist_ok=True)
    (out / "test-clip.s16").write_bytes(data)
    (out / "test-clip.txt").write_text(SENTENCE + "\n", encoding="utf-8")
    print(f"{out / 'test-clip.s16'}: {len(data)} bytes, {len(pcm) / RATE:.2f} s at {RATE} Hz")


if __name__ == "__main__":
    main()
