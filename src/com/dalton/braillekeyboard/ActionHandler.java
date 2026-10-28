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

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import android.database.Cursor;
import java.util.Set;
import java.util.HashSet;

import android.Manifest;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.Keyboard;
import androidx.core.content.ContextCompat;
import android.view.KeyEvent;
import android.view.inputmethod.InputMethodManager;
import android.os.Vibrator;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.util.Log;

import com.dalton.braillekeyboard.EditingUtilities.Word;
import com.dalton.braillekeyboard.EmojiDictionary.EmojiEntry;
import com.dalton.braillekeyboard.Options.KeyboardEcho;
import com.dalton.braillekeyboard.Options.KeyboardFeedback;
import com.dalton.braillekeyboard.Pad.Swipe;
import com.dalton.braillekeyboard.SpellChecker.SpellingSuggestionsReadyListener;
import com.dalton.braillekeyboard.SpellChecker.Suggestion;

/**
 * ActionHandler handles actions from a View or other interface and performs the
 * appropriate logic before returning the results back to the calling View in
 * the form of a callback. (Modernized for AndroidX and Dynamic Gestures).
 */
public class ActionHandler {
    private static final long DOUBLE_TOUCH_THRESHOLD = 1300;

    public static final int SOUND_TYPE_DEFAULT = 0;
    public static final int SOUND_TYPE_SPACE = 1;
    public static final int SOUND_TYPE_DELETE = 2;
    public static final int SOUND_TYPE_ENTER = 3;

    public interface OnActionListener {
        void onText(String format, String text, boolean isPasswordField);
        void onText(String format, String text, boolean isPasswordField, int mode);
        void onNotify(boolean vibrate, boolean playSound, int soundType);
        void onSetDots(boolean dot7, boolean dot8);
        void onSetLocale(Locale locale);
        void onShrink();
        void onPrivacy();
        void onShutup();
    }

    private enum Granularity {
        CHARACTER, WORD, LINE, ALL;
    }

    private enum EditAction {
        SELECT_ALL(R.string.select_all), COPY(android.R.string.copy), CUT(
                android.R.string.cut), PASTE(android.R.string.paste), SPEAK(
                R.string.speak_selection), DELETE(R.string.delete_selection);

        public final int resource;
        EditAction(int resource) { this.resource = resource; }

        private int getIndexOfThis() {
            EditAction[] values = EditAction.values();
            for (int i = 0; i < values.length; i++) if (this == values[i]) return i;
            return 0;
        }

        public EditAction next(ClipboardManager clipboard) {
            int i = getIndexOfThis();
            if (++i == values().length) i = 0;
            if (values()[i] == PASTE && !clipboard.hasPrimaryClip()) if (++i == values().length) i = 0;
            return values()[i];
        }
    }

    private final ClipboardManager clipboard;
    private final InputMethodManager inputManager;
    private final SpellChecker spellChecker;
    private final VoiceInput voiceInput = new VoiceInput();
    private final Context context;

    private EditAction editAction = EditAction.COPY;
    private long lastTouchTime = 0;
    private Swipe lastSwipe = Swipe.NONE;
    private KeyboardListener listener;
    private OnActionListener callback;
    private int directionThroughSuggestionList;
    private SpellChecker.Direction spellingDirection;
    private Suggestion spellingSuggestion;
    private String pendingAddToDictionary = null;
    private String pendingRemoveFromDictionary = null;

    private boolean emojiMode = false;
    private StringBuilder emojiSearchQuery = new StringBuilder();
    private List<EmojiEntry> emojiResults = new ArrayList<EmojiEntry>();
    private int emojiIndex = 0;

    public ActionHandler(Context context) {
        this.context = context;
        inputManager = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        spellChecker = new SpellChecker(context);
    }

    public void setCallback(OnActionListener callback) { this.callback = callback; }
    public void setKeyboardListener(KeyboardListener listener) { this.listener = listener; }
    public void shutdown() {
        pendingAddToDictionary = null;
        pendingRemoveFromDictionary = null;
        voiceInput.destroy();
        spellChecker.destroy();
    }

    public void closeSpellChecker() {
        if (spellChecker != null) {
            spellChecker.destroy();
        }
    }

    public void onStartInput(Locale locale) {
        pendingAddToDictionary = null;
        pendingRemoveFromDictionary = null;
        if (spellChecker != null) {
            spellChecker.onStartInput(locale);
        }
    }

    private static ToneGenerator toneGenerator;
    private static final Object toneLock = new Object();

    private static void playSpellingBeep() {
        synchronized (toneLock) {
            if (toneGenerator == null) {
                try {
                    toneGenerator = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                } catch (Exception e) {
                    Log.e("SBK_ActionHandler", "Failed to create ToneGenerator", e);
                }
            }
            if (toneGenerator != null) {
                try {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
                } catch (Exception e) {
                    Log.e("SBK_ActionHandler", "Failed to play tone", e);
                }
            }
        }
    }

