package jp.example.shepardkeyboard;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
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

        NativeAudioEngine.create();

        tvTranspose = findViewById(R.id.tv_transpose);
        envelopeView = findViewById(R.id.envelope_view);
        envelopeView.setParams(params);

        setupTransposeSeekBar();
        setupRealTimeControls();
        setupKeyboard();

        envelopeView.setOnEnvelopeChangeListener((attack, sustain, release) -> {
            syncNativeParams();
        });

        SwitchMaterial swFixedDuration = findViewById(R.id.sw_fixed_duration);
        swFixedDuration.setOnCheckedChangeListener((buttonView, isChecked) -> {
            params.fixedDuration = isChecked;
            NativeAudioEngine.setFixedDurationMode(isChecked);
            syncNativeParams();
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
        syncNativeParams();
    }

    private void initDefaultPresets() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getAll().isEmpty()) {
            // High frequency, sharp attack/release, short sustain
            savePresetInternal("Twinkle", 0.01, 0.2, 0.2, 1200.0, 0.8);
            // Low frequency, slow attack, long release, medium sustain
            savePresetInternal("Moan", 0.5, 2.0, 1.0, 150.0, 1.2);
            // Medium frequency, very sharp, very short sustain
            savePresetInternal("Pulsar", 0.05, 0.1, 0.1, 440.0, 0.4);
            // Wide spectrum, very slow attack, long sustain
            savePresetInternal("Nebula", 1.5, 3.0, 2.0, 300.0, 2.5);
        }
    }

    private void savePresetInternal(String name, double attack, double release, double duration, double center,
            double sigma) {
        try {
            JSONObject json = new JSONObject();
            json.put("attack", attack);
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
                params.modulationDepth = progress / 50.0; // 0 to 2 semitones
                syncNativeParams();
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
        NativeAudioEngine.setParams(params.attackSec, params.releaseSec, params.durationSec, params.centerFreq, params.sigma);
        NativeAudioEngine.setModulation(params.modulationDepth, params.modulationRate);
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

    private void handleTouch(View container, int pointerId, float x, float y, List<View> blackKeys, List<View> whiteKeys, Map<View, Integer> viewToNote) {
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
                // Update pointers first to correctly check if other fingers are holding the old note
                activePointers.remove(pointerId);
                if (lastNote != null && !activePointers.values().contains(lastNote)) {
                    NativeAudioEngine.setNoteOff(lastNote);
                }
                NativeAudioEngine.setNoteOn(transposedNote, volume);
                activePointers.put(pointerId, transposedNote);
                updateKeyVisuals(viewToNote, activePointers.values());
            } else {
                // Just update volume
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
            if (currentlyPlaying.contains(note)) {
                v.setBackgroundColor(Color.GRAY);
            } else {
                v.setBackgroundColor(isBlack ? Color.BLACK : Color.WHITE);
            }
        }
    }

    private boolean isPointInside(View v, float x, float y) {
        return x >= v.getLeft() && x <= v.getRight() && y >= v.getTop() && y <= v.getBottom();
    }

    private void regenerateSounds() {
        // Redraw only, synthesis is real-time now
        envelopeView.setParams(params);
        syncNativeParams();
    }

    private void showProgress() {
    }

    private void hideProgress() {
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        SeekBar seekAttack = dialogView.findViewById(R.id.seek_attack);
        SeekBar seekRelease = dialogView.findViewById(R.id.seek_release);
        SeekBar seekDuration = dialogView.findViewById(R.id.seek_duration);
        SeekBar seekCenter = dialogView.findViewById(R.id.seek_center_freq);
        SeekBar seekSigma = dialogView.findViewById(R.id.seek_sigma);
        SeekBar seekBufferSize = dialogView.findViewById(R.id.seek_buffer_size);

        TextView labelAttack = dialogView.findViewById(R.id.label_attack);
        TextView labelRelease = dialogView.findViewById(R.id.label_release);
        TextView labelDuration = dialogView.findViewById(R.id.label_duration);
        TextView labelCenter = dialogView.findViewById(R.id.label_center_freq);
        TextView labelSigma = dialogView.findViewById(R.id.label_sigma);
        TextView labelBufferSize = dialogView.findViewById(R.id.label_buffer_size);

        View monoSwitch = dialogView.findViewById(R.id.switch_mono);
        if (monoSwitch != null)
            monoSwitch.setVisibility(View.GONE);

        Button btnApply = dialogView.findViewById(R.id.btn_apply);

        // Helper to update text
        seekAttack.setOnSeekBarChangeListener(createSeekListener(labelAttack, "Attack (ms): %d"));
        seekRelease.setOnSeekBarChangeListener(createSeekListener(labelRelease, "Release (ms): %d"));
        seekDuration.setOnSeekBarChangeListener(createSeekListener(labelDuration, "Duration (ms): %d"));
        seekCenter.setOnSeekBarChangeListener(createSeekListener(labelCenter, "Center Frequency (Hz): %d"));
        seekSigma.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelSigma.setText(String.format("Sigma (Spectral Width): %.1f", Math.max(0.1, progress / 50.0)));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBufferSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                labelBufferSize.setText("Buffer Size (Frames): " + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekAttack.setProgress((int) (params.attackSec * 1000));
        seekRelease.setProgress((int) (params.releaseSec * 1000));
        seekDuration.setProgress((int) (params.durationSec * 1000));
        seekCenter.setProgress((int) params.centerFreq);
        seekSigma.setProgress((int) (params.sigma * 50));
        seekBufferSize.setProgress(params.bufferSize);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnApply.setOnClickListener(v -> {
            params.attackSec = seekAttack.getProgress() / 1000.0;
            params.releaseSec = seekRelease.getProgress() / 1000.0;
            params.durationSec = Math.max(0.1, seekDuration.getProgress() / 1000.0);
            params.centerFreq = Math.max(50, seekCenterFreq.getProgress());
            params.sigma = Math.max(0.1, seekSigma.getProgress() / 50.0);
            params.bufferSize = Math.max(64, seekBufferSize.getProgress());

            // Sync with main sliders
            seekCenterFreq.setProgress((int) params.centerFreq);
            seekSigma.setProgress((int) (params.sigma * 50));

            syncNativeParams();
            envelopeView.setParams(params);
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
            json.put("release", params.releaseSec);
            json.put("duration", params.durationSec);
            json.put("centerFreq", params.centerFreq);
            json.put("sigma", params.sigma);
            json.put("gain", params.gain);
            json.put("bufferSize", params.bufferSize);

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
        final List<String> names = new ArrayList<>(allEntries.keySet());

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
                params.releaseSec = json.optDouble("release", 0.5);
                params.durationSec = json.optDouble("duration", 1.0);
                params.centerFreq = json.optDouble("centerFreq", 600.0);
                params.sigma = json.optDouble("sigma", 1.0);
                params.gain = json.optDouble("gain", 0.8);
                params.bufferSize = json.optInt("bufferSize", 256);

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