#include "ShepardSynthesizer.hpp"
#include <algorithm>

static const double baseFreqs[] = {
    261.63, 277.18, 293.66, 311.13, 329.63, 349.23,
    369.99, 392.00, 415.30, 440.00, 466.16, 493.88
};

ShepardSynthesizer::ShepardSynthesizer() {
    voices.resize(12);
    for (int i = 0; i < 12; ++i) {
        voices[i].noteIndex = i;
    }
    // Initialize delay buffer for 2 seconds at 48kHz
    delayBuffer.resize((int)(kSampleRate * 2.0), 0.0f);
}

void ShepardSynthesizer::process(float* output, int numFrames) {
    std::lock_guard<std::mutex> lock(voiceMutex);

    for (int i = 0; i < numFrames; ++i) {
        float sample = 0;
        
        // Update LFO for each frame
        double lfoVal = 0;
        if (modEnabled) {
            lfoVal = std::sin(lfoPhase) * modDepth;
            lfoPhase += (kTwoPi * modRate) / kSampleRate;
            if (lfoPhase > kTwoPi) lfoPhase -= kTwoPi;
        }

        // Pitch bend smoothing (Slew rate limited)
        if (pitchEnabled) {
            float diff = targetPitchBend - currentPitchBend;
            float coeff = std::pow(0.1f, (float)bendSlewRate * 5.0f); // coefficient gets very small
            if (bendSlewRate <= 0.05) coeff = 1.0f; // almost instant at low settings
            currentPitchBend += diff * coeff;
        } else {
            currentPitchBend = 0;
        }

        // Octave jump smoothing
        {
            float diff = targetOctaveOffset - currentOctaveOffset;
            float coeff = std::pow(0.1f, (float)octaveSlewRate * 5.0f);
            if (octaveSlewRate <= 0.05) coeff = 1.0f;
            currentOctaveOffset += diff * coeff;
        }

        for (auto& v : voices) {
            if (!v.active && v.envelope <= 0) continue;

            // Timer and auto-release logic
            if (v.active) {
                v.timerSec += 1.0 / kSampleRate;
            }
            // ADSR Envelope logic
            if (v.stage == EnvStage::ATTACK) {
                if (attackSec > 0) {
                    v.envelope += 1.0 / (attackSec * kSampleRate);
                } else {
                    v.envelope = 1.0;
                }
                if (v.envelope >= 1.0) {
                    v.envelope = 1.0;
                    v.stage = EnvStage::DECAY;
                }
            } else if (v.stage == EnvStage::DECAY) {
                if (decaySec > 0) {
                    v.envelope -= (1.0 - sustainLevel) / (decaySec * kSampleRate);
                    if (v.envelope <= sustainLevel) {
                        v.envelope = sustainLevel;
                        v.stage = EnvStage::SUSTAIN;
                    }
                } else {
                    v.envelope = sustainLevel;
                    v.stage = EnvStage::SUSTAIN;
                }
            } else if (v.stage == EnvStage::SUSTAIN) {
                v.envelope = sustainLevel;
                // Auto-trigger release if in fixed duration mode
                if (fixedDurationMode && v.timerSec >= (attackSec + decaySec + fixedSustainSec)) {
                    v.stage = EnvStage::RELEASE;
                    v.autoReleased = true;
                }
            } else if (v.stage == EnvStage::RELEASE) {
                if (releaseSec > 0) {
                    v.envelope -= sustainLevel / (releaseSec * kSampleRate);
                } else {
                    v.envelope = 0;
                }
                if (v.envelope <= 0) {
                    v.envelope = 0;
                    v.stage = EnvStage::IDLE;
                }
            }

            // Frequency for the specific note + pitch bend + LFO modulation
            double targetBaseFreq = baseFreqs[v.noteIndex];
            double currentBaseFreq = targetBaseFreq;

            if (v.isGliding && glideSeconds > 0) {
                double t = v.glideTimer / glideSeconds;
                if (t >= 1.0) {
                    v.isGliding = false;
                } else {
                    // Linear interpolation in log space (ideal for pitch)
                    currentBaseFreq = v.glideStartFreq * std::pow(v.glideTargetFreq / v.glideStartFreq, t);
                    v.glideTimer += 1.0 / kSampleRate;
                }
            }

            double pitchBendFactor = std::pow(2.0, (currentPitchBend * bendRange + lfoVal) / 12.0);
            double currentFreq = currentBaseFreq * pitchBendFactor;
            
            // Shepard calculation: Sum octaves until outside audible range.
            // Using a while loop ensuring we cover the same absolute frequencies 
            // regardless of which base octave the note belongs to.
            double voiceSample = 0;
            
            // Find the lowest audible octave for this specific chroma
            double f = currentFreq;
            while (f > 20.0) f /= 2.0; 
            
            // Smoothed center frequency for octave jump
            double smoothedCenterFreq = centerFreq * std::pow(2.0, currentOctaveOffset);

            // Sum all octaves from lowest to highest audible
            while (f < 22050.0) {
                // Gaussian weighting in log2 scale
                double log2Freq = std::log2(f / smoothedCenterFreq);
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

        // Apply Overdrive (Soft clipping - Enhanced)
        if (driveEnabled && drive > 0) {
            // Increased multiplier for "Gyan Gyan" distortion
            float preGain = 1.0f + (float)drive * 40.0f; 
            sample *= preGain;
            // Soft clipping using tanh-like curve: x / (1 + |x|)
            sample = sample / (1.0f + std::abs(sample));
        }

        // Apply Delay
        if (delayEnabled && delayWet > 0) {
            int delaySamples = (int)(delayTime * kSampleRate);
            int readIndex = writeIndex - delaySamples;
            if (readIndex < 0) readIndex += delayBuffer.size();

            float delayedSample = delayBuffer[readIndex];
            
            // Mix delay back into buffer with feedback
            delayBuffer[writeIndex] = sample + delayedSample * (float)delayFeedback;
            
            // Output mix
            sample = sample * (1.0f - (float)delayWet) + delayedSample * (float)delayWet;
        } else {
            delayBuffer[writeIndex] = sample;
        }

        if (writeIndex >= delayBuffer.size()) writeIndex = 0;

        // Apply Resonant Filter (Trapezoidal SVF - Andrew Simper)
        if (filterEnabled) {
            // Parameter smoothing
            currentFilterCutoff += (filterCutoff - currentFilterCutoff) * 0.01;
            currentFilterResonance += (filterResonance - currentFilterResonance) * 0.01;

            double g = std::tan(M_PI * currentFilterCutoff / kSampleRate);
            double k = 1.0 / std::max(0.1, currentFilterResonance);
            double a1 = 1.0 / (1.0 + g * (g + k));
            double a2 = g * a1;
            double a3 = g * a2;

            double v3 = sample - z2;
            double v1 = a1 * z1 + a2 * v3;
            double v2 = z2 + a2 * z1 + a3 * v3;
            z1 = 2.0 * v1 - z1;
            z2 = 2.0 * v2 - z2;
            
            // Check for NaN/Inf stability and reset if necessary
            if (!(std::isfinite(z1) && std::isfinite(z2))) {
                z1 = 0.0;
                z2 = 0.0;
            }
            
            sample = (float)v2; // v2 is the Lowpass output
        }

        output[i] = sample;
    }
}

void ShepardSynthesizer::noteOn(int noteIndex, float volume, bool slideFromOld, int oldNoteIndex) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    if (noteIndex < 0 || noteIndex >= 12) return;

    if (slideFromOld && oldNoteIndex >= 0 && oldNoteIndex < 12 && voices[oldNoteIndex].active) {
        // Glide logic: Move state from old voice to new one
        Voice& oldV = voices[oldNoteIndex];
        Voice& newV = voices[noteIndex];

        // Only move if the new note is NOT already playing as a primary note
        if (!newV.active) {
            newV.active = true;
            newV.phase = oldV.phase;
            // inherits envelope/timer from old voice
            newV.stage = oldV.stage; 
            if (newV.stage == EnvStage::IDLE) newV.stage = EnvStage::ATTACK;
            
            newV.isGliding = true;
            newV.glideStartFreq = baseFreqs[oldNoteIndex];
            newV.glideTargetFreq = baseFreqs[noteIndex];
            newV.glideTimer = 0.0;
            
            // Turn off old voice immediately and silence its envelope
            // to prevent the release tail from overlapping with the glide.
            oldV.active = false;
            oldV.envelope = 0;
            oldV.stage = EnvStage::IDLE;
            return;
        }
    }

    // Only reset phase and timer if the note was not already active (new key press).
    if (!voices[noteIndex].active) {
        if (voices[noteIndex].envelope <= 0) {
            voices[noteIndex].phase = 0;
        }
        voices[noteIndex].timerSec = 0;
        voices[noteIndex].autoReleased = false;
        voices[noteIndex].isGliding = false;
        voices[noteIndex].stage = EnvStage::ATTACK;
    }
    voices[noteIndex].active = true;
    voices[noteIndex].volume = volume;
    // Ensure stage is ATTACK if re-triggered, but prevent re-triggering 
    // if we are just updating volume for an already-autorelased note in fixed mode.
    if (voices[noteIndex].stage == EnvStage::RELEASE || voices[noteIndex].stage == EnvStage::IDLE) {
        if (!fixedDurationMode || !voices[noteIndex].autoReleased) {
            voices[noteIndex].stage = EnvStage::ATTACK;
            voices[noteIndex].autoReleased = false; 
        }
    }
}

void ShepardSynthesizer::noteOff(int noteIndex) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    voices[noteIndex].active = false;
    if (voices[noteIndex].stage != EnvStage::IDLE) {
        voices[noteIndex].stage = EnvStage::RELEASE;
    }
}

