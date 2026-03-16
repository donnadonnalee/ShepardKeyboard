package jp.example.shepardkeyboard;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.content.Intent;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.material.switchmaterial.SwitchMaterial;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "ShepardPresets";

    private ShepardGenerator.Params params = new ShepardGenerator.Params();
    private int transposeOffset = 0; // -6 to +6
    private TextView tvTranspose;
    private EnvelopeView envelopeView;
    private InterstitialAd mInterstitialAd;

    // Real-time Controls
    private SeekBar seekPitchBend;
    private SeekBar seekModulation;
    private SeekBar seekCenterFreq;
    private SeekBar seekSigma;
    private final Map<Integer, Integer> activePointers = new HashMap<>();
    private final Map<View, Integer> viewToNote = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences globalPrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        params.recordingMode = globalPrefs.getBoolean("global_recording_mode", false);
        params.bendRange = globalPrefs.getFloat("perf_bend_range", 1.0f);
        params.bendSlewRate = globalPrefs.getFloat("perf_bend_slew", 0.5f);
        params.modulationDepth = globalPrefs.getFloat("perf_mod_depth", 0.5f);
        params.modulationRate = globalPrefs.getFloat("perf_mod_rate", 5.0f);
        params.bufferSize = globalPrefs.getInt("perf_buffer_size", 512);
        params.glideTime = globalPrefs.getFloat("perf_glide_time", 0.1f);
        params.isGlideEnabled = globalPrefs.getBoolean("perf_glide_enabled", false);
        params.fixedDuration = globalPrefs.getBoolean("perf_fixed_duration", false);
        params.attackSec = globalPrefs.getFloat("env_attack", 0.05f);
        params.decaySec = globalPrefs.getFloat("env_decay", 0.1f);
        params.sustainLevel = globalPrefs.getFloat("env_sustain_level", 0.7f);
        params.releaseSec = globalPrefs.getFloat("env_release", 0.5f);

        NativeAudioEngine.create(params.recordingMode);
        NativeAudioEngine.setFixedDurationMode(params.fixedDuration);
        NativeAudioEngine.setBufferSize(params.bufferSize);

        tvTranspose = findViewById(R.id.tv_transpose);
        envelopeView = findViewById(R.id.envelope_view);
        envelopeView.setParams(params);

        setupTransposeSeekBar();
        setupRealTimeControls();
        setupKeyboard();

        envelopeView.setOnEnvelopeChangeListener((attack, decay, sustainL, release) -> {
            params.attackSec = attack;
            params.decaySec = decay;
            params.sustainLevel = sustainL;
            params.releaseSec = release;
            updateValueLabels();
            syncNativeParams();
        });

        SwitchMaterial swFixedDuration = findViewById(R.id.sw_fixed_duration);
        swFixedDuration.setChecked(params.fixedDuration);
        swFixedDuration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            params.fixedDuration = isChecked;
            NativeAudioEngine.setFixedDurationMode(isChecked);
            syncNativeParams();
            envelopeView.invalidate();
        });

        SwitchMaterial swGlide = findViewById(R.id.sw_glide);
        swGlide.setChecked(params.isGlideEnabled);
        swGlide.setOnCheckedChangeListener((buttonView, isChecked) -> {
            params.isGlideEnabled = isChecked;
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean("perf_glide_enabled", isChecked).apply();
        });

        findViewById(R.id.ib_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.ib_save).setOnClickListener(v -> showSavePresetDialog());
        findViewById(R.id.ib_load).setOnClickListener(v -> showLoadPresetDialog());

        findViewById(R.id.btn_main_share).setOnClickListener(v -> shareApp());
        findViewById(R.id.btn_main_ads).setOnClickListener(v -> showInterstitialAd());

        MobileAds.initialize(this, initializationStatus -> {
            loadInterstitialAd();
        });

        initDefaultPresets();
        updateValueLabels();
        syncNativeParams();
    }

    private void updateValueLabels() {
        TextView tvCenter = findViewById(R.id.tv_center_freq);
        TextView tvSigmaLabel = findViewById(R.id.tv_sigma);
        TextView tvEnv = findViewById(R.id.tv_env_values);

        if (tvCenter != null)
            tvCenter.setText(String.format("%.0fHz", params.centerFreq));
        if (tvSigmaLabel != null)
            tvSigmaLabel.setText(String.format("%.2f", params.sigma));
        if (tvEnv != null) {
            tvEnv.setText(String.format("A:%.2f D:%.2f S:%.2f R:%.2f",
                    params.attackSec, params.decaySec, params.sustainLevel, params.releaseSec));
        }
    }

    private void initDefaultPresets() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int presetVersion = prefs.getInt("preset_version", 0);

        if (presetVersion < 1) {
            // Standard: Balanced sound
            savePresetInternal("Standard", 0.05, 0.1, 0.7, 0.5, 1.0, 600.0, 1.0);
            // Twinkle: High frequency, sharp attack, short decay/release
            savePresetInternal("Twinkle", 0.01, 0.5, 0.5, 0.4, 0.2, 2000.0, 1.5);
            // Moan: Low frequency, slow attack, long release
            savePresetInternal("Moan", 0.5, 0.5, 0.8, 2.0, 1.0, 150.0, 0.5);
            // Pulsar: Medium frequency, very sharp, very short
            savePresetInternal("Pulsar", 0, 0.05, 0.1, 0.1, 0.1, 440.0, 1.4);
            // Nebula: Wide spectrum, very slow attack, long sustain/release
            savePresetInternal("Nebula", 1.5, 2.0, 0.9, 3.0, 2.0, 300.0, 2.5);

            prefs.edit().putInt("preset_version", 1).apply();
        }
    }

    private void savePresetInternal(String name, double attack, double decay, double sustainL, double release,
            double duration, double center,
            double sigma) {
        try {
            JSONObject json = new JSONObject();
            json.put("attack", attack);
            json.put("decay", decay);
            json.put("sustainLevel", sustainL);
            json.put("release", release);
            json.put("duration", duration);
            json.put("centerFreq", center);
            json.put("sigma", sigma);
            json.put("gain", 0.8);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(name, json.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving default preset", e);
        }
    }

    private void setupTransposeSeekBar() {
        SeekBar seekTranspose = findViewById(R.id.seek_transpose);
        seekTranspose.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                transposeOffset = progress - 6;
                String[] noteNames = { "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };
                // 0 is C. -6 to +6.
                int noteIndex = (transposeOffset + 12) % 12;
                tvTranspose.setText("Key: " + noteNames[noteIndex] + " (" + (transposeOffset >= 0 ? "+" : "")
                        + transposeOffset + ")");

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
            excludeGestures();
        }
    }

    private void excludeGestures() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            View root = findViewById(android.R.id.content);
            root.post(() -> {
                int width = root.getWidth();
                int height = root.getHeight();
                // Exclude the entire screen or at least the edges for the keyboard/sliders
                List<Rect> exclusionRects = new ArrayList<>();
                // Exclude left and right edges (200px each)
                exclusionRects.add(new Rect(0, 0, 200, height));
                exclusionRects.add(new Rect(width - 200, 0, width, height));
                root.setSystemGestureExclusionRects(exclusionRects);
            });
        }
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void setupRealTimeControls() {
        seekPitchBend = findViewById(R.id.seek_pitch_bend);
        seekModulation = findViewById(R.id.seek_modulation);
        seekCenterFreq = findViewById(R.id.main_seek_center);
        seekSigma = findViewById(R.id.main_seek_sigma);

        seekPitchBend.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float bend = (progress - 100) / 100.0f; // -1.0 to 1.0
                NativeAudioEngine.setPitchBend(bend);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(100);
                NativeAudioEngine.setPitchBend(0);
            }
        });

        seekModulation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // Scaling the lever (0-100) by the "Max" depth set in settings
                double scaledDepth = (progress / 100.0) * params.modulationDepth;
                NativeAudioEngine.setPerformanceParams(params.bendRange, params.bendSlewRate, scaledDepth,
                        params.modulationRate, params.glideTime);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekCenterFreq.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                params.centerFreq = progress;
                updateValueLabels();
                syncNativeParams();
                envelopeView.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekSigma.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                params.sigma = Math.max(0.1, progress / 50.0);
                updateValueLabels();
                syncNativeParams();
                envelopeView.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Initial sync
        seekCenterFreq.setProgress((int) params.centerFreq);
        seekSigma.setProgress((int) (params.sigma * 50));
    }

    private void syncNativeParams() {
        double scaledModDepth = (seekModulation.getProgress() / 100.0) * params.modulationDepth;
        NativeAudioEngine.setParams(params.attackSec, params.decaySec, params.sustainLevel, params.durationSec,
                params.releaseSec, params.centerFreq, params.sigma);
        NativeAudioEngine.setPerformanceParams(params.bendRange, params.bendSlewRate, scaledModDepth,
                params.modulationRate, params.glideTime);
        NativeAudioEngine.setBufferSize(params.bufferSize);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupKeyboard() {
        int[] keyIds = {
                R.id.key_c, R.id.key_cs, R.id.key_d, R.id.key_ds, R.id.key_e, R.id.key_f,
                R.id.key_fs, R.id.key_g, R.id.key_gs, R.id.key_a, R.id.key_as, R.id.key_b
        };

        View container = findViewById(R.id.keyboard_container);
        List<View> blackKeys = new ArrayList<>();
        List<View> whiteKeys = new ArrayList<>();
        viewToNote.clear();

        for (int i = 0; i < keyIds.length; i++) {
            View v = findViewById(keyIds[i]);
            viewToNote.put(v, i);
            if (getResources().getResourceEntryName(keyIds[i]).endsWith("s")) {
                blackKeys.add(v);
            } else {
                whiteKeys.add(v);
            }
        }

        container.setOnTouchListener((v, event) -> {
            int actionMasked = event.getActionMasked();
            int pointerIndex = event.getActionIndex();
            int pointerId = event.getPointerId(pointerIndex);

            switch (actionMasked) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_POINTER_DOWN:
                case MotionEvent.ACTION_MOVE:
                    // For ACTION_MOVE, we need to check all active pointers
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        int pid = event.getPointerId(i);
                        float x = event.getX(i);
                        float y = event.getY(i);
                        handleTouch(container, pid, x, y, blackKeys, whiteKeys, viewToNote);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_POINTER_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopTouch(pointerId);
                    updateKeyVisuals(viewToNote, activePointers.values());
                    break;
            }
            return true;
        });
    }

    private void handleTouch(View container, int pointerId, float x, float y, List<View> blackKeys,
            List<View> whiteKeys, Map<View, Integer> viewToNote) {
        View targetKey = null;

        // Check black keys first (top layer)
        for (View v : blackKeys) {
            if (isPointInside(v, x, y)) {
                targetKey = v;
                break;
            }
        }
        // Check white keys if no black key hit
        if (targetKey == null) {
            for (View v : whiteKeys) {
                if (isPointInside(v, x, y)) {
                    targetKey = v;
                    break;
                }
            }
        }

        Integer lastNote = activePointers.get(pointerId);
        if (targetKey != null) {
            int newNoteIndex = viewToNote.get(targetKey);
            int transposedNote = (newNoteIndex + transposeOffset + 12) % 12;

            float volume = y / targetKey.getHeight(); // This Y is relative to container, needs to be relative to key
            // Correcting volume calculation for container-level touch
            float relativeY = y - targetKey.getTop();
            volume = relativeY / targetKey.getHeight();
            volume = Math.max(0.01f, Math.min(1.0f, volume));

            if (lastNote == null || lastNote != transposedNote) {
                if (lastNote != null) {
                    // Update pointers first to correctly check if other fingers are holding the old
                    // note
                    activePointers.remove(pointerId);
                    if (!activePointers.values().contains(lastNote)) {
                        // If glide is enabled, we don't call setNoteOff, we call setNoteOnGlide for the
                        // new note
                        if (params.isGlideEnabled) {
                            NativeAudioEngine.setNoteOnGlide(transposedNote, volume, lastNote);
                        } else {
                            NativeAudioEngine.setNoteOff(lastNote);
                            NativeAudioEngine.setNoteOn(transposedNote, volume);
                        }
                    } else {
                        // Old note still held by another finger, just start new note
                        NativeAudioEngine.setNoteOn(transposedNote, volume);
                    }
                } else {
                    // Fresh touch
                    NativeAudioEngine.setNoteOn(transposedNote, volume);
                }
                activePointers.put(pointerId, transposedNote);
                updateKeyVisuals(viewToNote, activePointers.values());
            } else {
                // Just update volume if changed significantly to avoid jitter
                // and redundant engine calls.
                // Note: v.getTag() could store the last volume if we really wanted to optimize,
                // but let's just do a simple check.
                NativeAudioEngine.setNoteOn(transposedNote, volume);
            }
        } else {
            if (lastNote != null) {
                stopTouch(pointerId);
                updateKeyVisuals(viewToNote, activePointers.values());
            }
        }
    }

    private void stopTouch(int pointerId) {
        Integer note = activePointers.remove(pointerId);
        if (note != null && !activePointers.values().contains(note)) {
            NativeAudioEngine.setNoteOff(note);
        }
    }

    private void updateKeyVisuals(Map<View, Integer> viewToNote, Collection<Integer> currentlyPlaying) {
        for (Map.Entry<View, Integer> entry : viewToNote.entrySet()) {
            View v = entry.getKey();
            int note = (entry.getValue() + transposeOffset + 12) % 12;
            boolean isBlack = getResources().getResourceEntryName(v.getId()).endsWith("s");

            Drawable bg = v.getBackground();
            if (bg != null) {
                if (currentlyPlaying.contains(note)) {
                    bg.setColorFilter(Color.GRAY, PorterDuff.Mode.MULTIPLY);
                } else {
                    bg.clearColorFilter();
                }
            }
        }
    }

    private boolean isPointInside(View v, float x, float y) {
        return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
    }

    private void regenerateSounds() {
        // Redraw only, synthesis is real-time now
        envelopeView.setParams(params);
        updateValueLabels();
        syncNativeParams();
    }

    private void showProgress() {
    }

    private void hideProgress() {
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        SeekBar seekBendRange = dialogView.findViewById(R.id.seek_bend_range);
        SeekBar seekBendSlew = dialogView.findViewById(R.id.seek_bend_slew);
        SeekBar seekModDepth = dialogView.findViewById(R.id.seek_modulation_depth);
        SeekBar seekModRate = dialogView.findViewById(R.id.seek_modulation_rate);
        SeekBar seekBufferSize = dialogView.findViewById(R.id.seek_buffer_size);

        TextView labelBendRange = dialogView.findViewById(R.id.label_bend_range);
        TextView labelBendSlew = dialogView.findViewById(R.id.label_bend_slew);
        TextView labelModDepth = dialogView.findViewById(R.id.label_modulation_depth);
        TextView labelModRate = dialogView.findViewById(R.id.label_modulation_rate);
        TextView labelBufferSize = dialogView.findViewById(R.id.label_buffer_size);
        TextView labelGlideTime = dialogView.findViewById(R.id.label_glide_time);
        SeekBar seekGlideTime = dialogView.findViewById(R.id.seek_glide_time);

        View monoSwitch = dialogView.findViewById(R.id.switch_mono);
        if (monoSwitch != null)
            monoSwitch.setVisibility(View.GONE);

        Switch swRecording = dialogView.findViewById(R.id.switch_recording_mode);
        swRecording.setChecked(params.recordingMode);

        Button btnApply = dialogView.findViewById(R.id.btn_apply);

        // Listeners for labels
        seekBendRange.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelBendRange.setText(String.format("Pitch Bend Range: %.1f semitones", progress * 0.5));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBendSlew.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelBendSlew.setText(String.format("Pitch Bend Smoothing: %d%%", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekModDepth.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelModDepth.setText(String.format("Max Modulation Depth: %.1f semitones", progress / 10.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekModRate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelModRate.setText(String.format("Modulation Rate: %.1f Hz", progress / 10.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBufferSize.setOnSeekBarChangeListener(createSeekListener(labelBufferSize, "Buffer Size (Frames): %d"));
        seekGlideTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelGlideTime.setText(String.format("Glide Time (ms): %d", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Set initial progress
        seekBendRange.setProgress((int) (params.bendRange * 2));
        seekBendSlew.setProgress((int) (params.bendSlewRate * 100));
        seekModDepth.setProgress((int) (params.modulationDepth * 10));
        seekModRate.setProgress((int) (params.modulationRate * 10));
        seekBufferSize.setProgress(params.bufferSize);
        seekGlideTime.setProgress((int) (params.glideTime * 1000));

        // Update labels initially
        labelBendRange.setText(String.format("Pitch Bend Range: %.1f semitones", params.bendRange));
        labelBendSlew.setText(String.format("Pitch Bend Smoothing: %d%%", (int) (params.bendSlewRate * 100)));
        labelModDepth.setText(String.format("Max Modulation Depth: %.1f semitones", params.modulationDepth));
        labelModRate.setText(String.format("Modulation Rate: %.1f Hz", params.modulationRate));
        labelBufferSize.setText(String.format("Buffer Size (Frames): %d", params.bufferSize));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnApply.setOnClickListener(v -> {
            params.bendRange = seekBendRange.getProgress() * 0.5;
            params.bendSlewRate = seekBendSlew.getProgress() / 100.0;
            params.modulationDepth = seekModDepth.getProgress() / 10.0;
            params.modulationRate = seekModRate.getProgress() / 10.0;
            params.bufferSize = Math.max(64, seekBufferSize.getProgress());
            params.glideTime = seekGlideTime.getProgress() / 1000.0;
            params.recordingMode = swRecording.isChecked();

            NativeAudioEngine.setRecordingMode(params.recordingMode);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean("global_recording_mode", params.recordingMode)
                    .putFloat("perf_bend_range", (float) params.bendRange)
                    .putFloat("perf_bend_slew", (float) params.bendSlewRate)
                    .putFloat("perf_mod_depth", (float) params.modulationDepth)
                    .putFloat("perf_mod_rate", (float) params.modulationRate)
                    .putInt("perf_buffer_size", params.bufferSize)
                    .putFloat("perf_glide_time", (float) params.glideTime)
                    .putBoolean("perf_fixed_duration", params.fixedDuration)
                    .putFloat("env_attack", (float) params.attackSec)
                    .putFloat("env_decay", (float) params.decaySec)
                    .putFloat("env_sustain_level", (float) params.sustainLevel)
                    .putFloat("env_release", (float) params.releaseSec)
                    .apply();

            syncNativeParams();
            dialog.dismiss();
        });

        dialog.show();
    }

    private SeekBar.OnSeekBarChangeListener createSeekListener(TextView label, String format) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                label.setText(String.format(format, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    private void showSavePresetDialog() {
        final EditText input = new EditText(this);
        input.setHint("Preset Name");
        new AlertDialog.Builder(this)
                .setTitle("Save Preset")
                .setView(input)
                .setPositiveButton("Save", (dialog, which) -> {
                    String name = input.getText().toString();
                    if (!name.isEmpty()) {
                        savePreset(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void savePreset(String name) {
        try {
            JSONObject json = new JSONObject();
            json.put("attack", params.attackSec);
            json.put("decay", params.decaySec);
            json.put("sustainLevel", params.sustainLevel);
            json.put("release", params.releaseSec);
            json.put("duration", params.durationSec);
            json.put("centerFreq", params.centerFreq);
            json.put("sigma", params.sigma);
            json.put("gain", params.gain);
            json.put("bufferSize", params.bufferSize);
            json.put("recordingMode", params.recordingMode);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(name, json.toString()).apply();
            Toast.makeText(this, "Preset '" + name + "' saved", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving preset", e);
        }
    }

    private void showLoadPresetDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        Map<String, ?> allEntries = prefs.getAll();
        final List<String> names = new ArrayList<>();
        
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof String && ((String) val).trim().startsWith("{")) {
                names.add(entry.getKey());
            }
        }

        if (names.isEmpty()) {
            Toast.makeText(this, "No presets found", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.select_dialog_item, names);
        new AlertDialog.Builder(this)
                .setTitle("Load Preset")
                .setAdapter(adapter, (dialog, which) -> {
                    loadPreset(names.get(which));
                })
                .show();
    }

    private void loadPreset(String name) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String jsonStr = prefs.getString(name, null);
        if (jsonStr != null) {
            try {
                JSONObject json = new JSONObject(jsonStr);
                params.attackSec = json.optDouble("attack", 0.05);
                params.decaySec = json.optDouble("decay", 0.1);
                params.sustainLevel = json.optDouble("sustainLevel", 0.7);
                params.releaseSec = json.optDouble("release", 0.5);
                params.durationSec = json.optDouble("duration", 1.0);
                params.centerFreq = json.optDouble("centerFreq", 600.0);
                params.sigma = json.optDouble("sigma", 1.0);
                params.gain = json.optDouble("gain", 0.8);
                params.bufferSize = json.optInt("bufferSize", 256);

                // Sync UI SeekBars
                if (seekCenterFreq != null)
                    seekCenterFreq.setProgress((int) params.centerFreq);
                if (seekSigma != null)
                    seekSigma.setProgress((int) (params.sigma * 50.0));

                Toast.makeText(this, "Loaded '" + name + "'", Toast.LENGTH_SHORT).show();
                regenerateSounds();
            } catch (JSONException e) {
                Log.e(TAG, "Error loading preset", e);
            }
        }
    }

    private void loadInterstitialAd() {
        AdRequest adRequest = new AdRequest.Builder().build();
        InterstitialAd.load(this, "ca-app-pub-7241606140350447/5614899388", adRequest,
                new InterstitialAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull InterstitialAd interstitialAd) {
                        mInterstitialAd = interstitialAd;
                        Log.i(TAG, "Ad Loaded");
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError loadAdError) {
                        Log.i(TAG, loadAdError.getMessage());
                        mInterstitialAd = null;
                    }
                });
    }

    private void showInterstitialAd() {
        if (mInterstitialAd != null) {
            mInterstitialAd.show(this);
            loadInterstitialAd(); // Load next one
        } else {
            Toast.makeText(this, "Ad is not ready yet", Toast.LENGTH_SHORT).show();
            loadInterstitialAd();
        }
    }

    private void shareApp() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Intent.ACTION_SEND);
        sendIntent.putExtra(Intent.EXTRA_TEXT, "Check out this Shepard Keyboard app!");
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, null);
        startActivity(shareIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NativeAudioEngine.delete();
    }

}