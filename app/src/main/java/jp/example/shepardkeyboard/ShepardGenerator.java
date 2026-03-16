package jp.example.shepardkeyboard;

import android.util.Log;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ShepardGenerator {
    private static final String TAG = "ShepardGenerator";
    private static final int SAMPLE_RATE = 44100;
    private static final double TWO_PI = 2.0 * Math.PI;
    
    public interface ProgressListener {
        void onProgress(double progress);
    }


    public static class Params {
        public double attackSec = 0.05;
        public double releaseSec = 0.5;
        public double durationSec = 1.0;
        public double centerFreq = 440.0;
        public double sigma = 1.0; 
        public double gain = 0.8;
    }

    public static byte[] generateNote(double frequency, Params params, ProgressListener listener) {

        double attack = params.attackSec;
        double sustain = params.durationSec;
        double release = params.releaseSec;
        double totalTime = attack + sustain + release;
        
        int numSamples = (int) (SAMPLE_RATE * totalTime);
        short[] pcm = new short[numSamples];
        
        double maxAmplitude = 0;

        for (int i = 0; i < numSamples; i++) {
            double t = (double) i / SAMPLE_RATE;
            double sample = 0;


            if (listener != null && i % 1000 == 0) {
                listener.onProgress((double) i / numSamples);
            }

            for (int oct = -4; oct <= 4; oct++) {

                double f = frequency * Math.pow(2, oct);
                if (f < 20 || f > 20000) continue;

                double weight = Math.exp(-Math.pow(Math.log(f / params.centerFreq) / Math.log(2), 2) / (2 * Math.pow(params.sigma, 2)));
                sample += weight * Math.sin(TWO_PI * f * t);
            }

            sample *= params.gain;

            double envelope = 0;
            if (t < attack) {
                envelope = (attack > 0) ? t / attack : 1.0;
            } else if (t < attack + sustain) {
                envelope = 1.0;
            } else {
                double releaseT = t - (attack + sustain);
                envelope = (release > 0) ? Math.max(0, 1.0 - (releaseT / release)) : 0;
            }

            double finalSample = sample * envelope;
            if (Math.abs(finalSample) > maxAmplitude) maxAmplitude = Math.abs(finalSample);
            
            // Prevent wrap-around by explicitly clamping the value before casting to short.
            // Also increase the divisor slightly for safer headroom.
            double scaledSample = finalSample * 32767.0 / 4.0; 
            if (scaledSample > 32767) scaledSample = 32767;
            if (scaledSample < -32768) scaledSample = -32768;
            
            pcm[i] = (short) scaledSample; 
        }

        Log.d(TAG, "Generated note: f=" + frequency + ", peakAmp=" + maxAmplitude + ", samples=" + numSamples);

        ByteBuffer buffer = ByteBuffer.allocate(pcm.length * 2);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        for (short s : pcm) {
            buffer.putShort(s);
        }
        return buffer.array();
    }
}
