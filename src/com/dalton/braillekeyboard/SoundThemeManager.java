package com.dalton.braillekeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SoundThemeManager {
    private static final String TAG = "SBK_SoundThemeManager";

    public static final int SOUND_TYPE_DEFAULT = 0;
    public static final int SOUND_TYPE_SPACE = 1;
    public static final int SOUND_TYPE_DELETE = 2;
    public static final int SOUND_TYPE_ENTER = 3;

    private static SoundThemeManager instance;
    private static volatile long lastSoundPlayedTime = 0;

    public static long getLastSoundPlayedTime() {
        return lastSoundPlayedTime;
    }

    private final Context context;
    private SoundPool soundPool;
    private final Map<Integer, Integer> soundIdMap = new HashMap<>();
    private String currentTheme = "system";
    private String customThemeUri = null;
    private AudioManager audioManager;
    private long lastSoundTime = 0;
    private static final long DEBOUNCE_DELAY_MS = 60;

    private float volumeFactor = 1.0f;
    private boolean accessibilityStream = false;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;

    private SoundThemeManager(Context context) {
        this.context = context.getApplicationContext();
        this.audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        
        SharedPreferences prefs = Options.getSharedPreferences(this.context);
        updateVolumeFactor(prefs);
        updateAccessibilityStream(prefs);
        this.preferenceChangeListener = (sharedPreferences, key) -> {
            if (key != null && key.equals(SoundThemeManager.this.context.getString(R.string.pref_keyboard_sound_volume_key))) {
                updateVolumeFactor(sharedPreferences);
            } else if (key != null && key.equals(SoundThemeManager.this.context.getString(R.string.pref_use_accessibility_volume_sounds_key))) {
                updateAccessibilityStream(sharedPreferences);
                reloadTheme();
            }
        };
        prefs.registerOnSharedPreferenceChangeListener(this.preferenceChangeListener);

        initSoundPool();
        loadSelectedTheme();
    }

    public static synchronized SoundThemeManager getInstance(Context context) {
        if (instance == null) {
            instance = new SoundThemeManager(context);
        }
        return instance;
    }

    private void initSoundPool() {
        if (soundPool != null) {
            soundPool.release();
        }
        // With the accessibility option the sounds go through the accessibility audio stream, like the speech does
        // (see Speech), so they follow the accessibility volume instead of the system/touch sounds volume.
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(accessibilityStream ? AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY
                        : AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build();
        soundIdMap.clear();
    }

    private void updateAccessibilityStream(SharedPreferences prefs) {
        accessibilityStream = prefs.getBoolean(context.getString(R.string.pref_use_accessibility_volume_sounds_key), false);
    }

    /** The audio attributes are fixed when the SoundPool is built: rebuild it and load the selected theme again. */
    private synchronized void reloadTheme() {
        currentTheme = null;
        customThemeUri = null;
        loadSelectedTheme();
    }

    private void updateVolumeFactor(SharedPreferences prefs) {
        String volKey = context.getString(R.string.pref_keyboard_sound_volume_key);
        int volPercent = prefs.getInt(volKey, 100);
        if (volPercent < 0) volPercent = 0;
        if (volPercent > 100) volPercent = 100;
        volumeFactor = volPercent / 100.0f;
        Log.d(TAG, "Updated sound volume factor: " + volumeFactor);
    }

    public synchronized void loadSelectedTheme() {
        SharedPreferences prefs = Options.getSharedPreferences(context);
        
        String themeKey = context.getString(R.string.pref_keyboard_sound_theme_key);
        String theme = prefs.getString(themeKey, "system");
        
        String customUriKey = context.getString(R.string.pref_custom_sound_theme_folder_uri_key);
        String uriStr = prefs.getString(customUriKey, null);

        if (theme != null && theme.equals(currentTheme) && 
            ((customThemeUri == null && uriStr == null) || (customThemeUri != null && customThemeUri.equals(uriStr)))) {
            return;
        }

        currentTheme = theme != null ? theme : "system";
        customThemeUri = uriStr;

        initSoundPool();

        if ("system".equals(currentTheme)) {
            Log.d(TAG, "System sounds selected, skipping SoundPool loading");
            return;
        }

        if ("custom".equals(currentTheme)) {
            loadCustomTheme(uriStr);
        } else {
            loadAssetTheme(currentTheme);
        }
    }

    private void loadAssetTheme(String themeName) {
        Log.d(TAG, "Loading asset theme: " + themeName);
        String[] soundFiles = {"tap.wav", "space.wav", "delete.wav", "enter.wav"};
        int[] soundTypes = {SOUND_TYPE_DEFAULT, SOUND_TYPE_SPACE, SOUND_TYPE_DELETE, SOUND_TYPE_ENTER};

        for (int i = 0; i < soundFiles.length; i++) {
            String path = "sound_themes/" + themeName + "/" + soundFiles[i];
            AssetFileDescriptor afd = null;
            try {
                afd = context.getAssets().openFd(path);
                int soundId = soundPool.load(afd, 1);
                soundIdMap.put(soundTypes[i], soundId);
            } catch (IOException e) {
                Log.e(TAG, "Failed to load asset sound: " + path, e);
            } finally {
                if (afd != null) {
                    try {
                        afd.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private void loadCustomTheme(String folderUriStr) {
        if (folderUriStr == null || folderUriStr.trim().isEmpty()) {
            Log.w(TAG, "Custom theme folder URI is not set");
            return;
        }

        Log.d(TAG, "Loading custom theme from URI: " + folderUriStr);
        try {
            Uri treeUri = Uri.parse(folderUriStr);
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root == null || !root.exists()) {
                Log.w(TAG, "Custom theme directory does not exist or is inaccessible");
                return;
            }

            String[] soundFiles = {"tap.wav", "space.wav", "delete.wav", "enter.wav"};
            int[] soundTypes = {SOUND_TYPE_DEFAULT, SOUND_TYPE_SPACE, SOUND_TYPE_DELETE, SOUND_TYPE_ENTER};

            for (int i = 0; i < soundFiles.length; i++) {
                DocumentFile file = root.findFile(soundFiles[i]);
                if (file != null && file.exists()) {
                    AssetFileDescriptor afd = null;
                    try {
                        afd = context.getContentResolver().openAssetFileDescriptor(file.getUri(), "r");
                        if (afd != null) {
                            int soundId = soundPool.load(afd, 1);
                            soundIdMap.put(soundTypes[i], soundId);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to load custom sound: " + soundFiles[i], e);
                    } finally {
                        if (afd != null) {
                            try {
                                afd.close();
                            } catch (IOException ignored) {}
                        }
                    }
                } else {
                    Log.w(TAG, "Custom sound file not found: " + soundFiles[i]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load custom theme", e);
        }
    }

    public synchronized void playSound(int soundType) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSoundTime < DEBOUNCE_DELAY_MS) {
            return;
        }
        lastSoundTime = currentTime;

        lastSoundPlayedTime = currentTime;
        Log.i("SBK_LATENCY", "SOUND_PLAYED:" + currentTime);

        playSoundWithVolume(soundType, volumeFactor);
    }

    public synchronized void playSoundWithVolume(int soundType, float volume) {
        if ("system".equals(currentTheme)) {
            playSystemSound(soundType, volume);
            return;
        }

        Integer soundId = soundIdMap.get(soundType);
        if (soundId != null && soundId > 0) {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f);
        } else {
            playSystemSound(soundType, volume);
        }
    }

    private void playSystemSound(int soundType, float volume) {
        if (audioManager == null) return;
        
        int effect;
        switch (soundType) {
            case SOUND_TYPE_SPACE:
                effect = AudioManager.FX_KEYPRESS_SPACEBAR;
                break;
            case SOUND_TYPE_DELETE:
                effect = AudioManager.FX_KEYPRESS_DELETE;
                break;
            case SOUND_TYPE_ENTER:
                effect = AudioManager.FX_KEYPRESS_RETURN;
                break;
            case SOUND_TYPE_DEFAULT:
            default:
                effect = AudioManager.FX_KEYPRESS_STANDARD;
                break;
        }
        audioManager.playSoundEffect(effect, volume);
    }
}
