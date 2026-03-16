#include <jni.h>
#include <oboe/Oboe.h>
#include "ShepardSynthesizer.hpp"
#include <memory>
#include <android/log.h>

#define TAG "NativeAudioEngine"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine(bool initialRecordingMode) : recordingMode(initialRecordingMode) {
        synth = std::make_unique<ShepardSynthesizer>();
    }

    void start() {
        oboe::AudioStreamBuilder builder;
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(recordingMode ? oboe::SharingMode::Shared : oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setDataCallback(this)
            ->setSampleRate(48000);

        if (recordingMode) {
            builder.setAllowedCapturePolicy(oboe::AllowedCapturePolicy::All)
                   ->setUsage(oboe::Usage::Media)
                   ->setContentType(oboe::ContentType::Music);
        }

        builder.openStream(stream);
        
        if (stream) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Stream opened: Mode=%s, Policy=%d, Usage=%d",
                               recordingMode ? "Shared (Recording)" : "Exclusive",
                               (int)stream->getAllowedCapturePolicy(),
                               (int)stream->getUsage());
            stream->requestStart();
        }
    }

    void stop() {
        if (stream) {
            stream->stop();
            stream->close();
            stream.reset();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        float *output = static_cast<float *>(audioData);
        synth->process(output, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void noteOn(int index, float vol, bool slide = false, int oldIdx = -1) { synth->noteOn(index, vol, slide, oldIdx); }
    void noteOff(int index) { synth->noteOff(index); }
    void allNotesOff() { synth->allNotesOff(); }
    void setParams(double a, double d, double sl, double sd, double r, double cf, double s) { synth->setParams(a, d, sl, sd, r, cf, s); }
    void setPerformanceParams(double br, double bs, double md, double mr, double gt) { synth->setPerformanceParams(br, bs, md, mr, gt); }
    void setModulation(double d, double r) { synth->setModulation(d, r); }
    void setPitchBend(float b) { synth->setPitchBend(b); }
    void setFixedDurationMode(bool enabled) { synth->setFixedDurationMode(enabled); }
    void setRecordingMode(bool enabled) {
        if (recordingMode != enabled) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Restarting stream for RecordingMode=%s", enabled ? "ON" : "OFF");
            stop();
            recordingMode = enabled;
            start();
        }
    }
    void setBufferSize(int frames) {
        if (stream) {
            stream->setBufferSizeInFrames(frames);
        }
    }

private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::unique_ptr<ShepardSynthesizer> synth;
    bool recordingMode = false;
};

static AudioEngine* engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_create(JNIEnv *env, jclass clazz, jboolean recording_mode) {
    if (engine == nullptr) {
        engine = new AudioEngine(recording_mode);
        engine->start();
    }
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_delete(JNIEnv *env, jclass clazz) {
    if (engine != nullptr) {
        engine->stop();
        delete engine;
        engine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setNoteOn(JNIEnv *env, jclass clazz, jint note_index, jfloat volume) {
    if (engine) engine->noteOn(note_index, volume);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setNoteOnGlide(JNIEnv *env, jclass clazz, jint note_index, jfloat volume, jint old_note_index) {
    if (engine) engine->noteOn(note_index, volume, true, old_note_index);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setNoteOff(JNIEnv *env, jclass clazz, jint note_index) {
    if (engine) engine->noteOff(note_index);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setAllNotesOff(JNIEnv *env, jclass clazz) {
    if (engine) engine->allNotesOff();
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setParams(JNIEnv *env, jclass clazz, jdouble attack, jdouble decay, jdouble sustain_level, jdouble sustain_duration, jdouble release, jdouble center_freq, jdouble sigma) {
    if (engine) engine->setParams(attack, decay, sustain_level, sustain_duration, release, center_freq, sigma);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setPerformanceParams(JNIEnv *env, jclass clazz, jdouble bend_range, jdouble bend_slew, jdouble mod_depth, jdouble mod_rate, jdouble glide_time) {
    if (engine) engine->setPerformanceParams(bend_range, bend_slew, mod_depth, mod_rate, glide_time);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setFixedDurationMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    if (engine) engine->setFixedDurationMode(enabled);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setRecordingMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    if (engine) engine->setRecordingMode(enabled);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setModulation(JNIEnv *env, jclass clazz, jdouble depth, jdouble rate) {
    if (engine) engine->setModulation(depth, rate);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setPitchBend(JNIEnv *env, jclass clazz, jfloat bend) {
    if (engine) engine->setPitchBend(bend);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setBufferSize(JNIEnv *env, jclass clazz, jint frames) {
    if (engine) engine->setBufferSize(frames);
}

}
