#pragma once

#include <vector>

namespace pitchline {
struct Pitch {
    float hz = 0;
    float confidence = 0;
};

std::vector<Pitch> detect(const float* audio, int frames, int channels, int rate, int hop, int first, int count);
std::vector<float> resynthesize(const float* audio, int frames, int channels, int rate, int hop,
                                int origin, const float* f0, const float* cents, const float* formant, int count);
std::vector<float> resample(const float* audio, int frames, int channels, int sourceRate,
                            int destinationRate, double firstSourceFrame, int outputFrames);
constexpr int fftSize = 4096;
constexpr int spectralHop = 1024;
constexpr int bins = fftSize / 2 + 1;
int spectralFrames(int frames);
std::vector<float> stft(const float* audio, int frames);
std::vector<float> istft(const float* spectrum, const float* estimate, int frames);
}
