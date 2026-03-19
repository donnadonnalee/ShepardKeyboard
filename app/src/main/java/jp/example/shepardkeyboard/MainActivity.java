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
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.media.MediaPlayer;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
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
    private SeekBar seekPitchBend, seekModulation, seekDrive, seekOctaveJump;
    private SeekBar seekPitchBendCompact, seekModulationCompact, seekDriveCompact, seekOctaveJumpCompact;
    private ImageButton ibCollapse;
    private View compactControls, dashboard;
    private boolean isCollapsed = false;
    private XYPadView padOscillator, padFilter;
    private TextView tvCenterFreq, tvSigma, tvDriveValue, tvFilterCutoff, tvFilterResonance;
    private final Map<Integer, Integer> activePointers = new HashMap<>();
    private final Map<View, Integer> viewToNote = new HashMap<>();

    // Audio Player
    private MediaPlayer mediaPlayer;
    private View layoutAudioPlayer;
    private ImageButton ibAudioPlayPause, ibAudioStop;
    private TextView tvAudioName, tvAudioTime;
    private SeekBar seekAudioPosition;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private final Runnable audioUpdater = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                int current = mediaPlayer.getCurrentPosition();
                seekAudioPosition.setProgress(current);
                tvAudioTime.setText(formatTime(current) + "/" + formatTime(mediaPlayer.getDuration()));
                audioHandler.postDelayed(this, 500);
            }
        }
    };

    private final ActivityResultLauncher<String[]> audioPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> {
                if (uri != null) {
                    loadAudio(uri);
                }
            });

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
        params.drive = globalPrefs.getFloat("perf_drive", 0.0f);
        params.delayTime = globalPrefs.getFloat("perf_delay_time", 0.5f);
        params.delayFeedback = globalPrefs.getFloat("perf_delay_feedback", 0.5f);
        params.delayWet = globalPrefs.getFloat("perf_delay_wet", 0.0f);
        params.isPitchEnabled = globalPrefs.getBoolean("perf_pitch_enabled", true);
        params.isModEnabled = globalPrefs.getBoolean("perf_mod_enabled", true);
        params.isDriveEnabled = globalPrefs.getBoolean("perf_drive_enabled", true);
        params.isDelayEnabled = globalPrefs.getBoolean("perf_delay_enabled", true);
        params.driveLimit = globalPrefs.getFloat("perf_drive_limit", 1.0f);
        params.isFilterEnabled = globalPrefs.getBoolean("perf_filter_enabled", true);
        params.filterCutoff = globalPrefs.getFloat("filter_cutoff", 1000.0f);
        params.filterResonance = globalPrefs.getFloat("filter_resonance", 1.0f);
        params.isOctaveEnabled = globalPrefs.getBoolean("perf_octave_enabled", true);
        params.octaveSlewRate = globalPrefs.getFloat("perf_octave_slew", 0.5f);
        params.verticalControlParam = globalPrefs.getInt("perf_vertical_control", 0);

        NativeAudioEngine.create(params.recordingMode);
        NativeAudioEngine.setFixedDurationMode(params.fixedDuration);
        NativeAudioEngine.setBufferSize(params.bufferSize);

        tvTranspose = findViewById(R.id.tv_transpose);
        envelopeView = findViewById(R.id.envelope_view);
        envelopeView.setParams(params);

        setupTransposeSeekBar();
        setupRealTimeControls();
        setupKeyboard();
        setupAudioPlayerUI();

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

        ibCollapse = findViewById(R.id.ib_collapse);
        dashboard = findViewById(R.id.dashboard);
        compactControls = findViewById(R.id.compact_controls);
        ibCollapse.setOnClickListener(v -> toggleDashboard());

        MobileAds.initialize(this, initializationStatus -> {
            loadInterstitialAd();
        });

        initDefaultPresets();
        updateValueLabels();
        updateEffectVisibility();
        syncNativeParams();
    }

    private void toggleDashboard() {
        isCollapsed = !isCollapsed;

        dashboard.setVisibility(isCollapsed ? View.GONE : View.VISIBLE);
        compactControls.setVisibility(isCollapsed ? View.VISIBLE : View.GONE);
        ibCollapse.setImageResource(isCollapsed ? R.drawable.ic_expand : R.drawable.ic_collapse);

        androidx.constraintlayout.widget.ConstraintLayout keyboardContainer = findViewById(R.id.keyboard_container);
        androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp = (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) keyboardContainer.getLayoutParams();

        if (isCollapsed) {
            lp.topToBottom = R.id.compact_controls;
            lp.matchConstraintPercentHeight = 0.70f;

            // Sync from main to compact
            seekOctaveJumpCompact.setProgress(seekOctaveJump.getProgress());
            seekPitchBendCompact.setProgress(seekPitchBend.getProgress());
            seekModulationCompact.setProgress(seekModulation.getProgress());
            seekDriveCompact.setProgress(seekDrive.getProgress());
        } else {
            lp.topToBottom = R.id.dashboard;
            lp.matchConstraintPercentHeight = 0.45f;

            // Sync from compact to main
            seekOctaveJump.setProgress(seekOctaveJumpCompact.getProgress());
            seekPitchBend.setProgress(seekPitchBendCompact.getProgress());
            seekModulation.setProgress(seekModulationCompact.getProgress());
            seekDrive.setProgress(seekDriveCompact.getProgress());
        }
        keyboardContainer.setLayoutParams(lp);
    }

    private void updateEffectVisibility() {
        View pitchContainer = findViewById(R.id.performance_pitch_container);
        View modContainer = findViewById(R.id.performance_mod_container);
        View driveContainer = findViewById(R.id.performance_drive_container);
        View filterContainer = findViewById(R.id.filter_container);

        if (pitchContainer != null)
            pitchContainer.setVisibility(params.isPitchEnabled ? View.VISIBLE : View.GONE);
        if (modContainer != null)
            modContainer.setVisibility(params.isModEnabled ? View.VISIBLE : View.GONE);
        if (driveContainer != null)
            driveContainer.setVisibility(params.isDriveEnabled ? View.VISIBLE : View.GONE);
        if (filterContainer != null)
            filterContainer.setVisibility(params.isFilterEnabled ? View.VISIBLE : View.GONE);
        View octaveContainer = findViewById(R.id.performance_octave_container);
        if (octaveContainer != null)
            octaveContainer.setVisibility(params.isOctaveEnabled ? View.VISIBLE : View.GONE);
    }

    private void updateValueLabels() {
        if (tvCenterFreq != null)
            tvCenterFreq.setText(String.format("%.0fHz", params.centerFreq));
        if (tvSigma != null)
            tvSigma.setText(String.format("%.2f", params.sigma));
        if (tvFilterCutoff != null)
            tvFilterCutoff.setText(String.format("FLT:%.0fHz", params.filterCutoff));
        if (tvFilterResonance != null)
            tvFilterResonance.setText(String.format("Q:%.1f", params.filterResonance));
        if (tvDriveValue != null)
            tvDriveValue.setText(String.format("%d%%", (int) (params.drive * 100)));
        TextView tvEnv = findViewById(R.id.tv_env_values);
        if (tvEnv != null) {
            tvEnv.setText(String.format("A:%.2f D:%.2f S:%.2f R:%.2f",
                    params.attackSec, params.decaySec, params.sustainLevel, params.releaseSec));
        }
    }

    private void initDefaultPresets() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int presetVersion = prefs.getInt("preset_version", 0);

        if (presetVersion < 2) {
            // Standard: Balanced sound
            savePresetInternal("Standard", 0.05, 0.1, 0.7, 0.5, 1.0, 600.0, 1.0, 0.0, 0.5, 0.5, 0.0);
            // Twinkle: High frequency, sharp attack, short decay/release
            savePresetInternal("Twinkle", 0.01, 0.5, 0.5, 0.4, 0.2, 2000.0, 1.5, 0.0, 0.5, 0.5, 0.0);
            // Moan: Low frequency, slow attack, long release
            savePresetInternal("Moan", 0.5, 0.5, 0.8, 2.0, 1.0, 150.0, 0.5, 0.0, 0.5, 0.5, 0.0);
            // Pulsar: Medium frequency, very sharp, very short
            savePresetInternal("Pulsar", 0, 0.05, 0.1, 0.1, 0.1, 440.0, 1.4, 0.0, 0.5, 0.5, 0.0);
            // Nebula: Wide spectrum, very slow attack, long sustain/release
            savePresetInternal("Nebula", 1.5, 2.0, 0.9, 3.0, 2.0, 300.0, 2.5, 0.0, 0.8, 0.7, 0.3);
            // Overdrive: High drive, sharp attack
            savePresetInternal("Overdrive", 0.01, 0.1, 0.7, 0.5, 1.0, 440.0, 1.0, 0.9, 0.5, 0.5, 0.0);

            prefs.edit().putInt("preset_version", 2).apply();
        }
    }

    private void savePresetInternal(String name, double attack, double decay, double sustainL, double release,
            double duration, double center,
            double sigma, double drive, double dTime, double dFeedback, double dWet) {
        try {
            JSONObject json = new JSONObject();
            json.put("attack", attack);
            json.put("decay", decay);
            json.put("sustainLevel", sustainL);
            json.put("release", release);
            json.put("duration", duration);
            json.put("centerFreq", center);
            json.put("sigma", sigma);
            json.put("drive", drive);
            json.put("delayTime", dTime);
            json.put("delayFeedback", dFeedback);
            json.put("delayWet", dWet);
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
        seekDrive = findViewById(R.id.seek_drive);
        seekOctaveJump = findViewById(R.id.seek_octave_jump);

        seekPitchBendCompact = findViewById(R.id.seek_pitch_bend_compact);
        seekModulationCompact = findViewById(R.id.seek_modulation_compact);
        seekDriveCompact = findViewById(R.id.seek_drive_compact);
        seekOctaveJumpCompact = findViewById(R.id.seek_octave_jump_compact);

        padOscillator = findViewById(R.id.pad_oscillator);
        padFilter = findViewById(R.id.pad_filter);

        tvCenterFreq = findViewById(R.id.tv_center_freq);
        tvSigma = findViewById(R.id.tv_sigma);
        tvFilterCutoff = findViewById(R.id.tv_filter_cutoff);
        tvFilterResonance = findViewById(R.id.tv_filter_resonance);
        tvDriveValue = findViewById(R.id.tv_drive_value);

        padOscillator.setLabels("WIDTH", "FREQ");
        padFilter.setLabels("RES", "CUTOFF");

        seekPitchBend.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekPitchBendCompact.setProgress(progress);
                }
                float bend = (progress - 100) / 100.0f; // -1.0 to 1.0
                NativeAudioEngine.setPitchBend(bend);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(100);
                seekPitchBendCompact.setProgress(100);
                NativeAudioEngine.setPitchBend(0);
            }
        });

        seekPitchBendCompact.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekPitchBend.setProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(100);
                seekPitchBend.setProgress(100);
                NativeAudioEngine.setPitchBend(0);
            }
        });

        seekOctaveJump.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekOctaveJumpCompact.setProgress(progress);
                }
                float offset = (float) (progress - 1); // -1.0, 0.0, 1.0
                NativeAudioEngine.setOctaveOffset(offset);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(1);
                seekOctaveJumpCompact.setProgress(1);
                NativeAudioEngine.setOctaveOffset(0);
            }
        });

        seekOctaveJumpCompact.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekOctaveJump.setProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekBar.setProgress(1);
                seekOctaveJump.setProgress(1);
                NativeAudioEngine.setOctaveOffset(0);
            }
        });

        seekModulation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekModulationCompact.setProgress(progress);
                }
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

        seekModulationCompact.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekModulation.setProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekDrive.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekDriveCompact.setProgress(progress);
                }
                params.drive = progress / 100.0;
                if (tvDriveValue != null)
                    tvDriveValue.setText(progress + "%");
                NativeAudioEngine.setDrive(params.drive * params.driveLimit);
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putFloat("perf_drive", (float) params.drive)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekDriveCompact.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    seekDrive.setProgress(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        padOscillator.setOnXYChangedListener((x, y) -> {
            // Y: Frequency (200 to 2000, exponential?)
            params.centerFreq = 200.0 * Math.pow(10, y); // 200 to 2000
            // X: Sigma (0.1 to 3.0)
            params.sigma = 0.1 + (x * 2.9);
            updateValueLabels();
            syncNativeParams();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putFloat("osc_center", (float) params.centerFreq)
                    .putFloat("osc_sigma", (float) params.sigma)
                    .apply();
        });

        padFilter.setOnXYChangedListener((x, y) -> {
            // Y: Cutoff (30Hz to 15kHz, exponential)
            params.filterCutoff = 30.0 * Math.pow(500, y);
            // X: Resonance (0.5 to 4.0) - User requested halving from 8.0
            params.filterResonance = 0.5 + (x * 3.5);
            updateValueLabels();
            syncNativeParams();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putFloat("filter_cutoff", (float) params.filterCutoff)
                    .putFloat("filter_resonance", (float) params.filterResonance)
                    .apply();
        });

        // Initial sync
        seekDrive.setProgress((int) (params.drive * 100));

        // Initial XY Pad positions
        float freqY = (float) (Math.log10(params.centerFreq / 200.0));
        float sigmaX = (float) ((params.sigma - 0.1) / 2.9);
        padOscillator.setValues(sigmaX, freqY);

        float cutoffY = (float) (Math.log(params.filterCutoff / 30.0) / Math.log(500.0));
        float resX = (float) ((params.filterResonance - 0.5) / 3.5);
        padFilter.setValues(resX, cutoffY);
    }

    private void setupAudioPlayerUI() {
        layoutAudioPlayer = findViewById(R.id.layout_audio_player);
        ibAudioPlayPause = findViewById(R.id.ib_audio_play_pause);
        ibAudioStop = findViewById(R.id.ib_audio_stop);
        tvAudioName = findViewById(R.id.tv_audio_name);
        tvAudioTime = findViewById(R.id.tv_audio_time);
        seekAudioPosition = findViewById(R.id.seek_audio_position);

        ibAudioPlayPause.setOnClickListener(v -> toggleAudioPlayback());
        ibAudioStop.setOnClickListener(v -> stopAudioPlayback());

        seekAudioPosition.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && mediaPlayer != null) {
                    mediaPlayer.seekTo(progress);
                    tvAudioTime.setText(formatTime(progress) + "/" + formatTime(mediaPlayer.getDuration()));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void toggleAudioPlayback() {
        if (mediaPlayer == null)
            return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            ibAudioPlayPause.setImageResource(R.drawable.ic_play);
            audioHandler.removeCallbacks(audioUpdater);
        } else {
            mediaPlayer.start();
            ibAudioPlayPause.setImageResource(R.drawable.ic_pause);
            audioHandler.post(audioUpdater);
        }
    }

    private void stopAudioPlayback() {
        if (mediaPlayer != null) {
            mediaPlayer.pause();
            mediaPlayer.seekTo(0);
            ibAudioPlayPause.setImageResource(R.drawable.ic_play);
            seekAudioPosition.setProgress(0);
            tvAudioTime.setText(formatTime(0) + "/" + formatTime(mediaPlayer.getDuration()));
            audioHandler.removeCallbacks(audioUpdater);
        }
    }

    private void loadAudio(Uri uri) {
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, uri);
            mediaPlayer.prepare();
            mediaPlayer.setOnCompletionListener(mp -> {
                ibAudioPlayPause.setImageResource(R.drawable.ic_play);
                audioHandler.removeCallbacks(audioUpdater);
                seekAudioPosition.setProgress(mp.getDuration());
            });

            String fileName = getFileName(uri);
            tvAudioName.setText(fileName);
            seekAudioPosition.setMax(mediaPlayer.getDuration());
            seekAudioPosition.setProgress(0);
            tvAudioTime.setText("00:00/" + formatTime(mediaPlayer.getDuration()));

            layoutAudioPlayer.setVisibility(View.VISIBLE);
            ibAudioPlayPause.setImageResource(R.drawable.ic_play);
        } catch (Exception e) {
            Log.e(TAG, "Error loading audio", e);
            Toast.makeText(this, "Failed to load audio", Toast.LENGTH_SHORT).show();
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index != -1)
                        result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1)
                result = result.substring(cut + 1);
        }
        return result;
    }

    private String formatTime(int ms) {
        int seconds = (ms / 1000) % 60;
        int minutes = (ms / (1000 * 60)) % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    private void syncNativeParams() {
        double scaledModDepth = (seekModulation.getProgress() / 100.0) * params.modulationDepth;
        NativeAudioEngine.setParams(params.attackSec, params.decaySec, params.sustainLevel, params.durationSec,
                params.releaseSec, params.centerFreq, params.sigma);
        NativeAudioEngine.setPerformanceParams(params.bendRange, params.bendSlewRate, scaledModDepth,
                params.modulationRate, params.glideTime);
        NativeAudioEngine.setDrive(params.drive * params.driveLimit);
        NativeAudioEngine.setDelay(params.delayTime, params.delayFeedback, params.delayWet);
        NativeAudioEngine.setFilter(params.filterCutoff, params.filterResonance);
        NativeAudioEngine.setEffectsEnabled(params.isPitchEnabled, params.isModEnabled, params.isDriveEnabled,
                params.isDelayEnabled, params.isFilterEnabled);
        NativeAudioEngine.setOctaveSlewRate((float) params.octaveSlewRate);
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
                    // For DOWN, we just handle the new pointer (it might be the highest)
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        float ty = event.getY(i);
                        // We recalculate highest for all pointers even on DOWN to be safe
                    }
                    // Fall through to MOVE logic which handles all pointers
                case MotionEvent.ACTION_MOVE:
                    // For ACTION_MOVE, we need to check all active pointers
                    float minY = Float.MAX_VALUE;
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        float y = event.getY(i);
                        if (y < minY)
                            minY = y;
                    }

                    for (int i = 0; i < event.getPointerCount(); i++) {
                        int pid = event.getPointerId(i);
                        float x = event.getX(i);
                        float y = event.getY(i);
                        handleTouch(container, pid, x, y, blackKeys, whiteKeys, viewToNote, y == minY);
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
            List<View> whiteKeys, Map<View, Integer> viewToNote, boolean isHighest) {
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

            float relativeY = y - targetKey.getTop();
            float normY = relativeY / targetKey.getHeight();
            normY = Math.max(0.0f, Math.min(1.0f, normY));

            float volume = 1.0f;
            if (params.verticalControlParam == 0) {
                // Volume: Higher = smaller
                volume = normY;
                volume = Math.max(0.01f, volume);
            } else if (params.verticalControlParam == 1 && isHighest) {
                // Modulation: Higher = stronger
                float modRaw = 1.0f - normY;
                double scaledDepth = modRaw * params.modulationDepth;
                NativeAudioEngine.setModulation(scaledDepth, params.modulationRate);
            } else if (params.verticalControlParam == 2 && isHighest) {
                // LPF: Higher = higher cutoff
                float lpfRaw = 1.0f - normY;
                // Sweep log from 100Hz to 15kHz
                double cutoff = 100.0 * Math.pow(150.0, lpfRaw);
                NativeAudioEngine.setFilter(cutoff, params.filterResonance);
            }

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

        TextView labelDelayWet = dialogView.findViewById(R.id.label_delay_wet);
        SeekBar seekDelayWet = dialogView.findViewById(R.id.seek_delay_wet);
        TextView labelDelayTime = dialogView.findViewById(R.id.label_delay_time);
        SeekBar seekDelayTime = dialogView.findViewById(R.id.seek_delay_time);
        TextView labelDelayFeedback = dialogView.findViewById(R.id.label_delay_feedback);
        SeekBar seekDelayFeedback = dialogView.findViewById(R.id.seek_delay_feedback);

        Switch swPitch = dialogView.findViewById(R.id.switch_pitch_enable);
        Switch swMod = dialogView.findViewById(R.id.switch_mod_enable);
        Switch swDrive = dialogView.findViewById(R.id.switch_drive_enable);
        Switch swDelay = dialogView.findViewById(R.id.switch_delay_enable);
        Switch swFilter = dialogView.findViewById(R.id.switch_filter_enable);
        Switch swOctave = dialogView.findViewById(R.id.switch_octave_enable);

        TextView labelDriveLimit = dialogView.findViewById(R.id.label_drive_limit);
        SeekBar seekDriveLimit = dialogView.findViewById(R.id.seek_drive_limit);

        TextView labelOctaveSlew = dialogView.findViewById(R.id.label_octave_slew);
        SeekBar seekOctaveSlew = dialogView.findViewById(R.id.seek_octave_slew);

        Switch swRecording = dialogView.findViewById(R.id.switch_recording_mode);
        swRecording.setChecked(params.recordingMode);

        RadioGroup rgVerticalControl = dialogView.findViewById(R.id.rg_vertical_control);
        if (params.verticalControlParam == 0)
            rgVerticalControl.check(R.id.rb_vertical_volume);
        else if (params.verticalControlParam == 1)
            rgVerticalControl.check(R.id.rb_vertical_modulation);
        else if (params.verticalControlParam == 2)
            rgVerticalControl.check(R.id.rb_vertical_lpf);

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

        seekOctaveSlew.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelOctaveSlew.setText(String.format("Octave Smoothing: %d%%", progress));
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

        seekDelayWet.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelDelayWet.setText(String.format("Delay Wet (Mix): %d%%", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekDelayTime.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelDelayTime.setText(String.format("Delay Time (ms): %d", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekDelayFeedback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelDelayFeedback.setText(String.format("Delay Feedback: %d%%", progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekDriveLimit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelDriveLimit.setText(String.format("Overdrive Gain Limit: %d%%", progress));
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
        seekDelayWet.setProgress((int) (params.delayWet * 100));
        seekDelayTime.setProgress((int) (params.delayTime * 1000));
        seekDelayFeedback.setProgress((int) (params.delayFeedback * 100));
        seekDriveLimit.setProgress((int) (params.driveLimit * 100));
        seekOctaveSlew.setProgress((int) (params.octaveSlewRate * 100));

        swPitch.setChecked(params.isPitchEnabled);
        swMod.setChecked(params.isModEnabled);
        swDrive.setChecked(params.isDriveEnabled);
        swDelay.setChecked(params.isDelayEnabled);
        swFilter.setChecked(params.isFilterEnabled);
        swOctave.setChecked(params.isOctaveEnabled);

        // Dynamic visibility logic
        View[] pitchViews = { labelBendRange, seekBendRange, labelBendSlew, seekBendSlew, labelGlideTime,
                seekGlideTime };
        View[] modViews = { labelModDepth, seekModDepth, labelModRate, seekModRate };
        View[] driveViews = { labelDriveLimit, seekDriveLimit };
        View[] delayViews = { labelDelayWet, seekDelayWet, labelDelayTime, seekDelayTime, labelDelayFeedback,
                seekDelayFeedback };

        autoToggleVisibility(swPitch, pitchViews);
        autoToggleVisibility(swMod, modViews);
        autoToggleVisibility(swDrive, driveViews);
        autoToggleVisibility(swDelay, delayViews);

        View[] octaveViews = { labelOctaveSlew, seekOctaveSlew };
        autoToggleVisibility(swOctave, octaveViews);

        // Update labels initially
        labelBendRange.setText(String.format("Pitch Bend Range: %.1f semitones", params.bendRange));
        labelBendSlew.setText(String.format("Pitch Bend Smoothing: %d%%", (int) (params.bendSlewRate * 100)));
        labelModDepth.setText(String.format("Max Modulation Depth: %.1f semitones", params.modulationDepth));
        labelModRate.setText(String.format("Modulation Rate: %.1f Hz", params.modulationRate));
        labelBufferSize.setText(String.format("Buffer Size (Frames): %d", params.bufferSize));
        labelDelayWet.setText(String.format("Delay Wet (Mix): %d%%", (int) (params.delayWet * 100)));
        labelDelayTime.setText(String.format("Delay Time: %d ms", (int) (params.delayTime * 1000)));
        labelDelayFeedback.setText(String.format("Delay Feedback: %d%%", (int) (params.delayFeedback * 100)));
        labelDriveLimit.setText(String.format("Overdrive Gain Limit: %d%%", (int) (params.driveLimit * 100)));
        labelOctaveSlew.setText(String.format("Octave Smoothing: %d%%", (int) (params.octaveSlewRate * 100)));

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
            params.delayWet = seekDelayWet.getProgress() / 100.0;
            params.delayTime = seekDelayTime.getProgress() / 1000.0;
            params.delayFeedback = seekDelayFeedback.getProgress() / 100.0;
            params.isPitchEnabled = swPitch.isChecked();
            params.isModEnabled = swMod.isChecked();
            params.isDriveEnabled = swDrive.isChecked();
            params.isDelayEnabled = swDelay.isChecked();
            params.isFilterEnabled = swFilter.isChecked();
            params.isOctaveEnabled = swOctave.isChecked();
            params.driveLimit = seekDriveLimit.getProgress() / 100.0;
            params.octaveSlewRate = seekOctaveSlew.getProgress() / 100.0;
            params.recordingMode = swRecording.isChecked();

            int checkedId = rgVerticalControl.getCheckedRadioButtonId();
            if (checkedId == R.id.rb_vertical_volume)
                params.verticalControlParam = 0;
            else if (checkedId == R.id.rb_vertical_modulation)
                params.verticalControlParam = 1;
            else if (checkedId == R.id.rb_vertical_lpf)
                params.verticalControlParam = 2;

            NativeAudioEngine.setRecordingMode(params.recordingMode);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putBoolean("global_recording_mode", params.recordingMode)
                    .putFloat("perf_bend_range", (float) params.bendRange)
                    .putFloat("perf_bend_slew", (float) params.bendSlewRate)
                    .putFloat("perf_mod_depth", (float) params.modulationDepth)
                    .putFloat("perf_mod_rate", (float) params.modulationRate)
                    .putInt("perf_buffer_size", params.bufferSize)
                    .putFloat("perf_glide_time", (float) params.glideTime)
                    .putFloat("perf_delay_wet", (float) params.delayWet)
                    .putFloat("perf_delay_time", (float) params.delayTime)
                    .putFloat("perf_delay_feedback", (float) params.delayFeedback)
                    .putBoolean("perf_pitch_enabled", params.isPitchEnabled)
                    .putBoolean("perf_mod_enabled", params.isModEnabled)
                    .putBoolean("perf_drive_enabled", params.isDriveEnabled)
                    .putBoolean("perf_delay_enabled", params.isDelayEnabled)
                    .putBoolean("perf_filter_enabled", params.isFilterEnabled)
                    .putFloat("perf_drive_limit", (float) params.driveLimit)
                    .putBoolean("perf_fixed_duration", params.fixedDuration)
                    .putFloat("env_attack", (float) params.attackSec)
                    .putFloat("env_decay", (float) params.decaySec)
                    .putFloat("env_sustain_level", (float) params.sustainLevel)
                    .putFloat("env_release", (float) params.releaseSec)
                    .putFloat("perf_drive", (float) params.drive)
                    .putBoolean("perf_octave_enabled", params.isOctaveEnabled)
                    .putFloat("perf_octave_slew", (float) params.octaveSlewRate)
                    .putInt("perf_vertical_control", params.verticalControlParam)
                    .apply();

            updateEffectVisibility();
            syncNativeParams();
            dialog.dismiss();
            showInterstitialAd();
        });

        dialog.show();
    }

    private void autoToggleVisibility(Switch sw, View[] views) {
        // Initial state
        for (View v : views)
            v.setVisibility(sw.isChecked() ? View.VISIBLE : View.GONE);
        // Listener
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            for (View v : views)
                v.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        });
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
            json.put("drive", params.drive);
            json.put("delayWet", params.delayWet);
            json.put("delayTime", params.delayTime);
            json.put("delayFeedback", params.delayFeedback);
            json.put("gain", params.gain);
            json.put("bufferSize", params.bufferSize);
            json.put("recordingMode", params.recordingMode);
            json.put("filterCutoff", params.filterCutoff);
            json.put("filterResonance", params.filterResonance);
            json.put("isFilterEnabled", params.isFilterEnabled);

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            prefs.edit().putString(name, json.toString()).apply();
            Toast.makeText(this, "Preset '" + name + "' saved", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Log.e(TAG, "Error saving preset", e);
        }
    }

    private void showLoadPresetDialog() {
        final List<String> options = new ArrayList<>();
        options.add("Load Audio");
        options.add("Load Preset");

        new AlertDialog.Builder(this)
                .setTitle("Select Action")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    if (which == 0) {
                        audioPickerLauncher.launch(new String[] { "audio/*" });
                    } else {
                        showPresetSelectionDialog();
                    }
                })
                .show();
    }

    private void showPresetSelectionDialog() {
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

                params.delayWet = json.optDouble("delayWet", 0.0);
                params.delayTime = json.optDouble("delayTime", 0.5);
                params.delayFeedback = json.optDouble("delayFeedback", 0.5);
                params.filterCutoff = json.optDouble("filterCutoff", 1000.0);
                params.filterResonance = json.optDouble("filterResonance", 1.0);
                params.isFilterEnabled = json.optBoolean("isFilterEnabled", true);

                // Update XY Pad positions
                if (padOscillator != null) {
                    float freqY = (float) (Math.log10(params.centerFreq / 200.0));
                    float sigmaX = (float) ((params.sigma - 0.1) / 2.9);
                    padOscillator.setValues(sigmaX, freqY);
                }
                if (padFilter != null) {
                    float cutoffY = (float) (Math.log(params.filterCutoff / 30.0) / Math.log(500.0));
                    float resX = (float) ((params.filterResonance - 0.5) / 3.5);
                    padFilter.setValues(resX, cutoffY);
                }

                if (seekDrive != null)
                    seekDrive.setProgress((int) (params.drive * 100.0));

                updateEffectVisibility();

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
        sendIntent.putExtra(Intent.EXTRA_TEXT, "Check out this Shepard Keyboard app!\n\n" +
                "https://play.google.com/store/apps/details?id=jp.example.shepardkeyboard");
        sendIntent.setType("text/plain");

        Intent shareIntent = Intent.createChooser(sendIntent, null);
        startActivity(shareIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        audioHandler.removeCallbacks(audioUpdater);
        NativeAudioEngine.delete();
    }

}