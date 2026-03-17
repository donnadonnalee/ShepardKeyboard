package jp.example.shepardkeyboard;

public class NativeAudioEngine {
    static {
        System.loadLibrary("shepard_engine");
    }

    public static native void create(boolean recordingMode);
    public static native void delete();
    public static native void setNoteOn(int noteIndex, float volume);
    public static native void setNoteOnGlide(int noteIndex, float volume, int oldNoteIndex);
    public static native void setNoteOff(int noteIndex);
    public static native void setAllNotesOff();
    public static native void setParams(double attack, double decay, double sustainLevel, double sustainDuration, double release, double centerFreq, double sigma);
    public static native void setPerformanceParams(double bendRange, double bendSlewRate, double modDepth, double modRate, double glideTime);
    public static native void setFixedDurationMode(boolean enabled);
    public static native void setRecordingMode(boolean enabled);
    public static native void setModulation(double depth, double rate);
    public static native void setPitchBend(float bend); // -1.0 to 1.0
    public static native void setDrive(double drive);
    public static native void setDelay(double time, double feedback, double wet);
    public static native void setEffectsEnabled(boolean pitch, boolean mod, boolean drive, boolean delay, boolean filter);
    public static native void setFilter(double cutoff, double resonance);
    public static native void setBufferSize(int frames);
}