    private static void triggerVibration(Context context, long duration) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(duration);
            }
        } catch (Exception e) {
            Log.e("SBK_ActionHandler", "Failed to vibrate", e);
        }
    }

    private String getTargetSpellingWord() {
        if (spellingSuggestion != null && spellCheckerMatchesWord()) {
            return spellingSuggestion.getOriginalWord();
        }
        Word word = EditingUtilities.getWord(listener);
        if (word != null && word.word != null && !word.word.trim().isEmpty()) {
            return word.word;
        }
        return null;
    }

    private void addWordToPersonalDictionary(Context context, String word) {
        if (word == null || word.trim().isEmpty()) {
            callback.onText("%s", context.getString(R.string.no_word_to_add), false);
            return;
        }
        word = word.trim();
        try {
            Locale locale = listener.getLocale();
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                android.provider.UserDictionary.Words.addWord(context, word, 250, null, locale);
            } else {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.UserDictionary.Words.WORD, word);
                values.put(android.provider.UserDictionary.Words.FREQUENCY, 250);
                values.put(android.provider.UserDictionary.Words.LOCALE, locale == null ? null : locale.toString());
                context.getContentResolver().insert(android.provider.UserDictionary.Words.CONTENT_URI, values);
            }
            callback.onText(context.getString(R.string.word_added_to_dictionary), word, false);
            if (spellChecker.isSpellCheckAvailable()) {
                doSpellCheck(context, SpellChecker.Direction.UNDER_CURSOR, 0, listener.getCursor());
            }
        } catch (Exception e) {
            android.util.Log.e("SBK_ActionHandler", "Failed to add word to UserDictionary", e);
        }
    }

    private void addWordToPersonalDictionaryNoAnnounce(Context context, String word) {
        if (word == null || word.trim().isEmpty()) {
            return;
        }
        word = word.trim();
        try {
            Locale locale = listener.getLocale();
            if (android.os.Build.VERSION.SDK_INT >= 16) {
                android.provider.UserDictionary.Words.addWord(context, word, 250, null, locale);
            } else {
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.UserDictionary.Words.WORD, word);
                values.put(android.provider.UserDictionary.Words.FREQUENCY, 250);
                values.put(android.provider.UserDictionary.Words.LOCALE, locale == null ? null : locale.toString());
                context.getContentResolver().insert(android.provider.UserDictionary.Words.CONTENT_URI, values);
            }
        } catch (Exception e) {
            android.util.Log.e("SBK_ActionHandler", "Failed to add word to UserDictionary", e);
        }
    }

    private void removeWordFromPersonalDictionaryNoAnnounce(Context context, String word) {
        deleteWordFromUserDictionaryCaseInsensitive(context, word);
    }

    private int deleteWordFromUserDictionaryCaseInsensitive(Context context, String word) {
        if (word == null || word.trim().isEmpty()) {
            return 0;
        }
        word = word.trim();
        String normalized = word.replace('\u2019', '\'');
        android.content.ContentResolver resolver = context.getContentResolver();
        
        Set<String> exactWordsToDelete = new HashSet<String>();
        exactWordsToDelete.add(word);
        exactWordsToDelete.add(normalized);
        exactWordsToDelete.add(word.toLowerCase());
        exactWordsToDelete.add(normalized.toLowerCase());
        
        Cursor cursor = null;
        try {
            cursor = resolver.query(
                    android.provider.UserDictionary.Words.CONTENT_URI,
                    new String[] { android.provider.UserDictionary.Words.WORD },
                    null, null, null
            );
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String dbWord = cursor.getString(0);
                    if (dbWord != null && (dbWord.equalsIgnoreCase(word) || dbWord.equalsIgnoreCase(normalized))) {
                        exactWordsToDelete.add(dbWord);
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.e("SBK_ActionHandler", "Failed to query UserDictionary", e);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        
        int totalDeleted = 0;
        String selection = android.provider.UserDictionary.Words.WORD + "=?";
        for (String target : exactWordsToDelete) {
            try {
                totalDeleted += resolver.delete(
                        android.provider.UserDictionary.Words.CONTENT_URI,
                        selection,
                        new String[] { target }
                );
            } catch (Exception e) {
                android.util.Log.e("SBK_ActionHandler", "Failed to delete word variant: " + target, e);
            }
        }
        return totalDeleted;
    }

    private void removeWordFromPersonalDictionary(Context context, String word) {
        if (word == null || word.trim().isEmpty()) {
            callback.onText("%s", context.getString(R.string.no_word_to_remove), false);
            return;
        }
        int rowsDeleted = deleteWordFromUserDictionaryCaseInsensitive(context, word);
        if (rowsDeleted > 0) {
            callback.onText(context.getString(R.string.word_removed_from_dictionary), word, false);
            if (spellChecker.isSpellCheckAvailable()) {
                doSpellCheck(context, SpellChecker.Direction.UNDER_CURSOR, 0, listener.getCursor());
            }
        } else {
            callback.onText(context.getString(R.string.word_not_in_dictionary), word, false);
        }
    }

    public void onFingerUp(Context context) {
        if (pendingAddToDictionary != null) {
            String word = pendingAddToDictionary;
            pendingAddToDictionary = null;
            addWordToPersonalDictionary(context, word);
        } else if (pendingRemoveFromDictionary != null) {
            String word = pendingRemoveFromDictionary;
            pendingRemoveFromDictionary = null;
            removeWordFromPersonalDictionary(context, word);
        }
    }

    public boolean handleSwipe(Context context, Swipe value) {
        if (voiceInput.isListening()) return true;

        BrailleAction action = GestureRepository.getActionForSwipe(context, value);
        
        String message = null;
        boolean notify = true;
        boolean setDots = false;
        boolean considerPassword = false;
        boolean dots[] = { false, false };
        boolean fastDoubleSwipe = fastDoubleSwipe(value, DOUBLE_TOUCH_THRESHOLD);

        if (emojiMode) {
            boolean emojiHandled = true;
            switch (value) {
                case FOUR_LEFT:
                    if (emojiSearchQuery.length() > 0) {
                        emojiSearchQuery.setLength(emojiSearchQuery.length() - 1);
                        updateEmojiResults();
                        callback.onText("%s", getEmojiSummary(), false);
                    } else callback.onText("%s", context.getString(R.string.emoji_search_query_empty), false);
                    break;
                case SIX_LEFT:
                    if (!emojiResults.isEmpty()) { emojiIndex = (emojiIndex - 1 + emojiResults.size()) % emojiResults.size(); message = getEmojiSummary(); }
                    else message = context.getString(R.string.emoji_no_results);
                    break;
                case SIX_RIGHT:
                    if (!emojiResults.isEmpty()) { emojiIndex = (emojiIndex + 1) % emojiResults.size(); message = getEmojiSummary(); }
                    else message = context.getString(R.string.emoji_no_results);
                    break;
                case SIX_UP:
                    if (!emojiResults.isEmpty()) {
                        EmojiEntry entry = emojiResults.get(emojiIndex);
                        listener.commitText(entry.emoji, 1);
                        String displayName = (entry.name != null && !entry.name.isEmpty()) ? entry.name : entry.keyword;
                        message = context.getString(R.string.emoji_inserted) + " " + displayName;
                        // Stay in emoji mode with the search and the selected emoji untouched, so the same emoji can be
                        // inserted several times in a row; the toggle gesture closes the mode.
                    } else message = context.getString(R.string.emoji_no_to_insert);
                    break;
                default: emojiHandled = false;
            }
            if (emojiHandled) { callback.onNotify(true, true, SOUND_TYPE_DEFAULT); if (message != null) callback.onText("%s", message, false); lastSwipe = value; return true; }
        }

        switch (action) {
        case MOVE_LEFT_CHAR: moveLeft(context, Granularity.CHARACTER); break;
        case MOVE_RIGHT_CHAR: moveRight(context, Granularity.CHARACTER); break;
        case MOVE_LEFT_WORD: moveLeft(context, Granularity.WORD); break;
        case MOVE_RIGHT_WORD: moveRight(context, Granularity.WORD); break;
        case MOVE_LEFT_LINE: moveLeft(context, Granularity.LINE); break;
        case MOVE_RIGHT_LINE: moveRight(context, Granularity.LINE); break;
        case MOVE_LEFT_ALL: moveLeft(context, Granularity.ALL); break;
        case MOVE_RIGHT_ALL: moveRight(context, Granularity.ALL); break;
        case BACKSPACE_CHAR: backspace(context, Granularity.CHARACTER, fastDoubleSwipe); break;
        case BACKSPACE_WORD: backspace(context, Granularity.WORD, fastDoubleSwipe); break;
        case BACKSPACE_LINE: backspace(context, Granularity.LINE, fastDoubleSwipe); break;
        case BACKSPACE_ALL: backspace(context, Granularity.ALL, fastDoubleSwipe); break;
        case READ_CHAR: message = getInput(Granularity.CHARACTER); considerPassword = true; break;
        case READ_WORD: message = getInput(Granularity.WORD); considerPassword = true; break;
        case READ_LINE: message = getInput(Granularity.LINE); considerPassword = true; break;
        case READ_ALL: message = getInput(Granularity.ALL); considerPassword = true; break;
        case SPACE: if (fastDoubleSwipe && handleDoubleSpace(context)) break; typeCharacter(context, (int) ' ', " "); break;
        case NEWLINE: typeCharacter(context, KeyEvent.KEYCODE_ENTER, context.getString(R.string.newline)); break;
        case VOICE_INPUT: doVoiceInput(context, fastDoubleSwipe); break;
        case TOGGLE_PRIVACY:
            boolean priv = Options.switchBooleanPreference(context, R.string.pref_privacy_key, Boolean.parseBoolean(context.getString(R.string.pref_privacy_default)));
            callback.onPrivacy(); message = priv ? context.getString(R.string.privacy_enabled) : context.getString(R.string.privacy_disabled); break;
        case TOGGLE_AUTO_CAPS:
            boolean caps = Options.switchBooleanPreference(context, R.string.pref_auto_caps_key, Boolean.parseBoolean(context.getString(R.string.pref_auto_caps_default)));
            message = caps ? context.getString(R.string.auto_caps_enabled) : context.getString(R.string.auto_caps_disabled); break;
        case TOGGLE_ECHO_PASSWORDS:
            boolean echoPassword = Options.switchBooleanPreference(context, R.string.pref_echo_passwords_key, false);
            message = echoPassword ? context.getString(R.string.speak_passwords) : context.getString(R.string.no_password_echo); break;
        case TOGGLE_MARK: message = context.getString(listener.toggleMark() ? R.string.set_mark : R.string.unset_mark); break;
        case TOGGLE_ABBREVIATIONS:
            boolean addSpaceEnabled = Options.toggleAbbreviationOption(context, 3);
            message = context.getString(addSpaceEnabled ? R.string.abbreviations_enabled
                    : R.string.abbreviations_disabled);
            break;
        case TOGGLE_ABBREVIATIONS_NO_SPACE:
            boolean spaceEnabled = Options.toggleAbbreviationOption(context, 1);
            message = context.getString(spaceEnabled ? R.string.abbreviations_no_space_enabled
                    : R.string.abbreviations_no_space_disabled);
            break;
        case TOGGLE_INSTANT_ABBREVIATIONS:
            boolean instantEnabled = Options.toggleAbbreviationOption(context, 2);
            message = context.getString(instantEnabled ? R.string.abbreviations_instant_enabled
                    : R.string.abbreviations_instant_disabled);
            break;
        case TOGGLE_MISSPELLING_SOUND:
            boolean misspellingSound = Options.switchBooleanPreference(context,
                    R.string.pref_misspelling_sound_key,
                    Boolean.parseBoolean(context.getString(R.string.pref_misspelling_sound_default)));
            message = context.getString(misspellingSound ? R.string.misspelling_sound_enabled
                    : R.string.misspelling_sound_disabled);
            break;
        case ADD_TO_DICTIONARY:
            String targetWord = getTargetSpellingWord();
            addWordToPersonalDictionary(context, targetWord);
            break;
        case REMOVE_FROM_DICTIONARY:
            String targetWordToRemove = getTargetSpellingWord();
            removeWordFromPersonalDictionary(context, targetWordToRemove);
            break;
        case CYCLE_KEYBOARD_FEEDBACK:
            KeyboardFeedback fb = KeyboardFeedback.valueOf(Integer.parseInt(Options.getStringPreference(context, R.string.pref_keyboard_feedback_key, KeyboardFeedback.SOUND.getValue())));
            fb = KeyboardFeedback.next(fb); Options.writeStringPreference(context, R.string.pref_keyboard_feedback_key, fb.getValue()); message = context.getString(fb.resource); break;
        case CYCLE_ECHO_FEEDBACK:
            KeyboardEcho echo = KeyboardEcho.valueOf(Integer.parseInt(Options.getStringPreference(context, R.string.pref_echo_feedback_key, KeyboardEcho.CHARACTER.getValue())));
            echo = KeyboardEcho.next(echo); Options.writeStringPreference(context, R.string.pref_echo_feedback_key, echo.getValue()); message = context.getString(echo.resource); break;
        case SWITCH_BRAILLE_TYPE:
            int bt = listener.switchBrailleType(); message = bt == 8 ? context.getString(R.string.grade_computer) : context.getString(R.string.grade_literary);
            callback.onSetLocale(listener.getLocale()); break;
        case SWITCH_BRAILLE_TABLE:
            message = listener.switchTable(); message = message == null ? context.getString(R.string.no_braille_table) : message;
            callback.onSetLocale(listener.getLocale()); break;
        case OPEN_SETTINGS:
            if (fastDoubleSwipe) { callback.onSetLocale(Locale.getDefault()); message = context.getString(R.string.show_settings); Intent intent = new Intent(context, PreferenceIME.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); }
            else message = context.getString(R.string.swipe_confirm_settings); break;
        case OPEN_INPUT_SWITCHER:
            if (fastDoubleSwipe) { message = context.getString(R.string.show_input_switcher); if (BrailleAccessibilityService.isServiceEnabled()) BrailleAccessibilityService.getInstance().clearPassthroughRegion(); inputManager.showInputMethodPicker(); }
            else message = context.getString(R.string.swipe_confirm_input); break;
        case CLOSE_KEYBOARD: message = context.getString(R.string.closing_keyboard); listener.closeKeyboard(); break;
        case SHRINK_KEYBOARD: message = context.getString(R.string.keyboard_shrink); callback.onShrink(); break;
        case WORD_COUNT:
            CharSequence text = listener.getAllText().text;
            if (text != null) message = String.format(context.getString(R.string.word_count), EditingUtilities.lineCount(text), EditingUtilities.wordCount(text), EditingUtilities.characterCount(text));
            break;
        case NEXT_FIELD_ACTION: nextAction(context); break;
        case SELECT_TEXT_ACTION: selectAction(context); break;
        case EMOJI_MODE_TOGGLE:
            emojiMode = !emojiMode;
            if (emojiMode) { emojiSearchQuery.setLength(0); emojiResults.clear(); emojiIndex = 0; Locale loc = listener.getLocale(); String lang = loc != null ? loc.getDisplayLanguage() : "English"; message = String.format(context.getString(R.string.emoji_search_mode_on), lang); }
            else message = context.getString(R.string.emoji_search_mode_off); break;
        case EDIT_SELECT_ALL: doSelectAll(context); break;
        case EDIT_COPY: doCopy(context); break;
        case EDIT_CUT: doCut(context); break;
        case EDIT_PASTE: doPaste(context); break;
        case EDIT_SPEAK: doSpeakSelection(context); break;
        case EDIT_DELETE: doDeleteSelection(context); break;
        case SPELL_CHECK_LEFT: doSpellCheck(context, SpellChecker.Direction.LEFT, 0, listener.getCursor()); break;
        case SPELL_CHECK_RIGHT: doSpellCheck(context, SpellChecker.Direction.RIGHT, 0, listener.getCursor()); break;
        case SPELL_CHECK_NEXT_SUGGESTION: nextSpellCheckSuggestion(context); break;
        case SPELL_CHECK_PREV_SUGGESTION: previousSpellCheckSuggestion(context); break;
        case SET_DOT_7: if (listener.getDots() == 8) { setDots = true; dots[0] = true; } else message = context.getString(R.string.unknown_character); break;
        case SET_DOT_8: if (listener.getDots() == 8) { setDots = true; dots[1] = true; } else message = context.getString(R.string.unknown_character); break;
        case NONE: return false;
        default: notify = false;
        }

        int soundType = SOUND_TYPE_DEFAULT;
        switch (action) {
            case SPACE:
                soundType = SOUND_TYPE_SPACE;
                break;
            case NEWLINE:
                soundType = SOUND_TYPE_ENTER;
                break;
            case BACKSPACE_CHAR:
            case BACKSPACE_WORD:
            case BACKSPACE_LINE:
            case BACKSPACE_ALL:
                soundType = SOUND_TYPE_DELETE;
                break;
        }
        callback.onNotify(notify, notify, soundType);
        if (message != null) callback.onText("%s", message, considerPassword ? listener.isPasswordField() : false);
        if (setDots) callback.onSetDots(dots[0], dots[1]);
        lastSwipe = value;
        return true;
    }

    private boolean isConfirmed(Context context, boolean fastDoubleTouch) {
        if (!fastDoubleTouch) {
            callback.onText("%s", context.getString(R.string.swipe_confirm), false);
            return false;
        }
        return true;
    }

    public void handleCharacter(Context context, byte value) {
        if (voiceInput.isListening()) return;
        if (emojiMode) {
            String trans = listener.translateLiteral(value);
            if (trans != null && trans.length() > 0) { emojiSearchQuery.append(trans); updateEmojiResults(); callback.onNotify(true, true, SOUND_TYPE_DEFAULT); callback.onText("%s", getEmojiSummary(), false); }
            else callback.onNotify(true, false, SOUND_TYPE_DEFAULT);
            return;
        }
        lastSwipe = Swipe.NONE;
        String result = listener.handleTypedCharacter(value);
        if (result == null) callback.onText("%s", context.getString(R.string.unknown_character), false);
        else {
            callback.onNotify(true, true, SOUND_TYPE_DEFAULT);
            String character = echoCharacter(context, result);
            result = (character == null) ? "" : character;
            if (!(result = result.trim()).equals("")) callback.onText("%s", result, listener.isPasswordField());
        }
        callback.onSetDots(false, false);
    }

    private void selectAction(Context context) {
        switch (editAction) {
        case COPY: doCopy(context); break;
        case CUT: doCut(context); break;
        case PASTE: doPaste(context); break;
        case SPEAK: doSpeakSelection(context); break;
        case DELETE: doDeleteSelection(context); break;
        case SELECT_ALL: doSelectAll(context); break;
        }
    }

    private void doCopy(Context context) { performContextMenuAction(context, true, android.R.id.copy, R.string.copied, R.string.copy_error); listener.deselect(); }
    private void doCut(Context context) { if (performContextMenuAction(context, true, android.R.id.cut, android.R.string.cut, R.string.cut_error)) listener.setCursorToStartOfSelection(); }
    private void doPaste(Context context) { if (performContextMenuAction(context, false, android.R.id.paste, R.string.pasted, R.string.paste_error)) listener.setSelection(listener.getCursor()); }
    private void doSpeakSelection(Context context) { performAction(context, EditAction.SPEAK); }
    private void doDeleteSelection(Context context) { performAction(context, EditAction.DELETE); }
    private void doSelectAll(Context context) { listener.selectAll(); callback.onText("%s", context.getString(R.string.selected_all), false); }

    private boolean performContextMenuAction(Context context, boolean requiresSelection, int code, int successString, int errorString) {
        if (!requiresSelection || listener.setSelection()) {
            if (listener.performContextMenuAction(code)) { callback.onText("%s", context.getString(successString), false); return true; }
            else { callback.onText("%s", context.getString(errorString), false); return false; }
        } else { callback.onText("%s", context.getString(R.string.mark_not_set), false); return false; }
    }

    private boolean performAction(Context context, EditAction action) {
        if (listener.setSelection()) {
            CharSequence text = listener.getSelectedText(0); listener.deselect();
            switch (action) {
            case SPEAK: callback.onText("%s", text == null ? context.getString(R.string.blank) : text.toString(), listener.isPasswordField()); break;
            case DELETE:
                if (listener.deleteSelection() && text != null) callback.onText(context.getString(R.string.deleted), text.toString(), listener.isPasswordField());
                else callback.onText("%s", context.getString(R.string.nothing_to_delete), false); break;
            default: return false;
            }
            return true;
        }
        callback.onText("%s", context.getString(R.string.mark_not_set), false); return false;
    }

    private void nextAction(Context context) { editAction = editAction.next(clipboard); callback.onText("%s", context.getString(editAction.resource), false); }

    private void moveLeft(Context context, Granularity granularity) {
        EditingUtilities.Word word = null; listener.finishComposingText();
        if (listener.isSelectAll()) { granularity = Granularity.ALL; listener.setSelection(0); }
        switch (granularity) {
        case CHARACTER: word = EditingUtilities.moveToPreviousCharacter(listener); break;
        case WORD: word = EditingUtilities.moveToPreviousWord(listener); break;
        case LINE: word = EditingUtilities.moveToPreviousLine(listener); break;
        case ALL: word = EditingUtilities.moveToHome(listener); break;
        }
        if (word != null) callback.onText("%s", !word.moveLeft ? context.getString(R.string.start_of_text) : word.word, word.moveLeft && listener.isPasswordField());
    }

    private void moveRight(Context context, Granularity granularity) {
        EditingUtilities.Word word = null; listener.finishComposingText();
        if (listener.isSelectAll()) { granularity = Granularity.ALL; listener.setSelection(0); }
        switch (granularity) {
        case CHARACTER: word = EditingUtilities.moveToNextCharacter(listener); break;
        case WORD: word = EditingUtilities.moveToNextWord(listener); break;
        case LINE: word = EditingUtilities.moveToNextLine(listener); break;
        case ALL: word = EditingUtilities.moveToEnd(listener); break;
        }
        if (word != null) callback.onText("%s", !word.moveRight ? context.getString(R.string.end_of_text) : word.word, word.moveRight && listener.isPasswordField());
    }

    private boolean backspace(Context context, Granularity granularity, boolean fastDoubleTouch) {
        EditingUtilities.Word word = null; boolean canDelete = true;
        switch (granularity) {
        case CHARACTER: word = listener.handleBackspace(); if (word != null) break; listener.finishComposingText(); word = EditingUtilities.moveToPreviousCharacter(listener); break;
        case WORD: listener.finishComposingText(); Word space = EditingUtilities.skipSepBackwards(listener, EditingUtilities.WORD_SEPARATORS); word = EditingUtilities.getWord(listener);
            if (word != null) { if (space != null) word.charsBefore += space.charsBefore; if (word.word.length() > word.charsBefore) word.word = word.word.substring(0, word.charsBefore); EditingUtilities.moveToPreviousWord(listener); } break;
        case LINE: if (canDelete = isConfirmed(context, fastDoubleTouch)) { listener.finishComposingText(); int cur = listener.getCursor(); word = EditingUtilities.getLine(listener); if (word != null && (cur > 0 || word.charsAfter > 0)) { int move = (cur - word.charsBefore) > 0 ? 1 : 0; listener.setSelection(cur - move - word.charsBefore); word.charsBefore += move + word.charsAfter; } } break;
        case ALL: if (canDelete = isConfirmed(context, fastDoubleTouch)) { listener.finishComposingText(); word = EditingUtilities.moveToHome(listener); word.word = EditingUtilities.getAllText(listener); word.charsBefore = word.word.length(); } break;
        }
        return performDelete(context, word, canDelete);
    }

    private boolean performDelete(Context context, EditingUtilities.Word word, boolean canDelete) {
        if (canDelete && word != null) {
            if (word.charsBefore > 0 || word.charsAfter > 0) { if (listener.deleteSurroundingText(0, word.charsBefore)) { if (word.word != null) callback.onText(context.getString(R.string.deleted), word.word, listener.isPasswordField()); return true; } return false; }
            else { callback.onText("%s", context.getString(R.string.nothing_to_delete), false); return true; }
        }
        return false;
    }

    private void typeCharacter(Context context, int code, String charName) {
        boolean expanded = listener.checkAndExpandAbbreviation(KeyboardListener.EXPANSION_TYPE_SPACE);
        listener.finishComposingText();
        Word word = EditingUtilities.getWord(listener);
        String msg = word == null ? null : word.word.substring(0, word.charsBefore);

        boolean addSpace = Options.getSharedPreferences(context).getBoolean("ABBREVIATIONS_ADD_SPACE", false);
        if (expanded && !addSpace) {
            if (msg != null) {
                msg = echoWord(context, msg);
                if (msg != null && !msg.isEmpty()) {
                    callback.onText("%s", msg, listener.isPasswordField());
                }
            }
            return;
        }

        listener.onKey(code);
        if ((msg = echoWord(context, msg)) == null) msg = echoCharacter(context, charName);
        callback.onText("%s", msg, listener.isPasswordField());
        if (Options.getBooleanPreference(context, R.string.pref_echo_misspellings_key, Boolean.parseBoolean(context.getString(R.string.pref_echo_misspellings_default))) && spellChecker.isSpellCheckAvailable()) doSpellCheck(context, SpellChecker.Direction.UNDER_CURSOR, 0, listener.getCursor() - 2);
    }

    private boolean handleDoubleSpace(Context context) {
        if (Options.getBooleanPreference(context, R.string.pref_double_space_period_key, Boolean.parseBoolean(context.getString(R.string.pref_double_space_period_default)))) {
            CharSequence text = listener.getTextBeforeCursor(2);
            if (text != null && text.length() == 2 && Character.isWhitespace(text.charAt(1)) && Character.isLetterOrDigit(text.charAt(0))) { listener.deleteSurroundingText(1, 0); listener.onKey('.'); typeCharacter(context, ' ', " "); return true; }
        }
        return false;
    }

    private boolean fastDoubleSwipe(Swipe swipe, long threshold) { if ((lastTouchTime + threshold) > System.currentTimeMillis() && lastSwipe == swipe) { lastTouchTime = 0; return true; } else { lastSwipe = swipe; lastTouchTime = System.currentTimeMillis(); return false; } }

    private String getInput(Granularity granularity) {
        switch (granularity) {
        case CHARACTER: return EditingUtilities.getCharacter(listener);
        case WORD: Word w = EditingUtilities.getWord(listener); return w == null ? null : w.word;
        case LINE: Word l = EditingUtilities.getLine(listener); return l == null ? null : l.word;
        case ALL: return EditingUtilities.getAllText(listener);
        default: return "";
        }
    }

    public boolean doVoiceInput(final Context context, boolean fastDoubleSwipe) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!fastDoubleSwipe) callback.onText("%s", context.getString(R.string.voice_input_enable), false);
            else { Intent intent = new Intent(context, IntentActivity.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); intent.setAction(context.getString(R.string.action_record_audio_permission)); intent.putExtra(context.getString(R.string.require_record_audio_now), true); context.startActivity(intent); }
            return false;
        }
        VoiceInput.TextReadyListener trl = new VoiceInput.TextReadyListener() {
            @Override public void onTextReady(String text) { if (text != null && text.length() > 0) { CharSequence before = listener.getTextBeforeCursor(1); if (before != null && before.length() > 0 && !Character.isWhitespace(before.charAt(0))) listener.onKey(' '); listener.commitText(text, 1); callback.onText("%s", text, listener.isPasswordField()); } }
            @Override public void onError(int error) { callback.onText("%s", String.format(context.getString(R.string.voice_input_error), error), false); }
        };
        if (voiceInput.start(context, trl, listener.getLocale())) callback.onShutup();
        else { callback.onText("%s", context.getString(R.string.voice_input_is_not_available), false); return false; }
        return true;
    }

    private static String echoCharacter(Context context, String character) { if ((Integer.parseInt(Options.getStringPreference(context, R.string.pref_echo_feedback_key, KeyboardEcho.CHARACTER.getValue())) & KeyboardEcho.CHARACTER.value) != 0) return character; return null; }
    private static String echoWord(Context context, String word) { if ((Integer.parseInt(Options.getStringPreference(context, R.string.pref_echo_feedback_key, KeyboardEcho.CHARACTER.getValue())) & KeyboardEcho.WORD.value) != 0) return word; return null; }

    private void doSpellCheck(final Context context, SpellChecker.Direction direction, int move, int cursor) {
        SpellingSuggestionsReadyListener sl = new SpellingSuggestionsReadyListener() {
            @Override public void suggestionsReady(Suggestion result) { spellingSuggestion = result; if (result != null || spellingDirection == SpellChecker.Direction.UNDER_CURSOR) handleSpellingSuggestion(context); else callback.onText("%s", context.getString(R.string.no_more_misspellings), false); }
        };
        String text = getInput(Granularity.ALL); spellingDirection = direction; directionThroughSuggestionList = move;
        if (text != null && text.length() > 0) { if (!spellChecker.checkSpelling(sl, text, cursor, direction)) callback.onText("%s", context.getString(R.string.spellcheck_not_supported), false); }
        else callback.onText("%s", context.getString(R.string.blank), false);
    }

    private void handleSpellingSuggestion(Context context) {
        boolean pass = false;
        String msg = null;
        if (spellingSuggestion == null && directionThroughSuggestionList != 0) {
            msg = context.getString(R.string.word_correct);
        } else if (spellingSuggestion != null && spellingDirection == SpellChecker.Direction.UNDER_CURSOR) {
            spellingDirection = null;
            if (directionThroughSuggestionList != 0) {
                listener.setSelection(spellingSuggestion.offset);
            }
            if (directionThroughSuggestionList > 0) {
                nextSpellCheckSuggestion(context);
            } else if (directionThroughSuggestionList < 0) {
                previousSpellCheckSuggestion(context);
            } else {
                if (spellingSuggestion.isUserDictWord) {
                    msg = null;
                } else {
                    if (Options.getBooleanPreference(context,
                            R.string.pref_misspelling_sound_key,
                            Boolean.parseBoolean(context.getString(R.string.pref_misspelling_sound_default)))) {
                        int feedbackVal = Integer.parseInt(Options.getStringPreference(context,
                                R.string.pref_keyboard_feedback_key,
                                KeyboardFeedback.SOUND.getValue()));
                        boolean playSound = (feedbackVal == KeyboardFeedback.SOUND.value 
                                          || feedbackVal == KeyboardFeedback.ALL.value);
                        boolean vibrate = (feedbackVal == KeyboardFeedback.VIBRATE.value 
                                        || feedbackVal == KeyboardFeedback.ALL.value);
                        if (playSound) {
                            playSpellingBeep();
                        }
                        if (vibrate) {
                            triggerVibration(context, 125);
                        }
                    } else {
                        msg = context.getString(R.string.word_misspelled);
                    }
                }
            }
        } else if (spellingSuggestion != null) {
            pass = true;
            if (spellingSuggestion.isAddToDictionaryOption()) {
                String originalWord = spellingSuggestion.getOriginalWord();
                msg = String.format(
                        context.getString(R.string.action_add_to_dictionary_option),
                        originalWord);
                commitSuggestionText(originalWord, spellingSuggestion);
                pendingAddToDictionary = originalWord;
                pendingRemoveFromDictionary = null;
            } else if (spellingSuggestion.isRemoveFromDictionaryOption()) {
                String originalWord = spellingSuggestion.getOriginalWord();
                msg = String.format(
                        context.getString(R.string.action_remove_from_dictionary_option),
                        originalWord);
                commitSuggestionText(originalWord, spellingSuggestion);
                pendingRemoveFromDictionary = originalWord;
                pendingAddToDictionary = null;
            } else {
                pendingAddToDictionary = null;
                pendingRemoveFromDictionary = null;
                if (spellingSuggestion.isUserDictWord && spellingSuggestion.isMisspelledWord()) {
                    msg = String.format(
                            context.getString(R.string.word_correction_correct),
                            spellingSuggestion.getCurrent());
                } else {
                    msg = spellingSuggestion.isMisspelledWord() ? String.format(
                            context.getString(R.string.word_correction_misspelled),
                            spellingSuggestion.getCurrent()) : spellingSuggestion.getCurrent();
                }
                commitSuggestionText(spellingSuggestion.getCurrent(), spellingSuggestion);
            }
        }
        if (msg != null) {
            callback.onText("%s", msg, listener.isPasswordField() && pass, Speech.QUEUE_ADD);
        }
    }

    private void commitSuggestionText(String text, Suggestion suggestion) {
        listener.setSelection(suggestion.offset);
        listener.deleteSurroundingText(0, suggestion.getLength());
        listener.commitText(text, 1);
        
        // Se ci sono solo spazi e punteggiatura fino alla fine del testo dopo la parola appena inserita,
        // spostiamo il cursore oltre di essi per evitare che la parola successiva si attacchi.
        int cursorShift = 0;
        CharSequence afterText = listener.getTextAfterCursor(20);
        if (afterText != null && afterText.length() > 0) {
            boolean onlyPunctuationOrWhitespace = true;
            for (int i = 0; i < afterText.length(); i++) {
                char c = afterText.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    onlyPunctuationOrWhitespace = false;
                    break;
                }
            }
            if (onlyPunctuationOrWhitespace) {
                cursorShift = afterText.length();
            }
        }
        if (cursorShift > 0) {
            listener.setSelection(suggestion.offset + text.length() + cursorShift);
        }
        
        suggestion.setLength();
    }

    private void nextSpellCheckSuggestion(Context context) { if (spellingSuggestion != null && spellCheckerMatchesWord()) { spellingSuggestion.next(); handleSpellingSuggestion(context); return; } doSpellCheck(context, SpellChecker.Direction.UNDER_CURSOR, 1, listener.getCursor()); }
    private void previousSpellCheckSuggestion(Context context) { if (spellingSuggestion != null && spellCheckerMatchesWord()) { spellingSuggestion.prev(); handleSpellingSuggestion(context); return; } doSpellCheck(context, SpellChecker.Direction.UNDER_CURSOR, -1, listener.getCursor()); }

    private boolean spellCheckerMatchesWord() {
        String text = getInput(Granularity.ALL);
        if (text != null && spellingSuggestion != null) {
            int offset = spellingSuggestion.offset;
            int length = spellingSuggestion.getLength();
            int cursor = listener.getCursor();
            if (text.length() > 0 && (offset + length) <= text.length()) {
                if (cursor >= offset) {
                    if (cursor <= (offset + length)) {
                        return text.substring(offset, offset + length).equals(
                                spellingSuggestion.getCurrent());
                    } else {
                        // Check if characters between end of the word (offset + length) and cursor are only whitespace or punctuation
                        boolean onlyPunctOrWhite = true;
                        for (int i = offset + length; i < cursor && i < text.length(); i++) {
                            char c = text.charAt(i);
                            if (Character.isLetterOrDigit(c)) {
                                onlyPunctOrWhite = false;
                                break;
                            }
                        }
                        if (onlyPunctOrWhite) {
                            return text.substring(offset, offset + length).equals(
                                    spellingSuggestion.getCurrent());
                        }
                    }
                }
            }
        }
        return false;
    }

    private void updateEmojiResults() { emojiResults = EmojiDictionary.search(context, emojiSearchQuery.toString(), listener.getLocale()); emojiIndex = 0; }
    private String getEmojiSummary() { if (emojiResults.isEmpty()) return String.format(context.getString(R.string.emoji_no_results_query), emojiSearchQuery.toString()); EmojiEntry entry = emojiResults.get(emojiIndex); String name = (entry.name != null && !entry.name.isEmpty()) ? entry.name : entry.keyword; return String.format(context.getString(R.string.emoji_count_summary), name, emojiIndex + 1, emojiResults.size()); }
}
