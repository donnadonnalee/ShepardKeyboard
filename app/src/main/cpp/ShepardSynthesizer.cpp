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
    delayBuffer.resize((int)(sampleRate * 2.0), 0.0f);
    updateFilterCoefficients();
}

void ShepardSynthesizer::setSampleRate(double rate) {
    if (rate <= 0) return;
    std::lock_guard<std::mutex> lock(voiceMutex);
    sampleRate = rate;
    // Resize delay buffer if needed
    delayBuffer.assign((int)(sampleRate * 2.0), 0.0f);
    writeIndex = 0;
    updateFilterCoefficients();
}

void ShepardSynthesizer::updateFilterCoefficients() {
    // This is called outside the process loop, or once per sample if cutoff changes.
    // However, to avoid tan() in the inner loop, we can calculate it only when needed.
    
    // 1. Resonance Capping: Reduce effective resonance as cutoff increases above 5kHz
    // to prevent "ear-piercing" high frequency peaks.
    double resCap = 1.0;
    if (currentFilterCutoff > 5000.0) {
        // Linearly decrease resonance from 100% at 5kHz to ~30% at 15kHz
        resCap = std::max(0.3, 1.0 - (currentFilterCutoff - 5000.0) / 15000.0);
    }
    double effectiveRes = currentFilterResonance * resCap;

    coeff_g = std::tan(M_PI * currentFilterCutoff / sampleRate);
    coeff_k = 1.0 / std::max(0.1, effectiveRes);
    coeff_a1 = 1.0 / (1.0 + coeff_g * (coeff_g + coeff_k));
    coeff_a2 = coeff_g * coeff_a1;
    coeff_a3 = coeff_g * coeff_a2;
}
void ShepardSynthesizer::process(float* output, int numFrames) {
    std::lock_guard<std::mutex> lock(voiceMutex);

    const double invSampleRate = 1.0 / sampleRate;
    const double invTwoSigmaSq = 1.0 / (2.0 * std::max(0.01, sigma * sigma));

    for (int i = 0; i < numFrames; ++i) {
        float sample = 0;
        
        // Update LFO for each frame
        double lfoVal = 0;
        if (modEnabled) {
            lfoVal = std::sin(lfoPhase) * modDepth;
            lfoPhase += (kTwoPi * modRate) * invSampleRate;
            if (lfoPhase > kTwoPi) lfoPhase -= kTwoPi;
        }

        // Pitch bend smoothing
        if (pitchEnabled) {
            float diff = targetPitchBend - currentPitchBend;
            float coeff = std::pow(0.1f, (float)bendSlewRate * 5.0f);
            if (bendSlewRate <= 0.05) coeff = 1.0f;
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

        double pitchBendFactor = std::pow(2.0, (currentPitchBend * bendRange + lfoVal) / 12.0);
        double smoothedCenterFreq = centerFreq * std::pow(2.0, currentOctaveOffset);

        for (auto& v : voices) {
            if (v.stage == EnvStage::IDLE) continue;

            // Timer and auto-release logic
            if (v.active) {
                v.timerSec += invSampleRate;
            }
            // ADSR Envelope logic
            if (v.stage == EnvStage::ATTACK) {
                if (attackSec > 0) {
                    v.envelope += invSampleRate / attackSec;
                } else {
                    v.envelope = 1.0;
                }
                if (v.envelope >= 1.0) {
                    v.envelope = 1.0;
                    v.stage = v.active ? EnvStage::DECAY : EnvStage::RELEASE;
                }
            } else if (v.stage == EnvStage::DECAY) {
                if (decaySec > 0) {
                    v.envelope -= (1.0 - sustainLevel) * invSampleRate / decaySec;
                    if (v.envelope <= sustainLevel) {
                        v.envelope = sustainLevel;
                        v.stage = v.active ? EnvStage::SUSTAIN : EnvStage::RELEASE;
                    }
                } else {
                    v.envelope = sustainLevel;
                    v.stage = v.active ? EnvStage::SUSTAIN : EnvStage::RELEASE;
                }
            } else if (v.stage == EnvStage::SUSTAIN) {
                v.envelope = sustainLevel;
                if (!v.active || (fixedDurationMode && v.timerSec >= (attackSec + decaySec + fixedSustainSec))) {
                    v.stage = EnvStage::RELEASE;
                    if (fixedDurationMode && v.active) v.autoReleased = true;
                }
            } else if (v.stage == EnvStage::RELEASE) {
                if (releaseSec > 0) {
                    double releaseSlope = std::max(sustainLevel, 0.2) * invSampleRate / releaseSec;
                    v.envelope -= releaseSlope;
                } else {
                    v.envelope = 0;
                }
                if (v.envelope <= 0) {
                    v.envelope = 0;
                    v.stage = EnvStage::IDLE;
                }
            }

            // Frequency for the specific note
            double currentBaseFreq = baseFreqs[v.noteIndex];

            if (v.isGliding && glideSeconds > 0) {
                double t = v.glideTimer / glideSeconds;
                if (t >= 1.0) {
                    v.isGliding = false;
                } else {
                    currentBaseFreq = v.glideStartFreq * std::pow(v.glideTargetFreq / v.glideStartFreq, t);
                    v.glideTimer += invSampleRate;
                }
            }

            double currentFreq = currentBaseFreq * pitchBendFactor;
            double voiceSample = 0;
            
            // Start from the lowest audible octave
            double f = currentFreq;
            while (f > 20.0) f *= 0.5; 
            
            // Optimization: Pre-calculate the starting log-frequency
            double log2Freq = std::log2(f / smoothedCenterFreq);
            double phaseMul = f / currentFreq;

            // Sum all octaves
            while (f < 22010.0) {
                // Gaussian weighting (optimized)
                double weight = std::exp(-(log2Freq * log2Freq) * invTwoSigmaSq);
                voiceSample += weight * std::sin(v.phase * phaseMul);
                
                f *= 2.0;
                log2Freq += 1.0;
                phaseMul *= 2.0;
            }
            
            v.phase += kTwoPi * currentFreq * invSampleRate;
            const double wrapPoint = kTwoPi * 1024.0; 
            if (v.phase > wrapPoint) v.phase -= wrapPoint;

            sample += (float)(voiceSample * v.envelope * v.volume * 0.05);
        }

        // Apply Overdrive (Refined with Gain Compensation)
        if (driveEnabled && drive > 0) {
            // 1. Boost input gain
            float preGain = 1.0f + (float)drive * 15.0f; 
            sample *= preGain;
            
            // 2. Soft-saturation curve (x / (1 + |x|))
            // This compresses the signal into the [-1, 1] range smoothly.
            sample = sample / (1.0f + std::abs(sample));
            
            // 3. Gain Compensation: Reduce output to maintain perceived loudness
            // As drive increases, we lower the final level.
            float postGain = 1.0f / (1.0f + (float)drive * 2.5f);
            sample *= postGain;
        }

        // Apply Delay
        if (delayEnabled && delayWet > 0) {
            int delaySamples = (int)(delayTime * sampleRate);
            int readIndex = writeIndex - delaySamples;
            if (readIndex < 0) readIndex += delayBuffer.size();

            float delayedSample = delayBuffer[readIndex];
            delayBuffer[writeIndex] = sample + delayedSample * (float)delayFeedback;
            sample = sample * (1.0f - (float)delayWet) + delayedSample * (float)delayWet;
        } else {
            delayBuffer[writeIndex] = sample;
        }

        if (++writeIndex >= delayBuffer.size()) writeIndex = 0;

        // Apply Resonant Filter (Optimized)
        if (filterEnabled) {
            // Cutoff smoothing
            double prevCutoff = currentFilterCutoff;
            currentFilterCutoff += (filterCutoff - currentFilterCutoff) * 0.01;
            currentFilterResonance += (filterResonance - currentFilterResonance) * 0.01;

            // Update coefficients only if parameters changed significantly
            if (std::abs(currentFilterCutoff - prevCutoff) > 0.1) {
                updateFilterCoefficients();
            }

            double v3 = sample - z2;
            double v1 = coeff_a1 * z1 + coeff_a2 * v3;
            
            // Non-linear saturation in the feedback path (v1)
            // This tames resonance peaks and adds "analog warmth".
            // Soft-clipping using x / (1 + |x|)
            v1 = v1 / (1.0 + std::abs(v1));

            double v2 = z2 + coeff_a2 * z1 + coeff_a3 * v3;
            z1 = 2.0 * v1 - z1;
            z2 = 2.0 * v2 - z2;
            
            if (!(std::isfinite(z1) && std::isfinite(z2))) {
                z1 = 0.0;
                z2 = 0.0;
            }
            sample = (float)v2;
        }

        // Simple Peak Limiter at the very end
        float absSample = std::abs(sample);
        if (absSample > kLimiterThresh) {
            float targetGain = kLimiterThresh / absSample;
            // Immediate attack for peaks
            if (targetGain < limiterGain) limiterGain = targetGain;
        }
        // Release gain slowly
        limiterGain += (1.0f - limiterGain) * kLimiterRelease;
        sample *= limiterGain;

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
            
            int diff = noteIndex - oldNoteIndex;
            if (diff > 6) diff -= 12;
            else if (diff < -6) diff += 12;

            newV.isGliding = true;
            newV.glideStartFreq = baseFreqs[oldNoteIndex];
            newV.glideTargetFreq = baseFreqs[oldNoteIndex] * std::pow(2.0, diff / 12.0);
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
    
    // To solve the "skipped note" issue in high-latency recording mode,
    // we let voices finish their ATTACK and DECAY phases if they were already triggered,
    // even if the finger is released early. This ensures the note is audible at its peak.
    // The process() loop will transition to RELEASE as soon as it sees !active 
    // at the end of the Current phase.
    if (voices[noteIndex].stage == EnvStage::SUSTAIN) {
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
    filterCutoff = std::max(20.0, std::min(sampleRate * 0.45, cutoff));
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
