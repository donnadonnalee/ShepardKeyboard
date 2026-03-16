package jp.example.shepardkeyboard;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SoundManager {
    private static final String TAG = "SoundManager";
    
    public interface ProgressListener {
        void onProgress(int noteIndex, double noteProgress);
    }

    private SoundPool soundPool;
    private final Map<Integer, Integer> noteToSoundId = new HashMap<>();
    private final Set<Integer> loadedSoundIds = new HashSet<>();
    private Integer lastStreamId = null;
    private boolean isMonophonic = false;
    private final Context context;

    public SoundManager(Context context) {
        this.context = context;
        initSoundPool();
    }

    private void initSoundPool() {
        if (soundPool != null) {
            soundPool.release();
        }

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(12)
                .setAudioAttributes(audioAttributes)
                .build();

        soundPool.setOnLoadCompleteListener((soundPool1, sampleId, status) -> {
            if (status == 0) {
                Log.d(TAG, "Sound loaded successfully: " + sampleId);
                loadedSoundIds.add(sampleId);
            } else {
                Log.e(TAG, "Failed to load sound: " + sampleId + " (status=" + status + ")");
            }
        });
    }

    public void setMonophonic(boolean monophonic) {
        this.isMonophonic = monophonic;
    }

    public void loadNotes(ShepardGenerator.Params params, ProgressListener listener) {

        if (soundPool == null) {
            Log.w(TAG, "soundPool was null, re-initializing...");
            initSoundPool();
        }
        
        // Use a local reference to avoid NPE if another thread calls release()
        SoundPool currentPool = soundPool;
        if (currentPool == null) {
            Log.e(TAG, "Failed to initialize SoundPool");
            return;
        }

        loadedSoundIds.clear();
        for (int soundId : noteToSoundId.values()) {
            try {
                currentPool.unload(soundId);
            } catch (Exception e) {
                Log.e(TAG, "Error unloading " + soundId, e);
            }
        }
        noteToSoundId.clear();

        double[] baseFreqs = {
                261.63, 277.18, 293.66, 311.13, 329.63, 349.23,
                369.99, 392.00, 415.30, 440.00, 466.16, 493.88
        };

        for (int i = 0; i < 12; i++) {
            final int noteIndex = i;
            byte[] pcmData = ShepardGenerator.generateNote(baseFreqs[i], params, progress -> {
                if (listener != null) {
                    listener.onProgress(noteIndex, progress);
                }
            });
            try {

                File wavFile = new File(context.getCacheDir(), "note_" + i + ".wav");
                writeWavHeader(wavFile, pcmData);
                
                int soundId = currentPool.load(wavFile.getAbsolutePath(), 1);
                noteToSoundId.put(i, soundId);
                Log.d(TAG, "Requested load for note " + i + ", soundId=" + soundId + ", path=" + wavFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Error saving note " + i, e);
            }
        }
    }

    private void writeWavHeader(File file, byte[] pcmData) throws IOException {
        FileOutputStream out = new FileOutputStream(file);
        int totalDataLen = pcmData.length;
        int totalFileLen = totalDataLen + 36;
        int sampleRate = 44100;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        byte[] header = new byte[44];
        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalFileLen & 0xff);
        header[5] = (byte) ((totalFileLen >> 8) & 0xff);
        header[6] = (byte) ((totalFileLen >> 16) & 0xff);
        header[7] = (byte) ((totalFileLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0; // PCM
        header[22] = (byte) (channels & 0xff); header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) (blockAlign & 0xff); header[33] = 0;
        header[34] = (byte) (bitsPerSample & 0xff); header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (totalDataLen & 0xff);
        header[41] = (byte) ((totalDataLen >> 8) & 0xff);
        header[42] = (byte) ((totalDataLen >> 16) & 0xff);
        header[43] = (byte) ((totalDataLen >> 24) & 0xff);

        out.write(header);
        out.write(pcmData);
        out.flush();
        out.close();
        
        Log.d(TAG, "Wrote WAV file: " + file.getName() + ", size=" + file.length());
    }

    public void playNote(int noteIndex, float volume) {
        SoundPool currentPool = soundPool;
        if (currentPool == null) {
            Log.e(TAG, "playNote called but soundPool is null");
            return;
        }

        Integer soundId = noteToSoundId.get(noteIndex);
        if (soundId != null) {
            if (!loadedSoundIds.contains(soundId)) {
                Log.w(TAG, "Playing note " + noteIndex + " but soundId " + soundId + " not loaded yet.");
            }
            if (isMonophonic && lastStreamId != null) {
                currentPool.stop(lastStreamId);
            }
            int streamId = currentPool.play(soundId, volume, volume, 1, 0, 1.0f);
            if (streamId == 0) {
                Log.e(TAG, "soundPool.play failed (returned 0) for soundId=" + soundId);
            } else {
                Log.d(TAG, "Playing note " + noteIndex + ", soundId=" + soundId + ", streamId=" + streamId + ", vol=" + volume);
                lastStreamId = streamId;
            }
        } else {
            Log.e(TAG, "No soundId found for note index " + noteIndex);
        }
    }

    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
