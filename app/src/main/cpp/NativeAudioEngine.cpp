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
        // Pre-allocate scratch buffer for mono synthesis (max expected 2048 frames)
        scratchBuffer.resize(2048);
    }

    void start() {
        oboe::AudioStreamBuilder builder;
        
        // Force OpenSL ES and Standard Performance Mode for Recording Mode.
        // This ensures the stream goes through the system mixer and is capturable by screen recorders,
        // bypassing the "MMAP" path that AAudio uses on Pixel devices.
        if (recordingMode) {
            builder.setAudioApi(oboe::AudioApi::OpenSLES)
                   ->setPerformanceMode(oboe::PerformanceMode::None)
                   ->setSharingMode(oboe::SharingMode::Shared)
                   ->setUsage(oboe::Usage::Media)
                   ->setContentType(oboe::ContentType::Music);
        } else {
            builder.setAudioApi(oboe::AudioApi::AAudio) // Prefer AAudio for performance
                   ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                   ->setSharingMode(oboe::SharingMode::Exclusive);
        }

        builder.setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo) // Always Stereo for consistency
            ->setDataCallback(this)
            ->setSampleRate(48000)
            ->setAllowedCapturePolicy(oboe::AllowedCapturePolicy::All);

        builder.openStream(stream);
        
        if (stream) {
            __android_log_print(ANDROID_LOG_INFO, TAG, "Stream opened: API=%s, Mode=%s, Channels=Stereo",
                               (stream->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL"),
                               (stream->getSharingMode() == oboe::SharingMode::Shared ? "Shared" : "Exclusive"));
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

        if (numFrames > scratchBuffer.size()) {
            scratchBuffer.resize(numFrames); // Audio thread resize - slightly risky but only happens if buffer size increases
        }

        // 1. Process mono signal into scratch buffer
        synth->process(scratchBuffer.data(), numFrames);

        // 2. Distribute mono signal to Stereo output (L and R)
        for (int i = 0; i < numFrames; ++i) {
            float sample = scratchBuffer[i];
            output[i * 2] = sample;     // Left
            output[i * 2 + 1] = sample; // Right
        }

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
    void setDrive(double d) { synth->setDrive(d); }
    void setDelay(double t, double f, double w) { synth->setDelay(t, f, w); }
    void setFilter(double c, double r) { synth->setFilter(c, r); }
    void setEffectsEnabled(bool p, bool m, bool dr, bool de, bool f) { synth->setEffectsEnabled(p, m, dr, de, f); }
    void setOctaveOffset(float o) { synth->setOctaveOffset(o); }
    void setOctaveSlewRate(double s) { synth->setOctaveSlewRate(s); }
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
    void getEnvelopeLevels(float* levels) {
        if (synth) synth->getEnvelopeLevels(levels);
    }

private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::unique_ptr<ShepardSynthesizer> synth;
    std::vector<float> scratchBuffer;
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
Java_jp_example_shepardkeyboard_NativeAudioEngine_setDrive(JNIEnv *env, jclass clazz, jdouble drive) {
    if (engine) engine->setDrive(drive);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setDelay(JNIEnv *env, jclass clazz, jdouble time, jdouble feedback, jdouble wet) {
    if (engine) engine->setDelay(time, feedback, wet);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setEffectsEnabled(JNIEnv *env, jclass clazz, jboolean pitch, jboolean mod, jboolean drive, jboolean delay, jboolean filter) {
    if (engine) engine->setEffectsEnabled(pitch, mod, drive, delay, filter);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setFilter(JNIEnv *env, jclass clazz, jdouble cutoff, jdouble resonance) {
    if (engine) engine->setFilter(cutoff, resonance);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setOctaveOffset(JNIEnv *env, jclass clazz, jfloat offset) {
    if (engine) engine->setOctaveOffset(offset);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setOctaveSlewRate(JNIEnv *env, jclass clazz, jfloat slew_rate) {
    if (engine) engine->setOctaveSlewRate(slew_rate);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setBufferSize(JNIEnv *env, jclass clazz, jint frames) {
    if (engine) engine->setBufferSize(frames);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_getEnvelopeLevels(JNIEnv *env, jclass clazz, jfloatArray levels) {
    if (engine) {
        jfloat *cLevels = env->GetFloatArrayElements(levels, nullptr);
        engine->getEnvelopeLevels(cLevels);
        env->ReleaseFloatArrayElements(levels, cLevels, 0);
    }
}

}
