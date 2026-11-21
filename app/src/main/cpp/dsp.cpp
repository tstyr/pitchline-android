#include "dsp.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <limits>

namespace pitchline {
namespace {
constexpr double pi = 3.14159265358979323846;

float interpolate(const float* data, int frames, int channels, int channel, double frame) {
    const int a = static_cast<int>(std::floor(frame));
    const auto sample = [&](int i) { return i >= 0 && i < frames ? data[i * channels + channel] : 0.0f; };
    return sample(a) + static_cast<float>(frame - a) * (sample(a + 1) - sample(a));
}

void fft(std::vector<std::complex<float>>& a, bool inverse) {
    const auto size = static_cast<unsigned>(a.size());
    for (unsigned i = 1, j = 0; i < size; ++i) {
        unsigned bit = size >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (unsigned width = 2; width <= size; width <<= 1) {
        const auto angle = static_cast<float>((inverse ? 2 : -2) * pi / width);
        const std::complex<float> step(std::cos(angle), std::sin(angle));
        for (unsigned i = 0; i < size; i += width) {
            std::complex<float> w(1);
            for (unsigned j = 0; j < width / 2; ++j) {
                const auto u = a[i + j];
                const auto v = a[i + j + width / 2] * w;
                a[i + j] = u + v;
                a[i + j + width / 2] = u - v;
                w *= step;
            }
        }
    }
    if (inverse) for (auto& value : a) value /= static_cast<float>(size);
}

std::vector<float> hann() {
    std::vector<float> w(fftSize);
    for (int i = 0; i < fftSize; ++i) w[i] = static_cast<float>(0.5 - 0.5 * std::cos(2 * pi * i / fftSize));
    return w;
}

int reflect(int i, int frames) {
    if (frames < 2) return 0;
    const int period = 2 * (frames - 1);
    const int wrapped = (i % period + period) % period;
    return std::min(wrapped, period - wrapped);
}
}

std::vector<Pitch> detect(const float* audio, int frames, int channels, int rate, int hop, int first, int count) {
    // Triangular anti-alias filter followed by decimation for the YIN difference function.
    const int stride = std::max(1, rate / 11500);
    const double reducedRate = static_cast<double>(rate) / stride;
    const int reducedFrames = (frames + stride - 1) / stride;
    std::vector<float> mono(reducedFrames);
    for (int i = 0; i < reducedFrames; ++i) {
        for (int d = 1 - stride; d < stride; ++d) {
            const int j = std::clamp(i * stride + d, 0, frames - 1);
            for (int c = 0; c < channels; ++c) {
                mono[i] += audio[j * channels + c] * (stride - std::abs(d));
            }
        }
        mono[i] /= channels * stride * stride;
    }
    const int minLag = std::max(2, static_cast<int>(reducedRate / 1500));
    const int maxLag = static_cast<int>(std::ceil(reducedRate / 65));
    const int width = static_cast<int>(reducedRate * 0.024);
    std::vector<Pitch> result(count);
    std::vector<float> window(width + maxLag + 2);
    std::vector<double> curve(maxLag + 2, 1);
    for (int n = 0; n < count; ++n) {
        const int start = (first + n * hop) / stride - (width + maxLag) / 2;
        for (int i = 0; i < static_cast<int>(window.size()); ++i) {
            window[i] = start + i >= 0 && start + i < reducedFrames ? mono[start + i] : 0;
        }
        double mean = 0, energy = 0;
        for (const auto sample : window) {
            mean += sample;
            energy += sample * sample;
        }
        mean /= window.size();
        if (energy / window.size() - mean * mean < 0.000007) continue;

        double sum = 0;
        for (int lag = 1; lag <= maxLag + 1; ++lag) {
            // Center each candidate's paired windows around the same analysis sample.
            const int offset = (maxLag + 1 - lag) / 2;
            double difference = 0;
            for (int i = offset; i < offset + width; ++i) {
                const float delta = window[i] - window[i + lag];
                difference += delta * delta;
            }
            sum += difference;
            curve[lag] = sum > 1e-12 ? difference * lag / sum : 1.0;
        }
        const auto minimum = [&](int lag) {
            const double slope = curve[lag - 1] - curve[lag + 1];
            const double curvature = curve[lag - 1] - 2 * curve[lag] + curve[lag + 1];
            return curvature > 1e-9 ? std::max(0.0, curve[lag] - slope * slope / (8 * curvature)) : curve[lag];
        };
        int best = minLag;
        int candidate = 0;
        for (int lag = minLag; lag <= maxLag; ++lag) {
            if (curve[lag] < curve[best]) best = lag;
            if (candidate == 0 && curve[lag] <= curve[lag - 1] && curve[lag] <= curve[lag + 1] && minimum(lag) < 0.16) {
                candidate = lag;
            }
        }
        if (candidate == 0) candidate = best;
        if (minimum(candidate) > 0.32) continue;
        // Reject half periods with a large mismatch when their doubled lag fits much better.
        const int doubled = 2 * candidate;
        if (doubled + 2 <= maxLag && minimum(candidate) > 0.065) {
            int local = doubled;
            for (int i = doubled - 2; i <= doubled + 2; ++i) if (curve[i] < curve[local]) local = i;
            if (minimum(local) < minimum(candidate) * 0.37) candidate = local;
        }
        const double left = curve[candidate - 1], mid = curve[candidate], right = curve[candidate + 1];
        const double divisor = left - 2 * mid + right;
        const double correction = divisor > 1e-9 ? std::clamp(0.5 * (left - right) / divisor, -0.5, 0.5) : 0;
        const float hz = static_cast<float>(reducedRate / (candidate + correction));
        if (hz >= 67 && hz <= 1450) result[n] = {hz, static_cast<float>(1 - minimum(candidate))};
    }
    return result;
}

std::vector<float> resynthesize(const float* audio, int frames, int channels, int rate, int hop,
                                int origin, const float* f0, const float* cents, const float* formant, int count) {
    std::vector<float> out(audio, audio + frames * channels);
    std::vector<float> wet(frames * channels);
    std::vector<float> weights(frames);
    std::vector<float> mono(frames);
    for (int i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) mono[i] += audio[i * channels + c] / channels;
    }
    const auto sampleTrack = [count, hop, origin](const float* track, double frame) {
        const double position = std::clamp((frame - origin) / hop, 0.0, static_cast<double>(count - 1));
        const int j = static_cast<int>(position);
        return track[j] + (track[std::min(j + 1, count - 1)] - track[j]) * (position - j);
    };
    // Close short gaps around zero crossings of a continuous edit so phase is not mixed twice per vibrato cycle.
    std::vector<float> editedRegion(count);
    const int maximumGap = std::max(1, static_cast<int>(0.12 * rate / hop));
    for (int i = 0, previous = -maximumGap - 1; i < count; ++i) {
        if (f0[i] <= 0) { previous = -maximumGap - 1; continue; }
        if (std::abs(cents[i]) <= 0.15f && std::abs(formant[i]) <= 0.15f) continue;
        if (i - previous <= maximumGap) std::fill(editedRegion.begin() + previous, editedRegion.begin() + i, 1.0f);
        editedRegion[i] = 1.0f;
        previous = i;
    }
    const auto pulse = [&](int previous, int estimate, double period, int end) {
        const int reach = std::max(1, static_cast<int>(period * 0.18));
        const int radius = std::max(2, static_cast<int>(period * 0.31));
        int best = estimate;
        double bestScore = -10;
        for (int test = std::max(previous + 1, estimate - reach); test <= std::min(end - 1, estimate + reach); ++test) {
            double product = 0, a = 1e-10, b = 1e-10;
            for (int d = -radius; d <= radius; d += 2) {
                const float x = mono[std::clamp(previous + d, 0, frames - 1)];
                const float y = mono[std::clamp(test + d, 0, frames - 1)];
                product += x * y;
                a += x * x;
                b += y * y;
            }
            const double score = product / std::sqrt(a * b) - 0.035 * std::abs(test - estimate) / reach;
            if (score > bestScore) { bestScore = score; best = test; }
        }
        return best;
    };

