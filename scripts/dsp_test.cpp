#include "dsp.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <numeric>
#include <random>
#include <string_view>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;
constexpr int rate = 48000;
constexpr int hop = 576;
constexpr int count = 250;

void require(bool value, std::string_view message) {
    if (!value) {
        std::cerr << "FAIL " << message << '\n';
        std::exit(1);
    }
}

double cents(double found, double expected) { return 1200 * std::log2(found / expected); }

template <class F>
std::vector<float> signal(int frames, int sampleRate, F pitch, int harmonicProfile = 0) {
    double phase = 0;
    std::vector<float> result(frames);
    for (int i = 0; i < frames; ++i) {
        phase += 2 * pi * pitch(i / double(sampleRate)) / sampleRate;
        double sample = 0;
        if (harmonicProfile == 0) sample = 0.38 * std::sin(phase) + 0.21 * std::sin(2 * phase + 0.12) + 0.08 * std::sin(3 * phase);
        if (harmonicProfile == 1) sample = 0.055 * std::sin(phase) + 0.55 * std::sin(2 * phase + 0.4) + 0.16 * std::sin(3 * phase);
        if (harmonicProfile == 2) sample = 0.63 * std::sin(phase) + 0.035 * std::sin(2 * phase);
        result[i] = float(sample);
    }
    return result;
}

std::vector<pitchline::Pitch> analyze(const std::vector<float>& pcm, int sampleRate, int step, int size) {
    return pitchline::detect(pcm.data(), pcm.size(), 1, sampleRate, step, 0, size);
}

double medianCents(const std::vector<pitchline::Pitch>& track, double expected) {
    std::vector<double> errors;
    for (size_t i = 15; i + 15 < track.size(); ++i) {
        if (track[i].hz > 0) errors.push_back(cents(track[i].hz, expected));
    }
    require(errors.size() > 0.85 * (track.size() - 30), "voiced recall");
    std::sort(errors.begin(), errors.end());
    return errors[errors.size() / 2];
}

std::vector<float> synthesize(const std::vector<float>& pcm, const std::vector<pitchline::Pitch>& detected,
                              const std::vector<float>& shift, float color = 0) {
    std::vector<float> f0;
    for (const auto& frame : detected) f0.push_back(frame.hz);
    const std::vector<float> formant(f0.size(), color);
    return pitchline::resynthesize(pcm.data(), pcm.size(), 1, rate, hop, 0,
                                   f0.data(), shift.data(), formant.data(), f0.size());
}

double f0Deviation(const std::vector<pitchline::Pitch>& pitch, double center) {
    double sum = 0;
    int n = 0;
    for (size_t i = 20; i + 20 < pitch.size(); ++i) {
        if (pitch[i].hz <= 0) continue;
        sum += std::pow(cents(pitch[i].hz, center), 2);
        ++n;
    }
    require(n > 150, "F0 deviation voiced recall");
    return std::sqrt(sum / n);
}

// A conventional normalized-autocorrelation baseline on the same decimated samples.
double autocorrelation(const std::vector<float>& data, int sampleRate, size_t frame) {
    const int step = sampleRate / 11000;
    const int n = int(0.024 * sampleRate / step);
    const int low = sampleRate / (step * 1500);
    const int high = sampleRate / (step * 65) + 2;
    std::vector<double> scores(high + 1);
    for (int lag = low; lag <= high; ++lag) {
        double a = 0, b = 0, ab = 0;
        for (int i = 0; i < n; ++i) {
            const auto offset = frame + (i - (n + lag) / 2) * step;
            const double x = data[offset], y = data[offset + lag * step];
            a += x * x;
            b += y * y;
            ab += x * y;
        }
        scores[lag] = a * b > 1e-9 ? ab / std::sqrt(a * b) : 0;
    }
    const double maximum = *std::max_element(scores.begin(), scores.end());
    if (maximum < 0.72) return 0;
    for (int lag = low + 1; lag < high; ++lag) {
        if (scores[lag] < maximum - 0.12 || scores[lag] < scores[lag - 1] || scores[lag] < scores[lag + 1]) continue;
        const double l = scores[lag - 1], m = scores[lag], r = scores[lag + 1];
        const double offset = std::clamp(0.5 * (l - r) / (l - 2 * m + r - 1e-15), -0.5, 0.5);
        return sampleRate / (step * (lag + offset));
    }
    return 0;
}

