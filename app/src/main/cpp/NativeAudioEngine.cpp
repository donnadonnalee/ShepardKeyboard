#include <jni.h>
#include <oboe/Oboe.h>
#include "ShepardSynthesizer.hpp"
#include <memory>
#include <android/log.h>

#define TAG "NativeAudioEngine"

class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine() {
        synth = std::make_unique<ShepardSynthesizer>();
    }

    void start() {
        oboe::AudioStreamBuilder builder;
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setDataCallback(this)
            ->setSampleRate(48000)
            ->openStream(stream);
        
        if (stream) {
            stream->requestStart();
        }
    }

    void stop() {
        if (stream) {
            stream->stop();
            stream->close();
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        float *output = static_cast<float *>(audioData);
        synth->process(output, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void noteOn(int index, float vol) { synth->noteOn(index, vol); }
    void noteOff(int index) { synth->noteOff(index); }
    void allNotesOff() { synth->allNotesOff(); }
    void setParams(double a, double r, double sus, double cf, double s) { synth->setParams(a, r, sus, cf, s); }
    void setModulation(double d, double r) { synth->setModulation(d, r); }
    void setPitchBend(float b) { synth->setPitchBend(b); }
    void setFixedDurationMode(bool enabled) { synth->setFixedDurationMode(enabled); }
    void setBufferSize(int frames) {
        if (stream) {
            stream->setBufferSizeInFrames(frames);
        }
    }

private:
    std::shared_ptr<oboe::AudioStream> stream;
    std::unique_ptr<ShepardSynthesizer> synth;
};

static AudioEngine* engine = nullptr;

extern "C" {

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_create(JNIEnv *env, jclass clazz) {
    if (engine == nullptr) {
        engine = new AudioEngine();
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
Java_jp_example_shepardkeyboard_NativeAudioEngine_setNoteOff(JNIEnv *env, jclass clazz, jint note_index) {
    if (engine) engine->noteOff(note_index);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setAllNotesOff(JNIEnv *env, jclass clazz) {
    if (engine) engine->allNotesOff();
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setParams(JNIEnv *env, jclass clazz, jdouble attack, jdouble release, jdouble sustain, jdouble center_freq, jdouble sigma) {
    if (engine) engine->setParams(attack, release, sustain, center_freq, sigma);
}

JNIEXPORT void JNICALL
Java_jp_example_shepardkeyboard_NativeAudioEngine_setFixedDurationMode(JNIEnv *env, jclass clazz, jboolean enabled) {
    if (engine) engine->setFixedDurationMode(enabled);
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
