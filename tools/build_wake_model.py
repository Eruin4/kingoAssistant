from __future__ import annotations

import argparse
import json
import math
import wave
from pathlib import Path


SAMPLE_RATE = 16000
FRAME_SIZE = 400
HOP_SIZE = 160
FRAME_COUNT = 48
NEGATIVE_WINDOW_SECONDS = 1.6
NEGATIVE_STRIDE_SECONDS = 0.8
PROBE_FREQS = (220.0, 350.0, 550.0, 800.0, 1200.0, 1800.0, 2600.0, 3800.0)


def read_wav(path: Path) -> list[float]:
    with wave.open(str(path), "rb") as wav:
        if wav.getsampwidth() != 2:
            raise ValueError(f"{path}: expected 16-bit PCM WAV")
        channels = wav.getnchannels()
        sample_rate = wav.getframerate()
        raw = wav.readframes(wav.getnframes())
    samples = []
    frame_width = channels * 2
    for i in range(0, len(raw), frame_width):
        total = 0
        for channel in range(channels):
            start = i + channel * 2
            total += int.from_bytes(raw[start : start + 2], "little", signed=True)
        samples.append((total / channels) / 32768.0)
    if sample_rate != SAMPLE_RATE:
        samples = resample_audio(samples, sample_rate, SAMPLE_RATE)
    return trim_silence(samples)


def resample_audio(samples: list[float], source_rate: int, target_rate: int) -> list[float]:
    if source_rate == target_rate or not samples:
        return samples
    target_len = max(1, int(len(samples) * target_rate / source_rate))
    output = []
    for i in range(target_len):
        pos = i * (len(samples) - 1) / max(1, target_len - 1)
        left = int(math.floor(pos))
        right = min(len(samples) - 1, left + 1)
        frac = pos - left
        output.append(samples[left] * (1.0 - frac) + samples[right] * frac)
    return output


def trim_silence(samples: list[float]) -> list[float]:
    if not samples:
        return samples
    frame = HOP_SIZE
    levels = []
    for start in range(0, max(1, len(samples) - frame), frame):
        chunk = samples[start : start + frame]
        rms = math.sqrt(sum(x * x for x in chunk) / max(1, len(chunk)))
        levels.append(rms)
    peak = max(levels or [0.0])
    threshold = max(0.004, peak * 0.18)
    first = 0
    last = len(samples)
    for idx, level in enumerate(levels):
        if level >= threshold:
            first = max(0, idx * frame - frame * 2)
            break
    for idx in range(len(levels) - 1, -1, -1):
        if levels[idx] >= threshold:
            last = min(len(samples), (idx + 3) * frame)
            break
    trimmed = samples[first:last]
    min_len = int(SAMPLE_RATE * 0.45)
    return trimmed if len(trimmed) >= min_len else samples


def goertzel_power(frame: list[float], freq: float) -> float:
    omega = 2.0 * math.pi * freq / SAMPLE_RATE
    coeff = 2.0 * math.cos(omega)
    prev = 0.0
    prev2 = 0.0
    for sample in frame:
        value = sample + coeff * prev - prev2
        prev2 = prev
        prev = value
    return prev2 * prev2 + prev * prev - coeff * prev * prev2


def frame_features(samples: list[float]) -> list[list[float]]:
    if len(samples) < FRAME_SIZE:
        samples = samples + [0.0] * (FRAME_SIZE - len(samples))
    features = []
    for start in range(0, len(samples) - FRAME_SIZE + 1, HOP_SIZE):
        frame = samples[start : start + FRAME_SIZE]
        rms = math.sqrt(sum(x * x for x in frame) / FRAME_SIZE)
        zcr = 0
        for i in range(1, len(frame)):
            if (frame[i - 1] >= 0.0) != (frame[i] >= 0.0):
                zcr += 1
        row = [math.log(max(rms, 1e-5)), zcr / FRAME_SIZE]
        row.extend(math.log(goertzel_power(frame, freq) + 1e-8) for freq in PROBE_FREQS)
        features.append(row)
    return normalize(resample_frames(features, FRAME_COUNT))


