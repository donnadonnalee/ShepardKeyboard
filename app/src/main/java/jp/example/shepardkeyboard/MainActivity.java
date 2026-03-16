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

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "ShepardPresets";

    private SoundManager soundManager;
    private ShepardGenerator.Params params = new ShepardGenerator.Params();
    private boolean isMonophonic = false;
    private int transposeOffset = 0; // -6 to +6
    private AlertDialog progressDialog;
    private TextView tvTranspose;
    private EnvelopeView envelopeView;
    private InterstitialAd mInterstitialAd;

    // Custom Progress Dialog Views
    private AnalogClockProgressView analogClockView;
    private TextView tvProgressStatus;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        soundManager = new SoundManager(this);

        tvTranspose = findViewById(R.id.tv_transpose);
        setupTransposeSeekBar();
        setupMonoSwitch();
        setupKeyboard();

        findViewById(R.id.ib_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.ib_save).setOnClickListener(v -> showSavePresetDialog());
        findViewById(R.id.ib_load).setOnClickListener(v -> showLoadPresetDialog());

        envelopeView = findViewById(R.id.envelope_view);
        envelopeView.setParams(params);

        MobileAds.initialize(this, initializationStatus -> {
            loadInterstitialAd();
        });

        initDefaultPresets();

        regenerateSounds();
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
                String[] noteNames = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
                // 0 is C. -6 to +6.
                int noteIndex = (transposeOffset + 12) % 12;
                tvTranspose.setText("Key: " + noteNames[noteIndex] + " (" + (transposeOffset >= 0 ? "+" : "") + transposeOffset + ")");

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupMonoSwitch() {
        @SuppressLint("UseSwitchCompatOrMaterialCode")
        Switch switchMono = findViewById(R.id.main_switch_mono);
        switchMono.setOnCheckedChangeListener((buttonView, isChecked) -> {
            isMonophonic = isChecked;
            soundManager.setMonophonic(isMonophonic);
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupKeyboard() {
        int[] keyIds = {
                R.id.key_c, R.id.key_cs, R.id.key_d, R.id.key_ds, R.id.key_e, R.id.key_f,
                R.id.key_fs, R.id.key_g, R.id.key_gs, R.id.key_a, R.id.key_as, R.id.key_b
        };

        for (int i = 0; i < keyIds.length; i++) {
            final int index = i;
            final View keyView = findViewById(keyIds[i]);

            final boolean isBlackKey = getResources().getResourceEntryName(keyIds[i]).endsWith("s");
            final int originalColor = isBlackKey ? Color.BLACK : Color.WHITE;
            final int pressedColor = Color.GRAY;

            keyView.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        v.setBackgroundColor(pressedColor);
                        float y = event.getY();
                        float height = v.getHeight();
                        float volume = Math.max(0.1f, Math.min(1.0f, y / height));
                        int noteToPlay = (index + transposeOffset + 12) % 12;
                        soundManager.playNote(noteToPlay, volume);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackgroundColor(originalColor);
                        return true;
                }
                return false;
            });
        }
    }

    private void regenerateSounds() {
        envelopeView.setParams(params);
        showProgress();
        new Thread(() -> {
            soundManager.loadNotes(params, (noteIndex, noteProgress) -> {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (analogClockView != null) {
                        analogClockView.setProgress(noteIndex, noteProgress);
                        tvProgressStatus.setText("Synthesizing: Note " + (noteIndex + 1) + "/12");
                    }
                });
            });
            new Handler(Looper.getMainLooper()).post(() -> {
                hideProgress();
                Toast.makeText(MainActivity.this, "Shepard Tones Ready!", Toast.LENGTH_SHORT).show();
            });
        }).start();

    }

    private void showProgress() {
        if (isFinishing() || isDestroyed())
            return;

        if (progressDialog == null) {
            View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_progress, null);
            analogClockView = dialogView.findViewById(R.id.analog_clock_progress);
            tvProgressStatus = dialogView.findViewById(R.id.tv_progress_status);
            
            Button btnShare = dialogView.findViewById(R.id.btn_share_app);
            Button btnAds = dialogView.findViewById(R.id.btn_watch_ads);

            btnShare.setOnClickListener(v -> shareApp());
            btnAds.setOnClickListener(v -> showInterstitialAd());

            progressDialog = new AlertDialog.Builder(this)
                    .setCancelable(false)
                    .setView(dialogView)
                    .create();
        }
        
        // Reset progress on show
        if (analogClockView != null) {
            analogClockView.setProgress(0, 0);
        }
        if (tvProgressStatus != null) {
            tvProgressStatus.setText("Synthesizing Tones...");
        }

        if (!progressDialog.isShowing()) {
            progressDialog.show();
        }
    }


    private void hideProgress() {
        if (!isFinishing() && !isDestroyed() && progressDialog != null && progressDialog.isShowing()) {
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                Log.e(TAG, "Error dismissing progress dialog", e);
            }
        }
    }

    private void showSettingsDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);
        SeekBar seekAttack = dialogView.findViewById(R.id.seek_attack);
        SeekBar seekRelease = dialogView.findViewById(R.id.seek_release);
        SeekBar seekDuration = dialogView.findViewById(R.id.seek_duration);
        SeekBar seekCenter = dialogView.findViewById(R.id.seek_center_freq);
        SeekBar seekSigma = dialogView.findViewById(R.id.seek_sigma);

        TextView labelAttack = dialogView.findViewById(R.id.label_attack);
        TextView labelRelease = dialogView.findViewById(R.id.label_release);
        TextView labelDuration = dialogView.findViewById(R.id.label_duration);
        TextView labelCenter = dialogView.findViewById(R.id.label_center_freq);
        TextView labelSigma = dialogView.findViewById(R.id.label_sigma);

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
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekAttack.setProgress((int) (params.attackSec * 1000));
        seekRelease.setProgress((int) (params.releaseSec * 1000));
        seekDuration.setProgress((int) (params.durationSec * 1000));
        seekCenter.setProgress((int) params.centerFreq);
        seekSigma.setProgress((int) (params.sigma * 50));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnApply.setOnClickListener(v -> {
            params.attackSec = seekAttack.getProgress() / 1000.0;
            params.releaseSec = seekRelease.getProgress() / 1000.0;
            params.durationSec = Math.max(0.1, seekDuration.getProgress() / 1000.0);
            params.centerFreq = Math.max(50, seekCenter.getProgress());
            params.sigma = Math.max(0.1, seekSigma.getProgress() / 50.0);

            regenerateSounds();
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
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
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
                params.centerFreq = json.optDouble("centerFreq", 440.0);
                params.sigma = json.optDouble("sigma", 1.0);
                params.gain = json.optDouble("gain", 0.8);

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
        if (soundManager != null) {
            soundManager.release();
        }
    }
}