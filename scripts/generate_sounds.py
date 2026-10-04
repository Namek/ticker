#!/usr/bin/env python3
"""Generate Tick's original percussion samples using only Python's standard library."""
import math
from pathlib import Path
import struct
import wave

RATE = 44_100
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw"


def render(name, duration_ms, modes, attack=0.004, drive=1.0):
    duration = duration_ms / 1_000
    samples = []
    for index in range(RATE * duration_ms // 1_000):
        time = index / RATE
        sample = sum(amplitude * math.sin(2 * math.pi * frequency * time)
                     * math.exp(-time / decay) for frequency, amplitude, decay in modes)
        # Gentle saturation adds body; a cosine tail prevents an abrupt cutoff.
        fade = min(1.0, (duration - time) / min(0.12, duration / 3))
        envelope = min(1.0, time / attack) * (0.5 - 0.5 * math.cos(math.pi * fade))
        samples.append(math.tanh(drive * sample) * envelope)
    gain = 0.94 / max(abs(sample) for sample in samples)
    frames = b"".join(struct.pack("<h", round(sample * gain * 32767)) for sample in samples)
    with wave.open(str(OUTPUT / (name + ".wav")), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(RATE)
        audio.writeframes(frames)


# Stronger, longer low strike with midrange resonance audible on a phone speaker.
render("tick", 180, [(330, 1, .055), (660, .7, .035), (990, .35, .015), (1480, .1, .010)],
       attack=.001, drive=1.4)
# Inharmonic modes give the gong its broad metallic strike.
render("gong", 750, [(160, .65, .3), (277, 1.1, .42), (280, .25, .42), (391, .6, .3),
                     (543, .65, .28), (729, .36, .22), (978, .25, .16), (1405, .1, .07)])
# Close frequency pairs produce the slow beating of a singing bowl.
render("bowl", 750, [(432, .7, .65), (435, .7, .65), (1157, .27, .4), (1162, .27, .4),
                     (2010, .1, .2)], attack=.006)
render("bell", 600, [(660, 1, .28), (1320, .45, .19), (1848, .25, .12), (2772, .1, .06)])
