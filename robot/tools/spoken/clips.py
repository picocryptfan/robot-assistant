#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Makes the spoken tests' clips on this computer, and checks each with the phone's recognizer before it's used.

Each "say" step's clip is synthesized with the sherpa-onnx 1.13.8 Python wheel (the app's commit), resampled to 16 kHz
and kept as raw signed 16-bit little-endian mono, the speed test's format. Before noise goes in, the clean clip is
decoded with Parakeet as tools/voice_bench.py does: a clip the recognizer can't read even clean is marked dropped, and the
tests that need it are blocked, not failed. What was decoded (clean and with its noise) goes in a .json beside the clip.

Clips are never in the repository or an APK: they stay in --out, outside it. Files never change once made (Kokoro isn't
deterministic, so a clip's name comes from what it says, not its samples).

Needs: pip install sherpa-onnx==1.13.8 numpy; the voice models (tools/fetch_voice_models.sh <dir> stt-parakeet-v3
tts-kokoro-v1.0 tts-kitten-nano-0.8); espeak-ng-data (tools/fetch_third_party.sh --install).

Usage: python3 -I tools/spoken/clips.py --models <dir> [--out <dir>] [script.json ...]   (default: every script)
"""
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scripts  # noqa: E402

ROBOT = HERE.parent.parent
CLIP_VERSION = 1  # part of every clip's name: raise it when the way clips are made changes
LEAD_S, TAIL_S = 0.25, 0.6  # silence before and after, a person pausing; the end lets the VAD see the silence
BABBLE = {"heart": "The meeting moved to Thursday, so we should bring the plans, the drawings and something to eat for everyone.",
          "kitten": "I walked along the water for an hour yesterday, and the wind was so strong I could barely hear my phone.",
          "siwis": "Nous avons pris le train de bonne heure, puis la pluie a commencé juste avant notre arrivée au marché."}
NUMBERS = {"en": "zero one two three four five six seven eight nine ten eleven twelve".split(),
           "fr": "zero un deux trois quatre cinq six sept huit neuf dix onze douze".split()}


def default_out() -> Path:
    return Path(os.environ.get("ROBOT_SPOKEN_CLIPS") or Path.home() / ".cache/robot-spoken/clips")


def spec_of(step: dict) -> dict:
    return {"text": step["text"], "voice": step["voice"], "speed": step.get("speed", 1.0), "noise": step.get("noise", "none"),
            "snr": step.get("snr")}


def name_of(spec: dict, cfg: dict) -> str:
    """say-<hash>.s16, or no-<hash>.s16 for a clip that's nothing but a "no": the only clips the test build's receiver
    plays while a risky card shows. It can't take the prefix from anywhere but this check of the text."""
    key = json.dumps({**spec, "v": CLIP_VERSION}, sort_keys=True, ensure_ascii=False).encode()
    kind = "no" if scripts.is_decline(spec["text"], cfg["voices"][spec["voice"]]["lang"], cfg) else "say"
    return f"{kind}-{hashlib.sha256(key).hexdigest()[:16]}{cfg['clip']['suffix']}"


def word_accuracy(expected: str, heard: str, lang: str = "en") -> float:
    """1 minus the word error rate, at least 0. A digit the recognizer wrote counts as the same word spelled out."""
    def words(s: str) -> list[str]:
        spelled = NUMBERS[lang]
        return [spelled[int(w)] if re.fullmatch(r"\d+", w) and int(w) < len(spelled) else w for w in scripts.norm(s).split()]
    ref, hyp = words(expected), words(heard)
    if not ref:
        return 0.0
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return max(0.0, 1 - row[-1] / len(ref))


class Maker:
    """Holds the engines (each loaded once, when first needed)."""

    def __init__(self, out: Path, models: Path, espeak: Path, cfg: dict):
        import numpy as np
        import sherpa_onnx as so
        self.np, self.so, self.out, self.models, self.espeak, self.cfg = np, so, out, models, espeak, cfg
        self.tts: dict = {}
        self.rec = None
        self.babble_voices: list = []

    def _tts(self, voice: str):
        info = self.cfg["voices"][voice]
        key = (info["engine"], info["lang"])
        if key not in self.tts:
            so, espeak = self.so, str(self.espeak)
            if info["engine"] == "kokoro":
                d = self.models / "tts-kokoro-v1.0"
                mc = so.OfflineTtsModelConfig(kokoro=so.OfflineTtsKokoroModelConfig(
                    model=str(d / "model.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"), data_dir=espeak,
                    lang="en-us" if info["lang"] == "en" else info["lang"]), num_threads=4, debug=False, provider="cpu")
            else:
                d = self.models / "tts-kitten-nano-0.8"
                mc = so.OfflineTtsModelConfig(kitten=so.OfflineTtsKittenModelConfig(
                    model=str(d / "model.fp32.onnx"), voices=str(d / "voices.bin"), tokens=str(d / "tokens.txt"), data_dir=espeak),
                    num_threads=4, debug=False, provider="cpu")
            self.tts[key] = so.OfflineTts(so.OfflineTtsConfig(model=mc, max_num_sentences=1))
        return self.tts[key]

    def speak(self, text: str, voice: str, speed: float):
        """Speech at 16 kHz, floats, with no silence added."""
        sys.path.insert(0, str(HERE.parent))
        from make_test_clip import resample_24k_to_16k
        gc = self.so.GenerationConfig()
        gc.sid = self.cfg["voices"][voice]["sid"]
        gc.speed = speed
        audio = self._tts(voice).generate(text, gc)
        if audio.sample_rate != 24000:
            raise RuntimeError(f"unexpected sample rate {audio.sample_rate}")
        x = resample_24k_to_16k(self.np.asarray(audio.samples, dtype=self.np.float32))
        if not len(x):
            raise RuntimeError(f"no audio for {text!r}")
        return x

    def decode(self, x) -> str:
        if self.rec is None:
            d = self.models / "stt-parakeet-v3"
            self.rec = self.so.OfflineRecognizer.from_transducer(
                encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"), joiner=str(d / "joiner.int8.onnx"),
                tokens=str(d / "tokens.txt"), model_type="nemo_transducer", num_threads=4, sample_rate=16000, feature_dim=80,
                decoding_method="greedy_search", debug=False, provider="cpu")
        s = self.rec.create_stream()
        s.accept_waveform(16000, x)
        self.rec.decode_stream(s)
        return s.result.text.strip()

    def _noise(self, kind: str, n: int, rng):
        import noise
        if kind == "pink":
            return noise.pink(n, rng)
        if kind == "fan":
            return noise.fan(n, rng)
        if not self.babble_voices:
            self.babble_voices = [self.speak(t, v, 1.0) for v, t in BABBLE.items()]
        return noise.babble(n, rng, self.babble_voices)

    def make(self, spec: dict) -> dict:
        """Writes the clip and its .json (a dropped clip gets the .json alone) and returns the .json's content."""
        import noise
        np, name = self.np, name_of(spec, self.cfg)
        lang = self.cfg["voices"][spec["voice"]]["lang"]
        x = self.speak(spec["text"], spec["voice"], spec["speed"])
        x = x / float(np.max(np.abs(x))) * 0.7
        x = np.concatenate([np.zeros(int(16000 * LEAD_S), np.float32), x, np.zeros(int(16000 * TAIL_S), np.float32)])
        clean = self.decode(x)
        meta = {**spec, "name": name, "heardClean": clean, "cleanAccuracy": round(word_accuracy(spec["text"], clean, lang), 3)}
        meta["dropped"] = meta["cleanAccuracy"] < self.cfg["minCleanAccuracy"]
        if not meta["dropped"]:
            if spec["noise"] != "none":
                seed = int(hashlib.sha256(name.encode()).hexdigest()[:8], 16)
                x = noise.mix(x, self._noise(spec["noise"], len(x), np.random.default_rng(seed)), spec["snr"])
                heard = self.decode(x)
                meta["heardWithNoise"], meta["noiseAccuracy"] = heard, round(word_accuracy(spec["text"], heard, lang), 3)
            meta["seconds"] = round(len(x) / 16000, 2)
            tmp = self.out / (name + ".tmp")
            tmp.write_bytes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
            tmp.replace(self.out / name)
        (self.out / (name + ".json")).write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        return meta


def known(spec: dict, out: Path, cfg: dict) -> dict | None:
    """A clip's .json if it was made and its clip is there (or it was dropped); None means it must be made."""
    name = name_of(spec, cfg)
    try:
        meta = json.loads((out / (name + ".json")).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    return meta if meta.get("dropped") or (out / name).is_file() else None


def ensure(specs: list[dict], out: Path, models: Path | None, espeak: Path, cfg: dict, say=print) -> dict[str, dict]:
    """Finds or makes every clip; returns name -> its .json's content. Without the wheel or models, the runner can still use
    clips made earlier: only a missing clip needs them."""
    out.mkdir(parents=True, exist_ok=True)
    found: dict[str, dict] = {}
    missing = []
    for spec in specs:
        name = name_of(spec, cfg)
        if name in found or any(name_of(m, cfg) == name for m in missing):
            continue
        meta = known(spec, out, cfg)
        if meta is None:
            missing.append(spec)
        else:
            found[name] = meta
    if missing:
        if models is None:
            raise RuntimeError(f"{len(missing)} clips aren't made yet: give --models")
        maker = Maker(out, models, espeak, cfg)
        for i, spec in enumerate(missing, 1):
            meta = maker.make(spec)
            found[meta["name"]] = meta
            state = "dropped" if meta["dropped"] else f"clean {meta['cleanAccuracy']}" + (
                f", with noise {meta['noiseAccuracy']}" if "noiseAccuracy" in meta else "")
            say(f"clip {i}/{len(missing)} {meta['name']}: {state}: {spec['text']!r}")
    return found


def main() -> int:
    ap = argparse.ArgumentParser(description="Makes and checks the spoken tests' clips.")
    ap.add_argument("--models", type=Path, required=True)
    ap.add_argument("--out", type=Path, default=default_out())
    ap.add_argument("--espeak", type=Path, default=ROBOT / "voice/src/main/assets/espeak-ng-data")
    ap.add_argument("scripts", nargs="*", type=Path)
    a = ap.parse_args()
    cfg, specs = scripts.config(), []
    for p in a.scripts or sorted(scripts.TESTS.glob("*.json")):
        script, problems = scripts.load(p)
        if problems:
            print(f"{p.name}: {problems[0]}")
            return 1
        specs += [spec_of(s) for s in scripts.clip_specs(script)]
    metas = ensure(specs, a.out, a.models, a.espeak, cfg)
    dropped = [m["name"] for m in metas.values() if m["dropped"]]
    print(f"{len(metas)} clips in {a.out}, {len(dropped)} dropped")
    return 0


if __name__ == "__main__":
    sys.exit(main())