    for (int a = 0; a < count;) {
        if (f0[a] <= 0) { ++a; continue; }
        int b = a + 1;
        while (b < count && f0[b] > 0) ++b;
        bool edited = false;
        for (int i = a; i < b; ++i) {
            if (std::abs(cents[i]) > 0.15 || std::abs(formant[i]) > 0.15) { edited = true; break; }
        }
        const int begin = std::max(0, origin + a * hop);
        const int end = std::min(frames, origin + b * hop);
        a = b;
        if (!edited || end - begin < hop) continue;

        std::vector<int> marks;
        const int initialPeriod = static_cast<int>(rate / sampleTrack(f0, begin));
        const int initialEnd = std::min(end, begin + initialPeriod);
        int mark = static_cast<int>(std::max_element(mono.begin() + begin, mono.begin() + initialEnd) - mono.begin());
        marks.push_back(mark);
        while (true) {
            const double period = rate / std::max(67.0, sampleTrack(f0, mark));
            const int estimate = mark + static_cast<int>(std::lround(period));
            if (estimate >= end) break;
            mark = pulse(mark, estimate, period, end);
            marks.push_back(mark);
        }

        size_t nearest = 0;
        for (double target = marks.front(); target < end;) {
            const double hz = std::max(67.0, sampleTrack(f0, target));
            const double sourcePeriod = rate / hz;
            const double ratio = std::exp2(std::clamp(sampleTrack(cents, target), -1200.0, 1200.0) / 1200);
            const double grainRatio = std::exp2(std::clamp(sampleTrack(formant, target), -600.0, 600.0) / 1200);
            while (nearest + 1 < marks.size() && std::abs(marks[nearest + 1] - target) < std::abs(marks[nearest] - target)) {
                ++nearest;
            }

            // Calibrate fractional-period bias against phase-aligned marks over a short window.
            const size_t radiusMarks = static_cast<size_t>(std::clamp(rate * 0.022 / sourcePeriod, 2.0, 9.0));
            const size_t firstMark = nearest > radiusMarks ? nearest - radiusMarks : 0;
            const size_t lastMark = std::min(nearest + radiusMarks, marks.size() - 1);
            double expected = 0;
            for (size_t index = firstMark; index < lastMark; ++index) {
                const double center = (marks[index] + marks[index + 1]) * 0.5;
                expected += rate / std::max(67.0, sampleTrack(f0, center));
            }
            const double adjustment = expected > 0 ? (marks[lastMark] - marks[firstMark]) / expected : 1.0;
            const double targetPeriod = sourcePeriod * std::clamp(adjustment, 0.98, 1.02) / ratio;
            const double radius = std::max(sourcePeriod / grainRatio, targetPeriod * 0.73);
            const int low = std::max(begin, static_cast<int>(std::ceil(target - radius)));
            const int high = std::min(end, static_cast<int>(std::ceil(target + radius)));
            for (int i = low; i < high; ++i) {
                const double delta = i - target;
                const float weight = static_cast<float>(0.5 + 0.5 * std::cos(pi * delta / radius));
                const double source = marks[nearest] + delta * grainRatio;
                if (source < begin - initialPeriod || source >= end + initialPeriod || source < 0 || source >= frames) continue;
                for (int c = 0; c < channels; ++c) {
                    wet[i * channels + c] += weight * interpolate(audio, frames, channels, c, source);
                }
                weights[i] += weight;
            }
            target += targetPeriod;
        }
        const double fade = std::max(1.0, rate * 0.012);
        for (int i = begin; i < end; ++i) {
            if (weights[i] < 0.035f) continue;
            const double position = std::min({1.0, (i - begin) / fade, (end - 1 - i) / fade,
                                              sampleTrack(editedRegion.data(), i)});
            const float mix = static_cast<float>(0.5 - 0.5 * std::cos(pi * position));
            for (int c = 0; c < channels; ++c) {
                const int index = i * channels + c;
                out[index] += mix * (wet[index] / weights[i] - audio[index]);
            }
        }
    }
    return out;
}

std::vector<float> resample(const float* audio, int frames, int channels, int sourceRate,
                            int destinationRate, double firstSourceFrame, int outputFrames) {
    std::vector<float> output(outputFrames * channels);
    const double step = static_cast<double>(sourceRate) / destinationRate;
    const double cutoff = std::min(1.0, 1.0 / step) * 0.96;
    const int radius = static_cast<int>(std::ceil(18 / cutoff));
    constexpr int phases = 1024;
    const int width = 2 * radius;
    std::vector<float> kernel((phases + 1) * width);
    for (int phase = 0; phase <= phases; ++phase) {
        const double fraction = static_cast<double>(phase) / phases;
        double sum = 0;
        auto* row = kernel.data() + phase * width;
        for (int k = 0; k < width; ++k) {
            const double x = fraction + radius - 1 - k;
            if (std::abs(x) >= radius) continue;
            const double angle = pi * x;
            const double weight = (std::abs(angle) < 1e-7 ? cutoff : std::sin(angle * cutoff) / angle) *
                (0.42 + 0.5 * std::cos(angle / radius) + 0.08 * std::cos(2 * angle / radius));
            row[k] = static_cast<float>(weight);
            sum += weight;
        }
        for (int k = 0; k < width; ++k) row[k] /= static_cast<float>(sum);
    }
    for (int i = 0; i < outputFrames; ++i) {
        const double p = firstSourceFrame + step * i;
        const int center = static_cast<int>(std::floor(p));
        const int phase = std::clamp(static_cast<int>(std::lround((p - center) * phases)), 0, phases);
        const auto* row = kernel.data() + phase * width;
        for (int k = 0; k < width; ++k) {
            const int at = std::clamp(center - radius + 1 + k, 0, frames - 1);
            for (int c = 0; c < channels; ++c) output[i * channels + c] += row[k] * audio[at * channels + c];
        }
    }
    return output;
}

int spectralFrames(int frames) { return frames / spectralHop + 1; }

std::vector<float> stft(const float* audio, int frames) {
    const auto window = hann();
    std::vector<float> output(spectralFrames(frames) * 2 * bins * 2);
    std::vector<std::complex<float>> spectrum(fftSize);
    for (int t = 0; t < spectralFrames(frames); ++t) {
        const int start = t * spectralHop - fftSize / 2;
        for (int c = 0; c < 2; ++c) {
            for (int i = 0; i < fftSize; ++i) spectrum[i] = audio[2 * reflect(start + i, frames) + c] * window[i];
            fft(spectrum, false);
            for (int b = 0; b < bins; ++b) {
                const int index = ((t * 2 + c) * bins + b) * 2;
                output[index] = spectrum[b].real();
                output[index + 1] = spectrum[b].imag();
            }
        }
    }
    return output;
}

std::vector<float> istft(const float* spectrum, const float* estimate, int frames) {
    const auto window = hann();
    std::vector<float> output(frames * 2);
    std::vector<float> weights(frames);
    std::vector<std::complex<float>> layer(fftSize);
    for (int t = 0; t < spectralFrames(frames); ++t) {
        const int start = t * spectralHop - fftSize / 2;
        const int low = std::max(0, -start);
        const int high = std::min(fftSize, frames - start);
        for (int c = 0; c < 2; ++c) {
            for (int b = 0; b < bins; ++b) {
                const int index = (t * 2 + c) * bins + b;
                const std::complex<float> x(spectrum[index * 2], spectrum[index * 2 + 1]);
                const float magnitude = std::abs(x);
                // Soft mask with the unexplained mixture magnitude as the second source.
                const float vocal = std::clamp(estimate[index], 0.0f, magnitude);
                const float remaining = magnitude - vocal;
                const float mask = vocal * vocal / (vocal * vocal + remaining * remaining + 1e-12f);
                layer[b] = x * mask;
                if (b > 0 && b < fftSize / 2) layer[fftSize - b] = std::conj(layer[b]);
            }
            fft(layer, true);
            for (int i = low; i < high; ++i) output[(start + i) * 2 + c] += layer[i].real() * window[i];
        }
        for (int i = low; i < high; ++i) weights[start + i] += window[i] * window[i];
    }
    for (int i = 0; i < frames; ++i) {
        output[i * 2] /= weights[i];
        output[i * 2 + 1] /= weights[i];
    }
    return output;
}
}
