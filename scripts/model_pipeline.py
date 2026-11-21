#!/usr/bin/env python3
"""Exercise both CPU models on a real WAV using the app's native spectrum code."""
import argparse
import ctypes
import math
import pathlib
import subprocess
import tempfile
import wave

import numpy as np
import onnxruntime as ort

ROOT = pathlib.Path(__file__).resolve().parent.parent
DSP = ROOT / "app/src/main/cpp"

BRIDGE = """
#include "dsp.h"
#include <algorithm>
extern "C" void spectrum(const float* input, int size, float* output) {
    const auto values = pitchline::stft(input, size);
    std::copy(values.begin(), values.end(), output);
}
extern "C" void reconstruct(const float* input, const float* model, int size, float* output) {
    const auto values = pitchline::istft(input, model, size);
    std::copy(values.begin(), values.end(), output);
}
"""


def run(source, workspace):
    with wave.open(str(source), "rb") as reader:
        if (reader.getnchannels(), reader.getsampwidth(), reader.getframerate()) != (2, 2, 44100):
            raise ValueError("Use stereo 44.1 kHz PCM16")
        mix = np.frombuffer(reader.readframes(reader.getnframes()), dtype="<i2").reshape(-1, 2).astype(np.float32) / 32768
    if not 44100 <= len(mix) <= 44100 * 20:
        raise ValueError("Use a 1–20 second clip")
    with tempfile.TemporaryDirectory() as temp:
        library = pathlib.Path(temp) / "spectrum.so"
        bridge = pathlib.Path(temp) / "bridge.cpp"
        bridge.write_text(BRIDGE)
        subprocess.run(["c++", "-shared", "-fPIC", "-O3", "-std=c++17", "-I", str(DSP),
                        str(bridge), str(DSP / "dsp.cpp"), "-o", str(library)], check=True)
        dsp = ctypes.CDLL(str(library))
        array = np.ctypeslib.ndpointer(dtype=np.float32, flags="C_CONTIGUOUS")
        dsp.spectrum.argtypes = [array, ctypes.c_int, array]
        dsp.reconstruct.argtypes = [array, array, ctypes.c_int, array]
        spectrum = np.zeros((len(mix) // 1024 + 1, 2, 2049, 2), dtype=np.float32)
        dsp.spectrum(mix, len(mix), spectrum)
        magnitude = np.ascontiguousarray(np.hypot(spectrum[..., 0], spectrum[..., 1]))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        for mode in ("standard", "high"):
            session = ort.InferenceSession(str(workspace / f"{mode}.onnx"), options, providers=["CPUExecutionProvider"])
            estimate = session.run(["vocals"], {"magnitude": magnitude})[0]
            voice = np.empty_like(mix)
            dsp.reconstruct(spectrum, estimate, len(mix), voice)
            backing = mix - voice
            assert np.all(np.isfinite(voice))
            assert np.max(np.abs(voice)) > 0.01
            assert np.max(np.abs(backing)) > 0.01
            null_peak = np.max(np.abs(voice + backing - mix))
            assert null_peak < 2e-7
            rms = lambda signal: math.sqrt(np.mean(signal ** 2))
            print(f"{mode}: {len(mix)} frames/stem, RMS vocals {rms(voice):.4f}, backing {rms(backing):.4f}, reconstruction peak {null_peak:.2g}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", type=pathlib.Path)
    parser.add_argument("--workspace", type=pathlib.Path, required=True)
    args = parser.parse_args()
    run(args.wav, args.workspace)
