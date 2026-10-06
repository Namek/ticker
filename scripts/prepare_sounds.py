#!/usr/bin/env python3
"""Trim and normalize the bundled field recordings. Requires FFmpeg on PATH."""
import math
from pathlib import Path
import struct
import subprocess
import wave

ROOT = Path(__file__).resolve().parents[1]
RATE = 44_100
# Source, start in seconds, length in milliseconds, tail fade in milliseconds.
SOUNDS = {
    "tick": ("clock-original.wav", 1.032, 300, 60),
    "electric_clock": ("electric-clock-original.mp3", 8.315, 150, 40),
    "gong": ("gong-original.mp3", 0.0, 3_000, 900),
    "bowl": ("bowl-original.ogg", 0.0, 3_000, 900),
    "bell": ("bell-original.mp3", 0.20, 2_000, 600),
}

for name, (source, start, duration, fade) in SOUNDS.items():
    command = [
        "ffmpeg", "-v", "error", "-i", str(ROOT / "audio-sources" / source),
        "-ac", "1", "-ar", str(RATE),
    ]
    if name == "bell":
        # Reduce isolated transients so peak normalization also lifts the audible body.
        command += ["-af", "acompressor=threshold=0.08:ratio=3:attack=0.2:release=60"]
    decoded = subprocess.check_output(command + ["-f", "f32le", "-"])
    samples = [value[0] for value in struct.iter_unpack("<f", decoded)]
    offset, length = round(start * RATE), RATE * duration // 1_000
    samples = samples[offset:offset + length]
    assert len(samples) == length, f"Recording too short: {source}"
    fade_frames = RATE * fade // 1_000
    for index in range(length):
        tail = min(1.0, (length - 1 - index) / fade_frames)
        # A 1 ms attack removes edit clicks without softening the recorded strike.
        attack = min(1.0, index / (RATE * 0.001))
        samples[index] *= attack * (0.5 - 0.5 * math.cos(math.pi * tail))
    # The gong needs headroom for ringing tails when intervals are short.
    peak = 0.45 if name == "gong" else 0.94
    gain = peak / max(abs(value) for value in samples)
    frames = b"".join(struct.pack("<h", round(value * gain * 32767)) for value in samples)
    output = ROOT / "app/src/main/res/raw" / f"{name}.wav"
    with wave.open(str(output), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(RATE)
        audio.writeframes(frames)
    rms = math.sqrt(sum((value * gain) ** 2 for value in samples) / length)
    print(f"{name}: {duration} ms, peak {20 * math.log10(peak):.2f} dBFS, RMS {20 * math.log10(rms):.2f} dBFS, gain {20 * math.log10(gain):+.2f} dB")