void tracking() {
    int yin = 0, ac = 0, total = 0;
    double worst = 0;
    for (int sampleRate : {44100, 48000}) {
        for (double hz : {70., 91., 110., 220., 442., 731., 1123., 1391.}) {
            for (int profile = 0; profile < 3; ++profile) {
                const auto pcm = signal(sampleRate / 3, sampleRate, [hz](double) { return hz; }, profile);
                const auto midi = analyze(pcm, sampleRate, sampleRate / 100, 20)[12].hz;
                const auto baseline = autocorrelation(pcm, sampleRate, 12 * (sampleRate / 100));
                if (midi > 0 && std::abs(cents(midi, hz)) <= 20) {
                    ++yin;
                    worst = std::max(worst, std::abs(cents(midi, hz)));
                } else std::cout << "YIN miss " << sampleRate << ' ' << hz << ' ' << profile << " -> " << midi << '\n';
                if (baseline > 0 && std::abs(cents(baseline, hz)) <= 20) ++ac;
                ++total;
            }
        }
    }
    std::cout << "F0 within 20 cents: YIN " << yin << "/" << total << ", normalized autocorrelation " << ac
              << "/" << total << "; worst YIN hit " << worst << " cents\n";
    require(yin >= total - 2 && yin >= ac, "70–1400 Hz baseline");
    std::mt19937 engine(14);
    std::uniform_real_distribution<float> distribution(-0.3f, 0.3f);
    std::vector<float> noise(rate);
    for (auto& sample : noise) sample = distribution(engine);
    for (const auto& p : analyze(noise, rate, hop, 80)) require(p.hz == 0, "unvoiced noise rejection");

    const auto leap = signal(rate * 2, rate, [](double t) { return t < 0.72 ? 175.0 : 523.25; }, 1);
    const auto jump = analyze(leap, rate, hop, 145);
    require(std::abs(cents(jump[56].hz, 175)) < 12 && std::abs(cents(jump[64].hz, 523.25)) < 12,
            "rapid jump across separated pitches");
}

void editing() {
    const auto clean = signal(count * hop, rate, [](double) { return 215.0; });
    const auto detected = analyze(clean, rate, hop, count);
    const auto identity = synthesize(clean, detected, std::vector<float>(count));
    require(identity == clean, "untouched samples are bit exact");
    const double original = medianCents(detected, 215);
    for (float amount : {100.f, 50.f, 1.f, -1.f, -100.f, 700.f, -700.f}) {
        const auto changed = synthesize(clean, detected, std::vector<float>(count, amount));
        require(changed.size() == clean.size(), "sample length invariant");
        const double after = medianCents(analyze(changed, rate, hop, count), 215);
        std::cout << "Render " << amount << " cents: measured " << after - original << " cents\n";
        require(std::abs(after - original - amount) < 11, "F0 follows semitone and cent changes");
        if (std::abs(amount) == 1) require((after - original) * amount > 0, "cent micro-adjustment moves in the requested direction");
    }
    const auto headVoice = signal(count * hop, rate, [](double) { return 1280.0; }, 2);
    const auto highPitch = analyze(headVoice, rate, hop, count);
    const auto raisedHeadVoice = synthesize(headVoice, highPitch, std::vector<float>(count, 50.f));
    const auto highAfter = analyze(raisedHeadVoice, rate, hop, count);
    const double highShift = medianCents(highAfter, 1280) - medianCents(highPitch, 1280);
    std::cout << "1280 Hz +50 cents: measured " << highShift << " cents\n";
    require(std::abs(highShift - 50) < 10, "high head-voice pitch remains trackable");

    const auto wobble = signal(count * hop, rate, [](double t) { return 215 * std::exp2(0.068 * std::sin(2 * pi * 4.5 * t)); });
    const auto wobblePitch = analyze(wobble, rate, hop, count);
    std::vector<float> correction;
    for (const auto& f : wobblePitch) correction.push_back(f.hz > 0 ? float(-cents(f.hz, 215)) : 0.f);
    const auto straight = synthesize(wobble, wobblePitch, correction);
    const auto beforeRms = f0Deviation(wobblePitch, 215);
    const auto afterRms = f0Deviation(analyze(straight, rate, hop, count), 215);
    std::cout << "Straighten F0 deviation: " << beforeRms << " -> " << afterRms << " cents RMS\n";
    require(afterRms < beforeRms * 0.40, "flattening reduces pitch variation");

    std::vector<float> vibrato(count);
    for (int i = 0; i < count; ++i) vibrato[i] = float(82 * std::sin(2 * pi * 5.25 * i * hop / rate));
    const auto rendered = synthesize(clean, detected, vibrato);
    const auto measured = analyze(rendered, rate, hop, count);
    double product = 0, targetEnergy = 0, outputEnergy = 0;
    for (int i = 20; i < count - 20; ++i) {
        const double error = cents(measured[i].hz, 215);
        product += vibrato[i] * error;
        targetEnergy += vibrato[i] * vibrato[i];
        outputEnergy += error * error;
    }
    const double correlation = product / std::sqrt(targetEnergy * outputEnergy);
    const double depth = std::sqrt(2 * outputEnergy / (count - 40));
    std::cout << "5.25 Hz vibrato F0 correlation: " << correlation << ", depth " << depth << " cents\n";
    require(correlation > 0.9 && std::abs(depth - 82) < 20, "vibrato follows requested cycle and depth");

    auto interrupted = clean;
    std::fill(interrupted.begin() + 90 * hop, interrupted.begin() + 105 * hop, 0.f);
    const auto marked = analyze(interrupted, rate, hop, count);
    const auto boundaries = synthesize(interrupted, marked, std::vector<float>(count, 250.f));
    for (int i = 93 * hop; i < 102 * hop; ++i) require(boundaries[i] == 0, "unvoiced middle stays untouched");
    float worst = 0;
    // Measure the edit contribution to the first difference at voiced-region boundaries.
    for (int boundary = 3; boundary < count - 3; ++boundary) {
        if ((marked[boundary - 1].hz > 0) == (marked[boundary].hz > 0)) continue;
        for (int i = boundary * hop - 32; i < boundary * hop + 32; ++i) {
            worst = std::max(worst, std::abs((boundaries[i] - boundaries[i - 1]) - (interrupted[i] - interrupted[i - 1])));
        }
    }
    std::cout << "Additional boundary peak difference: " << worst << '\n';
    require(worst < 0.012f, "no added impulse at breath boundary");
}

