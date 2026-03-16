#include "ShepardSynthesizer.hpp"
#include <algorithm>

ShepardSynthesizer::ShepardSynthesizer() {
    voices.resize(12);
    for (int i = 0; i < 12; ++i) {
        voices[i].noteIndex = i;
    }
}

void ShepardSynthesizer::process(float* output, int numFrames) {
    std::lock_guard<std::mutex> lock(voiceMutex);

    double baseFreqs[] = {
        261.63, 277.18, 293.66, 311.13, 329.63, 349.23,
        369.99, 392.00, 415.30, 440.00, 466.16, 493.88
    };

    for (int i = 0; i < numFrames; ++i) {
        float sample = 0;
        
        // Update LFO for each frame
        double lfoVal = std::sin(lfoPhase) * modDepth;
        lfoPhase += (kTwoPi * modRate) / kSampleRate;
        if (lfoPhase > kTwoPi) lfoPhase -= kTwoPi;

        for (auto& v : voices) {
            if (!v.active && v.envelope <= 0) continue;

            // Timer and auto-release logic
            if (v.active) {
                v.timerSec += 1.0 / kSampleRate;
                if (fixedDurationMode && !v.autoReleased) {
                    if (v.timerSec >= (attackSec + sustainSec)) {
                        v.autoReleased = true;
                    }
                }
            }

            // Envelope logic
            bool effectivelyActive = v.active && !v.autoReleased;
            if (effectivelyActive) {
                if (attackSec > 0) {
                    v.envelope += 1.0 / (attackSec * kSampleRate);
                } else {
                    v.envelope = 1.0;
                }
                if (v.envelope > 1.0) v.envelope = 1.0;
            } else {
                if (releaseSec > 0) {
                    v.envelope -= 1.0 / (releaseSec * kSampleRate);
                } else {
                    v.envelope = 0;
                }
                if (v.envelope < 0) v.envelope = 0;
            }

            // Frequency for the specific note + pitch bend + LFO modulation
            double baseFreq = baseFreqs[v.noteIndex];
// ... rest of process ...
            double pitchBendFactor = std::pow(2.0, (pitchBend + lfoVal) / 12.0);
            double currentFreq = baseFreq * pitchBendFactor;
            
            // Shepard calculation: Sum octaves until outside audible range.
            // Using a while loop ensuring we cover the same absolute frequencies 
            // regardless of which base octave the note belongs to.
            double voiceSample = 0;
            
            // Find the lowest audible octave for this specific chroma
            double f = currentFreq;
            while (f > 20.0) f /= 2.0; 
            
            // Sum all octaves from lowest to highest audible
            while (f < 22050.0) {
                // Gaussian weighting in log2 scale
                double log2Freq = std::log2(f / centerFreq);
                double weight = std::exp(-std::pow(log2Freq, 2.0) / (2.0 * std::pow(sigma, 2.0)));
                
                // Use the accumulated phase. The ratio f/currentFreq gives the octave multiplier.
                // Since f is always an octave multiple of currentFreq, this is coherent.
                voiceSample += weight * std::sin(v.phase * (f / currentFreq));
                f *= 2.0;
            }
            
            // Phase accumulation includes pitch bend for continuity
            v.phase += kTwoPi * currentFreq / kSampleRate;
            // Wrap at a large multiple to prevent phase jumps in sub-octaves
            const double wrapPoint = kTwoPi * 1024.0; 
            if (v.phase > wrapPoint) v.phase -= wrapPoint;

            sample += (float)(voiceSample * v.envelope * v.volume * 0.05); // Lower gain for summing many octaves
        }
        output[i] = sample;
    }
}

void ShepardSynthesizer::noteOn(int noteIndex, float volume) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    // Reset phase and timer only if note was not already active
    if (!voices[noteIndex].active && voices[noteIndex].envelope <= 0) {
        voices[noteIndex].phase = 0;
    }
    voices[noteIndex].timerSec = 0;
    voices[noteIndex].autoReleased = false;
    voices[noteIndex].active = true;
    voices[noteIndex].volume = volume;
}

void ShepardSynthesizer::noteOff(int noteIndex) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    voices[noteIndex].active = false;
}

void ShepardSynthesizer::allNotesOff() {
    std::lock_guard<std::mutex> lock(voiceMutex);
    for (auto& v : voices) v.active = false;
}

void ShepardSynthesizer::setParams(double attack, double release, double sustain, double cf, double s) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    attackSec = attack;
    releaseSec = release;
    sustainSec = sustain;
    centerFreq = cf;
    sigma = s;
}

void ShepardSynthesizer::setModulation(double depth, double rate) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    modDepth = depth;
    modRate = rate;
}

void ShepardSynthesizer::setPitchBend(float bend) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    pitchBend = bend * 2.0f; // +/- 2 semitones
}

void ShepardSynthesizer::setFixedDurationMode(bool enabled) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    fixedDurationMode = enabled;
}
