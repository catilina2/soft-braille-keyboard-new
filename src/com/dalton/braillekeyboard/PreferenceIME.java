/*
 * Copyright (C) 2016 The Soft Braille Keyboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dalton.braillekeyboard;

import com.dalton.braillekeyboard.R;
import com.dalton.braillekeyboard.BrailleParser.BrailleType;
import com.dalton.braillekeyboard.Options.KeyboardFeedback;
import com.dalton.braillekeyboard.Options.KeyboardEcho;
import com.dalton.braillekeyboard.Options.OptionList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.EngineInfo;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import android.net.Uri;
import androidx.core.content.FileProvider;

import com.dalton.braillekeyboard.BrailleParser.BrailleType;
import com.dalton.braillekeyboard.Options.KeyboardEcho;
import com.dalton.braillekeyboard.Options.KeyboardFeedback;
import com.dalton.braillekeyboard.Options.OptionList;
import com.googlecode.eyesfree.braille.translate.TableInfo;

/**
 * Modernized PreferenceIME using AndroidX Preference Library.
 * Handles nested PreferenceScreens and Dialogs correctly.
 */
public class PreferenceIME extends AppCompatActivity implements 
    PreferenceFragmentCompat.OnPreferenceStartScreenCallback {

    private static final String TITLE_TAG = "settings_title";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        
        if (savedInstanceState == null) {
            String rootKey = getIntent().getStringExtra(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT);
            Settings fragment = Settings.newInstance(rootKey);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, fragment)
                    .commit();
        } else {
            setTitle(savedInstanceState.getCharSequence(TITLE_TAG));
        }

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                setTitle(R.string.settings_name);
            }
        });
        
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putCharSequence(TITLE_TAG, getTitle());
    }

    @Override
    public boolean onSupportNavigateUp() {
        if (getSupportFragmentManager().popBackStackImmediate()) {
            return true;
        }
        finish();
        return true;
    }

    @Override
    public boolean onPreferenceStartScreen(PreferenceFragmentCompat caller, PreferenceScreen pref) {
        Settings fragment = Settings.newInstance(pref.getKey());
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .addToBackStack(pref.getKey())
                .commit();
        setTitle(pref.getTitle());
        return true;
    }

    public static class Settings extends PreferenceFragmentCompat {
        private BrailleParser brailleParser;
        private TextToSpeech tts;

        private ActivityResultLauncher<Intent> backupLauncher;
        private ActivityResultLauncher<Intent> restoreLauncher;

        public static Settings newInstance(String rootKey) {
            Settings fragment = new Settings();
            Bundle args = new Bundle();
            args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, rootKey);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            backupLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                            writeBackup(result.getData().getData());
                        }
                    });
            restoreLauncher = registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == android.app.Activity.RESULT_OK && result.getData() != null) {
                            confirmRestore(result.getData().getData());
                        }
                    });
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                getPreferenceManager().setStorageDeviceProtected();
            }
            
            setPreferencesFromResource(R.xml.ime_preferences, rootKey);
            
            // Logic for specific keys that might be in different screens
            initPreferences(rootKey);
        }
        
        private void initPreferences(String rootKey) {
            // Main screen or specific categories
            if (rootKey == null) {
                // Initialize version on main screen
                setupVersionPreference();
            }

            // Keyboard Feedback Category
            if (rootKey == null || rootKey.equals(getString(R.string.pref_category_feedback_key))) {
                setupFeedbackPreferences();
            }

            // Braille Category
            if (rootKey == null || rootKey.equals(getString(R.string.pref_category_braille_key))) {
                setupBraillePreferences();
            }

            // TTS Category
            if (rootKey == null || rootKey.equals("pref_text_to_speech_title_key") || findPreference(getString(R.string.pref_text_to_speech_engine_key)) != null) {
                setupTTSPreferences();
            }
            
            // Advanced / Log Category
            if (rootKey == null || rootKey.equals(getString(R.string.pref_category_advanced_key))) {
                setupLogPreferences();
            }

            // Misc Category
            if (rootKey == null || rootKey.equals(getString(R.string.pref_category_misc_key))) {
                setupMiscPreferences();
            }
        }

        private void setupMiscPreferences() {
            Preference storageManager = findPreference("pref_storage_manager_key");
            if (storageManager != null) {
                storageManager.setOnPreferenceClickListener(p -> {
                    Intent intent = new Intent(getActivity(), StorageManagerActivity.class);
                    intent.putExtra(StorageManagerActivity.EXTRA_MODE, StorageManagerActivity.MODE_TABLES);
                    startActivity(intent);
                    return true;
                });
            }
        }

        private void setupFeedbackPreferences() {
            ListPreference echo = findPreference(getString(R.string.pref_echo_feedback_key));
            ListPreference feedback = findPreference(getString(R.string.pref_keyboard_feedback_key));
            if (echo != null) addOptions(echo, KeyboardEcho.ALL);
            if (feedback != null) addOptions(feedback, KeyboardFeedback.ALL);

            ListPreference soundThemePref = findPreference(getString(R.string.pref_keyboard_sound_theme_key));
            if (soundThemePref != null) {
                soundThemePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (getActivity() != null) {
                            SoundThemeManager.getInstance(getActivity()).loadSelectedTheme();
                        }
                    });
                    return true;
                });
            }

            Preference customFolderPref = findPreference(getString(R.string.pref_custom_sound_theme_folder_key));
            if (customFolderPref != null) {
                customFolderPref.setOnPreferenceClickListener(p -> {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, 2002);
                    return true;
                });
            }
        }

        private void setupBraillePreferences() {
            Preference keyboardHelp = findPreference("pref_keyboard_help_key");
            if (keyboardHelp != null) {
                keyboardHelp.setOnPreferenceClickListener(p -> {
                    startActivity(new Intent(getActivity(), KeyboardHelpActivity.class));
                    return true;
                });
            }

            Preference importTable = findPreference("pref_import_table_key");
            if (importTable != null) {
                importTable.setOnPreferenceClickListener(p -> {
                    startActivity(new Intent(getActivity(), TableManagerActivity.class));
                    return true;
                });
            }

            final ListPreference brailleTypePref = findPreference(getString(R.string.pref_braille_type_key));
            final CheckBoxPreference useEightDotsPref = findPreference(getString(R.string.pref_use_eight_dots_key));

            if (brailleTypePref != null && useEightDotsPref != null) {
                brailleTypePref.setOnPreferenceChangeListener((preference, newValue) -> {
                    try {
                        int value = Integer.parseInt((String) newValue);
                        boolean isComputer = (value == 0); // 0 = COMPUTER in BrailleType.prefValue()
                        useEightDotsPref.setChecked(isComputer);
                        Options.writeBooleanPreference(getActivity(), R.string.pref_use_eight_dots_key, isComputer);
                    } catch (Exception e) {
                        android.util.Log.e("PreferenceIME", "Error syncing braille type and 8 dots", e);
                    }
                    return true;
                });
            }

            brailleParser = new BrailleParser(getActivity(), status -> addTables(status));

            final CheckBoxPreference lock = findPreference(getString(R.string.pref_lock_calibration_key));
            final CheckBoxPreference auto = findPreference(getString(R.string.pref_auto_match_keyboard_key));

            if (lock != null && auto != null) {
                lock.setOnPreferenceChangeListener((p, newValue) -> {
                    auto.setChecked(!(Boolean) newValue);
                    Options.writeBooleanPreference(getActivity(), R.string.pref_auto_match_keyboard_key, !(Boolean) newValue);
                    return true;
                });
                auto.setOnPreferenceChangeListener((p, newValue) -> {
                    lock.setChecked(!(Boolean) newValue);
                    Options.writeBooleanPreference(getActivity(), R.string.pref_lock_calibration_key, !(Boolean) newValue);
                    return true;
                });
            }
        }

        private void setupTTSPreferences() {
            ListPreference ttsPref = findPreference(getString(R.string.pref_text_to_speech_engine_key));
            if (ttsPref != null) addTTSList(ttsPref);
        }

        private void setupLogPreferences() {
            Preference share = findPreference(getString(R.string.pref_share_log_key));
            Preference clear = findPreference(getString(R.string.pref_clear_log_key));
            if (share != null) share.setOnPreferenceClickListener(p -> { shareLog(); return true; });
            if (clear != null) clear.setOnPreferenceClickListener(p -> { clearLog(); return true; });

            Preference backup = findPreference("pref_backup_settings_key");
            Preference restore = findPreference("pref_restore_settings_key");
            if (backup != null) backup.setOnPreferenceClickListener(p -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(Intent.EXTRA_TITLE, "soft_braille_keyboard_settings.json");
                backupLauncher.launch(intent);
                return true;
            });
            if (restore != null) restore.setOnPreferenceClickListener(p -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*");
                restoreLauncher.launch(intent);
                return true;
            });
        }

        // --- Backup Logic ---
        private void writeBackup(Uri uri) {
            boolean ok = false;
            if (uri != null) {
                try (OutputStream os = getActivity().getContentResolver().openOutputStream(uri)) {
                    ok = os != null && SettingsBackup.exportTo(getActivity(), os);
                } catch (Exception e) {
                    android.util.Log.e("PreferenceIME", "Backup error", e);
                }
            }
            Toast.makeText(getActivity(), ok ? R.string.msg_backup_saved : R.string.msg_backup_failed, Toast.LENGTH_SHORT).show();
        }

        private void confirmRestore(Uri uri) {
            if (uri == null) return;
            new android.app.AlertDialog.Builder(getActivity())
                    .setTitle(R.string.pref_restore_settings_title)
                    .setMessage(R.string.pref_restore_settings_confirm)
                    .setPositiveButton(R.string.btn_ok, (dialog, which) -> restoreBackup(uri))
                    .setNegativeButton(R.string.btn_cancel, null)
                    .show();
        }

        private void restoreBackup(Uri uri) {
            boolean ok = false;
            try (InputStream is = getActivity().getContentResolver().openInputStream(uri)) {
                ok = is != null && SettingsBackup.importFrom(getActivity(), is);
            } catch (Exception e) {
                android.util.Log.e("PreferenceIME", "Restore error", e);
            }
            Toast.makeText(getActivity(), ok ? R.string.msg_restore_done : R.string.msg_restore_failed, Toast.LENGTH_LONG).show();
            if (ok) getActivity().recreate();
        }

        private void setupVersionPreference() {
            Preference version = findPreference(getString(R.string.pref_app_version_key));
            if (version != null) {
                try {
                    String vName = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName;
                    version.setTitle(String.format(getString(R.string.pref_app_version_title), vName));
                } catch (Exception ignored) {}
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == 2002 && resultCode == android.app.Activity.RESULT_OK && data != null) {
                Uri uri = data.getData();
                if (uri != null && getActivity() != null) {
                    try {
                        getActivity().getContentResolver().takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        Options.writeStringPreferenceStringKey(getActivity(), 
                                getString(R.string.pref_custom_sound_theme_folder_uri_key), uri.toString());
                        SoundThemeManager.getInstance(getActivity()).loadSelectedTheme();
                        Toast.makeText(getActivity(), getString(R.string.toast_custom_sound_theme_folder_selected), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        android.util.Log.e("PreferenceIME", "Failed to take persistable URI permission", e);
                        Toast.makeText(getActivity(), getString(R.string.toast_custom_sound_theme_folder_error), Toast.LENGTH_SHORT).show();
                    }
                }
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            if (brailleParser != null) addTables(BrailleParser.STATUS_OK);
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (brailleParser != null) brailleParser.destroy();
            if (tts != null) tts.shutdown();
        }

        // --- Log Logic ---
        private void shareLog() {
            try {
                File logFile = new File(getActivity().getCacheDir(), "logcat.txt");
                Process process = Runtime.getRuntime().exec("logcat -d");
                try (InputStream is = process.getInputStream(); FileOutputStream fos = new FileOutputStream(logFile)) {
                    byte[] buffer = new byte[1024]; int read;
                    while ((read = is.read(buffer)) != -1) fos.write(buffer, 0, read);
                }
                Uri uri = FileProvider.getUriForFile(getActivity(), getActivity().getPackageName() + ".fileprovider", logFile);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(intent, getString(R.string.pref_share_log_title)));
            } catch (Exception e) {
                Toast.makeText(getActivity(), R.string.msg_error_logs, Toast.LENGTH_SHORT).show();
            }
        }

        private void clearLog() {
            try {
                Runtime.getRuntime().exec("logcat -c");
                Toast.makeText(getActivity(), R.string.msg_logs_cleared, Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
        }

        // --- Tables Logic ---
        private void addTables(int status) {
            ListPreference comp = findPreference(getString(R.string.pref_braille_computer_table_key));
            ListPreference literary = findPreference(getString(R.string.pref_braille_literary_table_key));
            MultiSelectListPreference switchPref = findPreference(getString(R.string.pref_switch_tables_key));

            if (comp == null && literary == null && switchPref == null) return;

            List<TableInfo> tables = (status == BrailleParser.STATUS_OK || status == BrailleParser.STATUS_TABLE_ERROR) 
                                     ? brailleParser.getTables(BrailleType.ALL) : null;
            if (tables == null) return;

            List<String> entries = new ArrayList<>(), values = new ArrayList<>();
            populateWithTables(tables, entries, values, true, null);
            if (switchPref != null) {
                switchPref.setEntries(entries.toArray(new String[0]));
                switchPref.setEntryValues(values.toArray(new String[0]));
            }

            entries.clear(); values.clear();
            populateWithTables(brailleParser.getTables(BrailleType.LITERARY), entries, values, true, brailleParser.getDefaultId(getActivity(), BrailleType.LITERARY));
            if (literary != null) {
                literary.setEntries(entries.toArray(new String[0]));
                literary.setEntryValues(values.toArray(new String[0]));
            }

            entries.clear(); values.clear();
            populateWithTables(brailleParser.getTables(BrailleType.COMPUTER), entries, values, true, brailleParser.getDefaultId(getActivity(), BrailleType.COMPUTER));
            if (comp != null) {
                comp.setEntries(entries.toArray(new String[0]));
                comp.setEntryValues(values.toArray(new String[0]));
            }
        }

        private void populateWithTables(List<TableInfo> tables, List<String> entries, List<String> values, boolean verbose, String defId) {
            if (tables == null || getActivity() == null) return;
            for (TableInfo t : tables) {
                String text = t.getId().startsWith("custom:") ? getString(R.string.custom_table_label_prefix) + " " + (t.getVariant() != null ? t.getVariant() : "table")
                                                              : t.getLocale().getDisplayName();
                if (verbose) {
                    String label = t.isEightDot() ? getString(R.string.grade_computer) : String.format(getString(R.string.grade_table), t.getGrade());
                    if (!t.isEightDot()) {
                        if ("it-g1".equals(t.getId())) {
                            label += " num-6";
                        } else if ("it-it-numerazione-canonica".equals(t.getId())) {
                            label += " num3456";
                        }
                    }
                    if (t.getVariant() != null && !t.getVariant().isEmpty() && !t.getId().startsWith("custom:")) label += " (" + t.getVariant() + ")";
                    text += ": " + label;
                }
                entries.add(text);
                values.add(t.getId().equals(defId) ? getString(R.string.pref_braille_table_auto) : t.getId());
            }
        }

        private void addOptions(ListPreference pref, OptionList option) {
            OptionList[] types = option.getValues();
            CharSequence[] entries = new CharSequence[types.length], values = new CharSequence[types.length];
            for (int i = 0; i < types.length; i++) {
                entries[i] = getString(types[i].getResource());
                values[i] = types[i].getValue();
            }
            pref.setEntries(entries);
            pref.setEntryValues(values);
        }

        private void addTTSList(final ListPreference pref) {
            tts = new TextToSpeech(getActivity(), status -> {
                List<EngineInfo> engines = tts.getEngines();
                Collections.sort(engines, (o1, o2) -> o1.label.toLowerCase().compareTo(o2.label.toLowerCase()));
                CharSequence[] entries = new CharSequence[engines.size()], values = new CharSequence[engines.size()];
                for (int i = 0; i < engines.size(); i++) {
                    entries[i] = engines.get(i).label;
                    values[i] = engines.get(i).name;
                }
                pref.setEntries(entries);
                pref.setEntryValues(values);
            });
        }
    }
}