def resample_frames(features: list[list[float]], target_count: int) -> list[list[float]]:
    if not features:
        return [[0.0] * (2 + len(PROBE_FREQS)) for _ in range(target_count)]
    if len(features) == 1:
        return [features[0][:] for _ in range(target_count)]
    output = []
    for i in range(target_count):
        pos = i * (len(features) - 1) / max(1, target_count - 1)
        left = int(math.floor(pos))
        right = min(len(features) - 1, left + 1)
        frac = pos - left
        output.append([
            features[left][j] * (1.0 - frac) + features[right][j] * frac
            for j in range(len(features[0]))
        ])
    return output


def normalize(features: list[list[float]]) -> list[list[float]]:
    cols = len(features[0])
    means = [sum(row[col] for row in features) / len(features) for col in range(cols)]
    stds = []
    for col in range(cols):
        var = sum((row[col] - means[col]) ** 2 for row in features) / len(features)
        stds.append(max(0.001, math.sqrt(var)))
    return [[(row[col] - means[col]) / stds[col] for col in range(cols)] for row in features]


def distance(a: list[list[float]], b: list[list[float]]) -> float:
    total = 0.0
    count = 0
    for row_a, row_b in zip(a, b):
        for value_a, value_b in zip(row_a, row_b):
            total += abs(value_a - value_b)
            count += 1
    return total / max(1, count)


def collect_templates(directory: Path, *, split_long: bool = False) -> list[dict]:
    templates = []
    for path in sorted(directory.glob("*.wav")):
        samples = read_wav(path)
        if split_long and len(samples) > int(SAMPLE_RATE * NEGATIVE_WINDOW_SECONDS * 1.5):
            window = int(SAMPLE_RATE * NEGATIVE_WINDOW_SECONDS)
            stride = int(SAMPLE_RATE * NEGATIVE_STRIDE_SECONDS)
            index = 1
            for start in range(0, max(1, len(samples) - window + 1), stride):
                chunk = samples[start : start + window]
                if len(chunk) < window:
                    continue
                if rms(chunk) < 0.004:
                    continue
                templates.append({"name": f"{path.name}#{index:03d}", "features": frame_features(chunk)})
                index += 1
        else:
            templates.append({"name": path.name, "features": frame_features(samples)})
    return templates


def rms(samples: list[float]) -> float:
    return math.sqrt(sum(value * value for value in samples) / max(1, len(samples)))


def estimate_threshold(positive: list[dict], negative: list[dict]) -> float:
    if len(positive) < 2:
        return 0.62
    positive_scores = []
    for idx, item in enumerate(positive):
        others = [entry for other_idx, entry in enumerate(positive) if other_idx != idx]
        best_distance = min(distance(item["features"], other["features"]) for other in others)
        positive_scores.append(1.0 / (1.0 + best_distance))
    min_positive = min(positive_scores)
    if negative:
        negative_scores = []
        for item in negative:
            best_distance = min(distance(item["features"], other["features"]) for other in positive)
            negative_scores.append(1.0 / (1.0 + best_distance))
        return max(0.45, min(0.85, (min_positive + max(negative_scores)) / 2.0))
    return max(0.45, min(0.85, min_positive * 0.95))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--positive-dir", default="wake_samples/positive")
    parser.add_argument("--negative-dir", default="wake_samples/negative")
    parser.add_argument("--output", default="app/src/main/assets/wake_model.json")
    args = parser.parse_args()

    positive = collect_templates(Path(args.positive_dir))
    if not positive:
        raise SystemExit(f"No positive WAV samples found in {args.positive_dir}")
    negative_dir = Path(args.negative_dir)
    negative = collect_templates(negative_dir, split_long=True) if negative_dir.exists() else []
    threshold = estimate_threshold(positive, negative)
    model = {
        "version": 1,
        "sample_rate": SAMPLE_RATE,
        "frame_count": FRAME_COUNT,
        "feature_count": 2 + len(PROBE_FREQS),
        "threshold": threshold,
        "templates": positive,
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"Wrote {output} with {len(positive)} positive templates, threshold={threshold:.3f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
