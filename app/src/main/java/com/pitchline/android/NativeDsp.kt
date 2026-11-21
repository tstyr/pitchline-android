package com.pitchline.android

object NativeDsp {
    init { System.loadLibrary("pitchline") }

    external fun detect(input: FloatArray, channels: Int, rate: Int, hop: Int, first: Int, count: Int): FloatArray
    external fun resynthesize(
        input: FloatArray, channels: Int, rate: Int, hop: Int, origin: Int,
        f0: FloatArray, cents: FloatArray, formant: FloatArray,
    ): FloatArray
    external fun resample(
        input: FloatArray, channels: Int, sourceRate: Int, destinationRate: Int,
        first: Double, outputFrames: Int,
    ): FloatArray
    external fun stft(stereo: FloatArray): FloatArray
    external fun istft(spectrum: FloatArray, estimates: FloatArray, frames: Int): FloatArray
}
