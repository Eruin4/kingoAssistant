from __future__ import annotations

import argparse
import sys
import wave
from pathlib import Path


SAMPLE_RATE = 16000


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--label", choices=("positive", "negative"), default="positive")
    parser.add_argument("--count", type=int, default=30)
    parser.add_argument("--seconds", type=float, default=1.6)
    parser.add_argument("--output-root", default="wake_samples")
    args = parser.parse_args()

    try:
        import sounddevice as sd
    except ImportError:
        print("Missing dependency: sounddevice. Install with `python -m pip install sounddevice`.", file=sys.stderr)
        return 1

    out_dir = Path(args.output_root) / args.label
    out_dir.mkdir(parents=True, exist_ok=True)
    frames = int(SAMPLE_RATE * args.seconds)
    for index in range(1, args.count + 1):
        input(f"{args.label} sample {index}/{args.count}: press Enter, then speak...")
        audio = sd.rec(frames, samplerate=SAMPLE_RATE, channels=1, dtype="int16")
        sd.wait()
        path = out_dir / f"{args.label}_{index:03d}.wav"
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(SAMPLE_RATE)
            wav.writeframes(audio.tobytes())
        print(f"saved {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