double resonance(const std::vector<float>& signal) {
    std::vector<float> stereo(rate * 2);
    for (int i = 0; i < rate; ++i) stereo[2 * i] = stereo[2 * i + 1] = signal[rate + i];
    const auto spectrum = pitchline::stft(stereo.data(), rate);
    double centroid = 0, power = 0;
    for (int t = 1; t < pitchline::spectralFrames(rate) - 1; ++t) {
        for (int b = 1; b < pitchline::bins; ++b) {
            const double frequency = b * rate / double(pitchline::fftSize);
            if (frequency < 275 || frequency > 4400) continue;
            const int index = ((t * 2) * pitchline::bins + b) * 2;
            const double p = std::pow(spectrum[index], 2) + std::pow(spectrum[index + 1], 2);
            centroid += frequency * p;
            power += p;
        }
    }
    return centroid / power;
}

void formants() {
    constexpr double fundamental = 140;
    std::vector<float> vowel(count * hop);
    for (int h = 1; h <= 28; ++h) {
        const double frequency = fundamental * h;
        const double f1 = std::exp(-0.5 * std::pow((frequency - 920) / 250, 2));
        const double f2 = 0.45 * std::exp(-0.5 * std::pow((frequency - 2150) / 330, 2));
        for (size_t i = 0; i < vowel.size(); ++i) {
            vowel[i] += float(0.1 * (f1 + f2 + 0.13 / h) * std::sin(2 * pi * frequency * i / rate + 0.3 * h));
        }
    }
    const auto pitch = analyze(vowel, rate, hop, count);
    const auto raised = synthesize(vowel, pitch, std::vector<float>(count, 400));
    const auto colored = synthesize(vowel, pitch, std::vector<float>(count, 0), 420);
    const double center = resonance(vowel);
    const double after = resonance(raised);
    const double tone = resonance(colored);
    std::cout << "Vowel spectral centroid: " << center << " Hz, shifted F0 " << after << " Hz, raised formant " << tone << " Hz\n";
    require(std::abs(after - center) / center < 0.17, "spectral envelope broadly preserved");
    require(tone > center * 1.12, "formant control moves spectral envelope");
}

void signalChain() {
    std::vector<float> stereo(29013 * 2);
    for (size_t i = 0; i < stereo.size() / 2; ++i) {
        stereo[2 * i] = 0.3f * std::sin(2 * pi * 440 * i / rate);
        stereo[2 * i + 1] = 0.27f * std::cos(2 * pi * 1318 * i / rate);
    }
    const auto spectrum = pitchline::stft(stereo.data(), stereo.size() / 2);
    std::vector<float> magnitude(spectrum.size() / 2);
    for (size_t i = 0; i < magnitude.size(); ++i) magnitude[i] = std::hypot(spectrum[i * 2], spectrum[i * 2 + 1]);
    const auto recovered = pitchline::istft(spectrum.data(), magnitude.data(), stereo.size() / 2);
    double rms = 0;
    for (size_t i = 0; i < stereo.size(); ++i) rms += std::pow(stereo[i] - recovered[i], 2);
    require(std::sqrt(rms / stereo.size()) < 4e-5, "separation STFT preserves sample alignment and amplitude");
    const int frames = int(std::ceil((stereo.size() / 2) * 44100.0 / rate));
    const auto down = pitchline::resample(stereo.data(), stereo.size() / 2, 2, rate, 44100, 0, frames);
    const auto up = pitchline::resample(down.data(), frames, 2, 44100, rate, 0, stereo.size() / 2);
    rms = 0;
    for (size_t i = 200; i < stereo.size() - 200; ++i) rms += std::pow(stereo[i] - up[i], 2);
    require(std::sqrt(rms / stereo.size()) < 5e-5, "48 kHz to 44.1 kHz round trip without sync drift");
}
}

int main() {
    tracking();
    editing();
    formants();
    signalChain();
    std::cout << "DSP checks passed\n";
}
