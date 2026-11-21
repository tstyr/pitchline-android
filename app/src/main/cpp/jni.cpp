#include "dsp.h"
#include <jni.h>
#include <new>
#include <stdexcept>

namespace {
class Floats {
public:
    Floats(JNIEnv* env, jfloatArray array) : env_(env), array_(array), values_(env->GetFloatArrayElements(array, nullptr)) {
        if (!values_) throw std::bad_alloc();
    }
    ~Floats() { env_->ReleaseFloatArrayElements(array_, values_, JNI_ABORT); }
    const float* data() const { return values_; }
    int length() const { return env_->GetArrayLength(array_); }
private:
    JNIEnv* env_;
    jfloatArray array_;
    float* values_;
};

template <typename Processor>
jfloatArray run(JNIEnv* env, Processor process) {
    try {
        const auto output = process();
        const auto result = env->NewFloatArray(static_cast<jsize>(output.size()));
        if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(output.size()), output.data());
        return result;
    } catch (const std::bad_alloc&) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "DSP memory");
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
    }
    return nullptr;
}
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pitchline_android_NativeDsp_detect(JNIEnv* env, jobject, jfloatArray input, jint channels, jint rate,
                                            jint hop, jint first, jint count) {
    return run(env, [&] {
        Floats pcm(env, input);
        const auto pitch = pitchline::detect(pcm.data(), pcm.length() / channels, channels, rate, hop, first, count);
        std::vector<float> output;
        output.reserve(2 * count);
        for (const auto& frame : pitch) { output.push_back(frame.hz); output.push_back(frame.confidence); }
        return output;
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pitchline_android_NativeDsp_resynthesize(JNIEnv* env, jobject, jfloatArray input, jint channels, jint rate,
                                                  jint hop, jint origin, jfloatArray f0, jfloatArray cents, jfloatArray formant) {
    return run(env, [&] {
        Floats pcm(env, input), pitch(env, f0), edit(env, cents), tone(env, formant);
        return pitchline::resynthesize(pcm.data(), pcm.length() / channels, channels, rate, hop, origin,
                                       pitch.data(), edit.data(), tone.data(), pitch.length());
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pitchline_android_NativeDsp_resample(JNIEnv* env, jobject, jfloatArray input, jint channels,
                                              jint sourceRate, jint destinationRate, jdouble first, jint outputFrames) {
    return run(env, [&] {
        Floats pcm(env, input);
        return pitchline::resample(pcm.data(), pcm.length() / channels, channels, sourceRate, destinationRate, first, outputFrames);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pitchline_android_NativeDsp_stft(JNIEnv* env, jobject, jfloatArray input) {
    return run(env, [&] {
        Floats pcm(env, input);
        return pitchline::stft(pcm.data(), pcm.length() / 2);
    });
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_pitchline_android_NativeDsp_istft(JNIEnv* env, jobject, jfloatArray spectrum, jfloatArray estimates, jint frames) {
    return run(env, [&] {
        Floats mix(env, spectrum), vocals(env, estimates);
        return pitchline::istft(mix.data(), vocals.data(), frames);
    });
}
