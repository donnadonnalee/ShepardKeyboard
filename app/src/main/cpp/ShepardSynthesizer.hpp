#ifndef SHEPARD_SYNTHESIZER_H
#define SHEPARD_SYNTHESIZER_H

#include <vector>
#include <cmath>
#include <mutex>

struct Voice {
    bool active = false;
    double phase = 0.0;
    double envelope = 0.0;
    float volume = 1.0f;
    int noteIndex = 0;
    double timerSec = 0.0; // Tracks elapsed time since noteOn
    bool autoReleased = false; // True if fixed duration exceeded
};

class ShepardSynthesizer {
public:
    ShepardSynthesizer();
    void process(float* output, int numFrames);
    void noteOn(int noteIndex, float volume);
    void noteOff(int noteIndex);
    void allNotesOff();
    void setParams(double attack, double release, double sustain, double centerFreq, double sigma);
    void setModulation(double depth, double rate);
    void setPitchBend(float bend);
    void setFixedDurationMode(bool enabled);

private:
    std::vector<Voice> voices;
    std::mutex voiceMutex;

    double attackSec = 0.05;
    double releaseSec = 0.5;
    double sustainSec = 0.2; // Only used in fixedDurationMode
    bool fixedDurationMode = false;
    double centerFreq = 440.0;
    double sigma = 1.0;
    float pitchBend = 0.0f; // in semitones
    
    double lfoPhase = 0.0;
    double modDepth = 0.0; // in semitones
    double modRate = 5.0;  // in Hz

    static constexpr double kSampleRate = 48000.0;
    static constexpr double kTwoPi = 6.283185307179586;
};

#endif
