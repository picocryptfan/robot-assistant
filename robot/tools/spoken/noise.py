# SPDX-License-Identifier: GPL-3.0-only
"""Background noise made by code, so nothing is downloaded: pink noise, a fan's hum, and babble from overlapping voices.
Every function gives the same result for the same random generator. Floats from -1 to 1. Needs numpy."""
import numpy as np

RATE = 16000


def rms(x: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0


def _unit(x: np.ndarray) -> np.ndarray:
    peak = float(np.max(np.abs(x))) if len(x) else 0.0
    return (x / peak).astype(np.float32) if peak else x.astype(np.float32)


def pink(n: int, rng: np.random.Generator) -> np.ndarray:
    """White noise shaped to 1/f power in the frequency domain."""
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    f[0] = f[1] if n > 2 else 1.0
    return _unit(np.fft.irfft(spec / np.sqrt(f), n))


def fan(n: int, rng: np.random.Generator) -> np.ndarray:
    """A motor's hum (a fundamental near 110 Hz and its fading harmonics, wavering slowly) over low rushing air."""
    t = np.arange(n) / RATE
    base = rng.uniform(100, 120)
    wobble = 1 + 0.15 * np.sin(2 * np.pi * rng.uniform(0.2, 0.6) * t)
    hum = sum(np.sin(2 * np.pi * base * h * t + rng.uniform(0, 6.28)) / h for h in range(1, 6)) * wobble
    spec = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / RATE)
    air = np.fft.irfft(spec * (f < 1200) / np.maximum(f, 40) * 40, n)
    return _unit(_unit(hum) * 0.6 + _unit(air))


def babble(n: int, rng: np.random.Generator, voices: list[np.ndarray], talkers: int = 6) -> np.ndarray:
    """Several people talking at once: each of `talkers` is one of `voices`, looped from a random place."""
    if not voices or any(not len(v) for v in voices):
        raise ValueError("babble needs voices")
    out = np.zeros(n, np.float64)
    for _ in range(talkers):
        v = voices[int(rng.integers(len(voices)))]
        at = int(rng.integers(len(v)))
        out += np.resize(np.roll(v, -at), n)
    return _unit(out)


def mix(speech: np.ndarray, noise: np.ndarray, snr_db: float) -> np.ndarray:
    """Speech over noise at snr_db, measured over the speech itself (the silence around it doesn't count), scaled down
    together if the sum would clip."""
    loud = speech[np.abs(speech) > 1e-3]
    if not len(loud):
        raise ValueError("no speech to mix")
    gain = rms(loud) / (rms(noise) * 10 ** (snr_db / 20))
    out = speech + noise * gain
    peak = float(np.max(np.abs(out)))
    return (out * 0.95 / peak if peak > 0.95 else out).astype(np.float32)
