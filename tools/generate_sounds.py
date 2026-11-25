#!/usr/bin/env python3
"""
Generates 90s-arcade style WAV sound effects under app/src/main/assets/sounds/:
  - countdown_beep.wav  (440 Hz square-wave countdown pip)
  - countdown_go.wav    (880 Hz + 1320 Hz bright GO! tone)
  - engine_loop.wav     (seamless loopable sawtooth + sub-square engine rumble)
  - jump.wav            (upward arcade pitch sweep)
"""
import os
import struct
import wave

SAMPLE_RATE = 22050
SOUNDS_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app",
    "src",
    "main",
    "assets",
    "sounds",
)


def write_wav(filename: str, samples: list[float]) -> None:
    os.makedirs(SOUNDS_DIR, exist_ok=True)
    path = os.path.join(SOUNDS_DIR, filename)
    with wave.open(path, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        pcm = bytearray()
        for s in samples:
            clamped = max(-1.0, min(1.0, s))
            pcm.extend(struct.pack("<h", int(clamped * 32000)))
        wf.writeframes(bytes(pcm))
    print(f"Generated {filename} ({len(samples)} samples) -> {path}")


def gen_countdown_beep() -> list[float]:
    dur = 0.12
    n = int(SAMPLE_RATE * dur)
    freq = 440.0
    out = []
    for i in range(n):
        t = i / SAMPLE_RATE
        p = i / float(n)
        env = p / 0.08 if p < 0.08 else max(0.0, 1.0 - (p - 0.08) / 0.92)
        phase = (t * freq) % 1.0
        sq = 1.0 if phase < 0.5 else -1.0
        out.append(sq * env * 0.55)
    return out


def gen_countdown_go() -> list[float]:
    dur = 0.26
    n = int(SAMPLE_RATE * dur)
    f1, f2 = 880.0, 1320.0
    out = []
    for i in range(n):
        t = i / SAMPLE_RATE
        p = i / float(n)
        env = p / 0.06 if p < 0.06 else max(0.0, 1.0 - (p - 0.06) / 0.94)
        s1 = 1.0 if (t * f1) % 1.0 < 0.5 else -1.0
        s2 = 1.0 if (t * f2) % 1.0 < 0.5 else -1.0
        out.append((s1 * 0.65 + s2 * 0.35) * env * 0.55)
    return out


def gen_engine_loop() -> list[float]:
    # Exact integer number of cycles at 55 Hz -> seamless loop with zero click
    freq = 55.0
    cycles = 28
    dur = cycles / freq  # ~0.509s
    n = int(round(SAMPLE_RATE * dur))
    lp = 0.0
    out = []
    for i in range(n):
        phase = (i * cycles / float(n)) % 1.0
        sub_phase = (i * (cycles * 0.5) / float(n)) % 1.0
        saw = phase * 2.0 - 1.0
        sub_sq = 1.0 if sub_phase < 0.5 else -1.0
        raw = saw * 0.65 + sub_sq * 0.35
        lp += 0.16 * (raw - lp)
        out.append(lp * 0.75)
    return out


def gen_jump() -> list[float]:
    dur = 0.22
    n = int(SAMPLE_RATE * dur)
    phase = 0.0
    out = []
    for i in range(n):
        p = i / float(n)
        freq = 185.0 + p * 460.0
        env = (1.0 - p) ** 1.8
        phase = (phase + freq / SAMPLE_RATE) % 1.0
        tri = 1.0 - 4.0 * abs(phase - 0.5)
        sq = 1.0 if phase < 0.5 else -1.0
        out.append((tri * 0.65 + sq * 0.35) * env * 0.70)
    return out


if __name__ == "__main__":
    write_wav("countdown_beep.wav", gen_countdown_beep())
    write_wav("countdown_go.wav", gen_countdown_go())
    write_wav("engine_loop.wav", gen_engine_loop())
    write_wav("jump.wav", gen_jump())