void ShepardSynthesizer::allNotesOff() {
    std::lock_guard<std::mutex> lock(voiceMutex);
    for (auto& v : voices) {
        v.active = false;
        if (v.stage != EnvStage::IDLE) v.stage = EnvStage::RELEASE;
    }
}

void ShepardSynthesizer::setParams(double attack, double decay, double sustainL, double sustainDur, double release, double cf, double s) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    attackSec = attack;
    decaySec = decay;
    sustainLevel = sustainL;
    fixedSustainSec = sustainDur;
    releaseSec = release;
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
    targetPitchBend = bend;
}

void ShepardSynthesizer::setPerformanceParams(double br, double bs, double md, double mr, double gt) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    bendRange = br;
    bendSlewRate = bs;
    modDepth = md;
    modRate = mr;
    glideSeconds = gt;
}

void ShepardSynthesizer::setFixedDurationMode(bool enabled) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    fixedDurationMode = enabled;
}

void ShepardSynthesizer::setDrive(double d) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    drive = d;
}

void ShepardSynthesizer::setDelay(double time, double feedback, double wet) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    delayTime = std::max(0.01, std::min(2.0, time));
    delayFeedback = std::max(0.0, std::min(0.95, feedback));
    delayWet = std::max(0.0, std::min(1.0, wet));
}

void ShepardSynthesizer::setEffectsEnabled(bool p, bool m, bool dr, bool de, bool f) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    pitchEnabled = p;
    modEnabled = m;
    driveEnabled = dr;
    delayEnabled = de;
    filterEnabled = f;
}

void ShepardSynthesizer::setFilter(double cutoff, double resonance) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    // Limit cutoff to safe range for basic SVF
    filterCutoff = std::max(20.0, std::min(kSampleRate * 0.35, cutoff));
    filterResonance = std::max(0.1, std::min(5.0, resonance));
}

void ShepardSynthesizer::setOctaveOffset(float offset) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    targetOctaveOffset = offset;
}

void ShepardSynthesizer::setOctaveSlewRate(double slewRate) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    octaveSlewRate = slewRate;
}

void ShepardSynthesizer::getEnvelopeLevels(float* levels) {
    std::lock_guard<std::mutex> lock(voiceMutex);
    for (int i = 0; i < 12; ++i) {
        // Return the combined amplitude of the envelope and the base volume
        levels[i] = (float)(voices[i].envelope * voices[i].volume);
    }
}
