#ifndef SHEPARD_SYNTHESIZER_H
#define SHEPARD_SYNTHESIZER_H

#include <vector>
#include <cmath>
#include <mutex>

enum class EnvStage {
    IDLE,
    ATTACK,
    DECAY,
    SUSTAIN,
    RELEASE
};

class ShepardSynthesizer {
public:
    ShepardSynthesizer();
    void process(float* output, int numFrames);
    void noteOn(int noteIndex, float volume);
    void noteOff(int noteIndex);
    void allNotesOff();
    void setParams(double attack, double decay, double sustainLevel, double sustainDuration, double release, double centerFreq, double sigma);
    void setPerformanceParams(double bendRange, double bendSlewRate, double modDepth, double modRate, double glideTime);
    void setModulation(double depth, double rate);
    void setPitchBend(float bend); // -1.0 to 1.0
    void setFixedDurationMode(bool enabled);
    void setDrive(double drive);
    void setDelay(double time, double feedback, double wet);
    void setFilter(double cutoff, double resonance);
    void setEffectsEnabled(bool pitch, bool mod, bool drive, bool delay, bool filter);
    
    // Updated noteOn to support slides
    void noteOn(int noteIndex, float volume, bool slideFromOld = false, int oldNoteIndex = -1);

private:
    struct Voice {
        bool active = false;
        double phase = 0.0;
        double envelope = 0.0;
        float volume = 1.0f;
        int noteIndex = 0;
        double timerSec = 0.0;
        bool autoReleased = false;
        
        bool isGliding = false;
        double glideStartFreq = 0.0;
        double glideTargetFreq = 0.0;
        double glideTimer = 0.0;

        EnvStage stage = EnvStage::IDLE;
    };
    
    std::vector<Voice> voices;
    std::mutex voiceMutex;

    double attackSec = 0.05;
    double decaySec = 0.1;
    double sustainLevel = 0.7;
    double releaseSec = 0.5;
    double fixedSustainSec = 0.2; 
    bool fixedDurationMode = false;
    double centerFreq = 440.0;
    double sigma = 1.0;
    
    float targetPitchBend = 0.0f; 
    float currentPitchBend = 0.0f; 
    double bendRange = 2.0;       
    double bendSlewRate = 0.95;   
    double glideSeconds = 0.1;
    
    double lfoPhase = 0.0;
    double modDepth = 0.0; // in semitones
    double modRate = 5.0;  // in Hz
    double drive = 0.0;    // 0.0 to 1.0

    // Delay parameters
    double delayTime = 0.5;
    double delayFeedback = 0.5;
    double delayWet = 0.0;
    std::vector<float> delayBuffer;
    int writeIndex = 0;

    bool pitchEnabled = true;
    bool modEnabled = true;
    bool driveEnabled = true;
    bool delayEnabled = true;
    bool filterEnabled = true;

    // Filter parameters & state (State Variable Filter)
    double filterCutoff = 20000.0;
    double filterResonance = 0.707;
    double svf_low = 0.0;
    double svf_band = 0.0;
    static constexpr double kSampleRate = 48000.0;
    static constexpr double kTwoPi = 6.283185307179586;
};

#endif
