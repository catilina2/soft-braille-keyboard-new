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
import com.dalton.braillekeyboard.Pad.Swipe;
import com.dalton.braillekeyboard.Pad.Coords;
import com.dalton.braillekeyboard.BrailleParser.BrailleType;
import com.dalton.braillekeyboard.Options.KeyboardFeedback;
import com.dalton.braillekeyboard.Options.KeyboardEcho;
import com.dalton.braillekeyboard.Options.KeyboardType;
import com.dalton.braillekeyboard.Options.OptionList;
import com.dalton.braillekeyboard.EditingUtilities.Word;
import com.dalton.braillekeyboard.EmojiDictionary.EmojiEntry;
import com.dalton.braillekeyboard.SpellChecker.Suggestion;
import com.dalton.braillekeyboard.SpellChecker.SpellingSuggestionsReadyListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.Keyboard;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import com.googlecode.eyesfree.braille.translate.TableInfo;

/**
 * Implementation of an Input method service for Android (Modernized).
 */
public class BrailleIME extends InputMethodService implements KeyboardListener {
    private final List<Byte> cells = new ArrayList<Byte>();
    private final StringBuilder composingText = new StringBuilder();
    private final Handler mAccessibilityHandler = new Handler(Looper.getMainLooper());

    private BrailleParser brailleParser;
    private BrailleView brailleView = null;
    private int caps;
    private int cursor = -1;
    private int mark = -1;
    private boolean predictionOn;
    private boolean selectAll = false;
    private boolean mIsFinishing = false;

    private final View.OnLayoutChangeListener mLayoutChangeListener = new View.OnLayoutChangeListener() {
        @Override
        public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                   int oldLeft, int oldTop, int oldRight, int oldBottom) {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                updatePassthroughRegion();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (brailleParser == null) {
            brailleParser = new BrailleParser(this,
                    new BrailleParser.BrailleParserListener() {
                        @Override
                        public void onTranslatorReady(int status) {
                            brailleParserReady(status);
                        }
                    });
        }
    }

    private int mImeOptions = 0;
    private boolean mKeepAwakeSuspended = false;

    /** Keeps the screen on while the keyboard is open in a text field (option "Keep the screen on while typing"). */
    private void updateKeepAwake() {
        boolean enabled = Options.getBooleanPreference(this, R.string.pref_keep_screen_on_key,
                Boolean.parseBoolean(getString(R.string.pref_keep_screen_on_default)));
        setKeepAwake(enabled && !mKeepAwakeSuspended);
    }

    private void setKeepAwake(boolean on) {
        if (brailleView != null) brailleView.setKeepScreenOn(on);
    }

    /** Enter performs the editor action (send, go, search, done) unless the field asked for a plain new line. */
    private boolean enterSendsAction() {
        if ((mImeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false;
        int action = mImeOptions & EditorInfo.IME_MASK_ACTION;
        return action == EditorInfo.IME_ACTION_SEND || action == EditorInfo.IME_ACTION_GO
                || action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE;
    }

    /** After sending, let the screen turn off again until the next text is typed. */
    private void suspendKeepAwake() {
        mKeepAwakeSuspended = true;
        setKeepAwake(false);
    }

    private void resumeKeepAwake() {
        if (mKeepAwakeSuspended) {
            mKeepAwakeSuspended = false;
            updateKeepAwake();
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        mIsFinishing = false;
        updateKeepAwake();
        if (brailleView != null) {
            brailleView.setVisibility(View.VISIBLE);
            brailleView.onStartInput(getLocale());
        }
        scheduleAccessibilityUpdate(5, 400);
    }

    private void scheduleAccessibilityUpdate(final int retriesLeft, long delay) {
        mAccessibilityHandler.removeCallbacksAndMessages(null);
        mAccessibilityHandler.postDelayed(() -> {
            if (brailleView != null && brailleView.isShown() && brailleView.getWidth() > 0) updatePassthroughRegion();
            if (retriesLeft > 0 && !mIsFinishing) scheduleAccessibilityUpdate(retriesLeft - 1, 400);
        }, delay);
    }

    private void updatePassthroughRegion() {
        if (brailleView == null || !brailleView.isShown() || brailleView.getVisibility() != View.VISIBLE) return;
        BrailleAccessibilityService accService = BrailleAccessibilityService.getInstance();
        if (accService != null) {
            if (brailleView.getShrinkKeyboard()) { accService.clearPassthroughRegion(); return; }
            int[] loc = new int[2]; brailleView.getLocationOnScreen(loc);
            if (brailleView.getWidth() > 0 && brailleView.getHeight() > 0) {
                accService.updatePassthroughRegion(loc[0], loc[1], brailleView.getWidth(), brailleView.getHeight());
                brailleView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            }
        }
    }

    @Override
    public View onCreateInputView() {
        super.onCreateInputView();
        brailleView = (BrailleView) getLayoutInflater().inflate(R.layout.keyboard, null);
        brailleView.removeOnLayoutChangeListener(mLayoutChangeListener);
        brailleView.addOnLayoutChangeListener(mLayoutChangeListener);
        return brailleView;
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        mIsFinishing = false; selectAll = false; mark = -1; predictionOn = false;
        cells.clear(); composingText.setLength(0);
        mAccessibilityHandler.postDelayed(() -> updateShiftState(), 50);
        
        if (!BrailleAccessibilityService.isServiceEnabled() && !BrailleAccessibilityService.isJieshuoActive(this)) {
            if (!Options.getBooleanPreference(this, R.string.pref_has_asked_accessibility_service_key, false)) {
                Intent intent = new Intent(this, IntentActivity.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.setAction("com.dalton.braillekeyboard.ACTION_ENABLE_ACCESSIBILITY"); startActivity(intent);
                Options.writeBooleanPreference(this, R.string.pref_has_asked_accessibility_service_key, true);
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        mIsFinishing = false;
        mImeOptions = info != null ? info.imeOptions : 0;
        if (!restarting) mKeepAwakeSuspended = false;     // apps often restart the input after a send: stay released
        updateKeepAwake();
        if (!restarting && brailleView != null) {
            brailleView.onInitialiseForInput(this, this);
        } else if (restarting && brailleView != null) {
            brailleView.onStartInput(getLocale());
        }
        brailleParser.setTranslator(this);
        scheduleAccessibilityUpdate(3, 400);
    }

    @Override public void onFinishInputView(boolean finishingInput) { super.onFinishInputView(finishingInput); setKeepAwake(false); if (brailleView != null) brailleView.close(); }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        mIsFinishing = true;
        setKeepAwake(false);
        finishComposingText(false);
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        setKeepAwake(false);
        if (brailleView != null) {
            brailleView.setVisibility(View.GONE);
            brailleView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        }
        mIsFinishing = true;
        finishComposingText(false);
        mAccessibilityHandler.removeCallbacksAndMessages(null);
        clearAccessibilityState();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        setKeepAwake(false);
        mIsFinishing = true; mAccessibilityHandler.removeCallbacksAndMessages(null);
        if (brailleParser != null) { brailleParser.destroy(); brailleParser = null; }
        clearAccessibilityState();
    }

    private void clearAccessibilityState() {
        BrailleAccessibilityService accService = BrailleAccessibilityService.getInstance();
        if (accService != null) accService.clearPassthroughRegion();
    }

    @Override
    public void onComputeInsets(Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (brailleView != null && brailleView.getShrinkKeyboard()) {
            outInsets.contentTopInsets = brailleView.getTop();
            outInsets.visibleTopInsets = brailleView.getTop();
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE;
        }
    }

    @Override public boolean onEvaluateFullscreenMode() { return brailleView != null ? !brailleView.getShrinkKeyboard() : false; }
    private void brailleParserReady(int status) { if (status == BrailleParser.STATUS_OK && brailleView != null) brailleView.setLocale(getLocale()); }

    @Override public ExtractedText getAllText() { InputConnection ic = getCurrentInputConnection(); return ic != null ? ic.getExtractedText(new ExtractedTextRequest(), 0) : null; }
    @Override public CharSequence getTextBeforeCursor(int n) { InputConnection ic = getCurrentInputConnection(); return ic != null ? ic.getTextBeforeCursor(n, 0) : null; }
    @Override public CharSequence getTextAfterCursor(int n) { InputConnection ic = getCurrentInputConnection(); return ic != null ? ic.getTextAfterCursor(n, 0) : null; }
    @Override public CharSequence getSelectedText(int flags) { InputConnection ic = getCurrentInputConnection(); return ic != null ? ic.getSelectedText(flags) : null; }

    @Override
    public boolean setSelection() {
        int cur = !selectAll ? getCursor() : 0;
        int[] pos = getSelectionBoundaries(cur);
        return mark >= 0 && pos != null ? setSelection(pos[0], pos[1]) : false;
    }

    @Override public boolean setSelection(int cursor) { if (selectAll) { toggleMark(); selectAll = false; } finishComposingText(); this.cursor = cursor; return setSelection(cursor, cursor); }
    private boolean setSelection(int start, int end) { InputConnection ic = getCurrentInputConnection(); if (ic == null) return false; finishComposingText(); return ic.setSelection(start, end); }
    @Override public boolean performContextMenuAction(int id) { InputConnection ic = getCurrentInputConnection(); return ic != null ? ic.performContextMenuAction(id) : false; }
    @Override public boolean deleteSurroundingText(int before, int after) { InputConnection ic = getCurrentInputConnection(); if (ic == null) return false; selectAll = false; return ic.deleteSurroundingText(before, after); }

    @Override
    public boolean deleteSelection() {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return false;
        int cur = (this.cursor != -1) ? this.cursor : getCursor();
        int[] pos = getSelectionBoundaries(cur);
        if (pos == null || pos[0] == pos[1]) return false;
        mark = -1; selectAll = false; this.cursor = pos[0];
        ic.beginBatchEdit();
        try { finishComposingText(false); ic.setSelection(pos[1], pos[1]); ic.deleteSurroundingText(pos[1] - pos[0], 0); }
        finally { ic.endBatchEdit(); }
        return true;
    }

    @Override
    public boolean toggleMark() {
        int cur = getCursor();
        if (cur == mark || selectAll) { mark = -1; selectAll = false; } else mark = cur;
        return mark != -1;
    }

    @Override
    public int getCursor() {
        ExtractedText et = getAllText();
        if (et != null && et.selectionStart == et.selectionEnd) cursor = et.startOffset + et.selectionStart;
        else cursor = -1;
        return cursor;
    }

    @Override public boolean deselect() { if (selectAll) return false; ExtractedText et = getAllText(); if (et == null) return false; int end = et.startOffset + et.selectionEnd; return setSelection(end, end); }
    @Override public boolean setCursorToStartOfSelection() { int cur = getCursor(); if (cur == -1 || mark == -1) { ExtractedText et = getAllText(); if (et != null && mark != -1) cur = Math.min(et.startOffset + et.selectionStart, mark); else return false; } else cur = Math.min(cur, mark); return setSelection(cur, cur); }

    @Override
    public boolean selectAll() {
        getCursor(); ExtractedText et = getAllText();
        if (et != null) { mark = et.text.length(); setSelection(0, mark); selectAll = true; }
        return selectAll;
    }

    @Override public boolean isSelectAll() { return selectAll; }
    @Override public Locale getLocale() { return brailleParser != null ? brailleParser.getTable(this).getLocale() : null; }
    @Override public int getDots() { return brailleParser != null ? brailleParser.getBrailleType(this).dots : -1; }

    @Override
    public String handleTypedCharacter(byte dots) {
        if (brailleParser != null) {
            InputConnection ic = getCurrentInputConnection(); String snapshot = "";
            if (ic != null) { CharSequence before = ic.getTextBeforeCursor(20, 0); if (before != null) snapshot = before.toString(); }
            if (dots == 0) {
                boolean expanded = checkAndExpandAbbreviation(KeyboardListener.EXPANSION_TYPE_SPACE);
                finishComposingText(true);
                boolean addSpace = Options.getSharedPreferences(this).getBoolean("ABBREVIATIONS_ADD_SPACE", false);
                if (expanded && !addSpace) {
                    return "";
                }
                return " ";
            }
            
            Locale loc = getLocale();
            boolean isEng = loc != null && loc.getLanguage().equals(Locale.ENGLISH.getLanguage());
            if (isEng && dots == 32 && cells.contains((byte) 60)) finishComposingText(true);
            
            String oldText = composingText.toString(); setCells(dots);
            String text = brailleParser.backTranslate(this, cells.toArray(new Byte[cells.size()]));
            if (text != null) {
                text = compose(text.subSequence(0, text.length()), snapshot);
                checkAndExpandAbbreviation(KeyboardListener.EXPANSION_TYPE_INSTANT);
            } else { cells.remove(cells.size() - 1); return null; }
            return text != null ? stringDifference(oldText, text) : null;
        }
        return null;
    }

    private String compose(CharSequence text, String snapshot) {
        resumeKeepAwake();
        if (composingText.length() == 0) updateShiftState();
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return text.toString();
        if (selectAll) { toggleMark(); selectAll = false; }
        text = capitalise(text);
        ic.beginBatchEdit();
        try {
            if (predictionOn) { composingText.setLength(0); composingText.append(text); ic.setComposingText(composingText.toString(), composingText.length()); }
            else if (text.length() > 0) {
                String prev = composingText.toString(), curr = text.toString();
                CharSequence beforeCursor = ic.getTextBeforeCursor(curr.length(), 0);
                if (beforeCursor != null && beforeCursor.length() > 0 && curr.startsWith(beforeCursor.toString())) {
                    String beforeStr = beforeCursor.toString();
                    if (composingText.length() > 0) { if (beforeStr.length() > composingText.length()) prev = beforeStr; }
                    else if (snapshot.length() == 0 || !snapshot.endsWith(beforeStr)) prev = beforeStr;
                }
                int commonLen = 0; while (commonLen < prev.length() && commonLen < curr.length() && prev.charAt(commonLen) == curr.charAt(commonLen)) commonLen++;
                int delCount = prev.length() - commonLen; if (delCount > 0) ic.deleteSurroundingText(delCount, 0);
                if (commonLen < curr.length()) ic.commitText(curr.subSequence(commonLen, curr.length()), 1);
                composingText.setLength(0); composingText.append(text);
            }
        } finally { ic.endBatchEdit(); }
        return text.toString();
    }

    private CharSequence capitalise(CharSequence text) {
        if (Options.getBooleanPreference(this, R.string.pref_auto_caps_key, Boolean.parseBoolean(getString(R.string.pref_auto_caps_default)))) {
            if (caps != 0 && text != null && text.length() > 0) text = String.valueOf(Character.toUpperCase(text.charAt(0))) + text.subSequence(1, text.length());
        }
        return text;
    }

    @Override
    public void onKey(int keyCode) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        if (selectAll) { toggleMark(); selectAll = false; }
        finishComposingText();
        resumeKeepAwake();
        switch (keyCode) {
        case KeyEvent.KEYCODE_DEL: keyDownUp(ic, KeyEvent.KEYCODE_DEL); break;
        case KeyEvent.KEYCODE_ENTER: case '\n': keyDownUp(ic, KeyEvent.KEYCODE_ENTER); if (enterSendsAction()) suspendKeepAwake(); break;
        default:
            if (keyCode >= '0' && keyCode <= '9') keyDownUp(ic, keyCode - '0' + KeyEvent.KEYCODE_0);
            else ic.commitText(String.valueOf((char) keyCode), 1);
            break;
        }
    }

    private void keyDownUp(InputConnection ic, int keyEventCode) { if (ic == null) return; ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyEventCode)); ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyEventCode)); }

    private int[] getSelectionBoundaries(int cursor) {
        if (mark == -1) return null;
        ExtractedText et = getAllText(); if (et == null || et.text == null) return null;
        int cur = cursor; if (cur == -1) cur = et.startOffset + et.selectionEnd;
        int len = et.startOffset + et.text.length();
        int effMark = Math.max(0, Math.min(mark, len)), effCur = Math.max(0, Math.min(cur, len));
        int[] arr = new int[2]; arr[0] = Math.min(effCur, effMark); arr[1] = Math.max(effCur, effMark);
        if (arr[1] < len) arr[1]++;
        return arr;
    }

    @Override
    public EditingUtilities.Word handleBackspace() {
        if (cells.size() > 1) {
            int cur = getCursor(); String deleted = "";
            InputConnection ic = getCurrentInputConnection();
            CharSequence before = getTextBeforeCursor(1); if (before != null && before.length() > 0) deleted = before.toString();
            finishComposingText(true, false);
            cells.remove(cells.size() - 1);
            if (ic != null && cur > 0) { ic.setSelection(cur - 1, cur - 1); this.cursor = cur - 1; }
            if (cells.size() > 1) {
                String text = brailleParser.backTranslate(this, cells.toArray(new Byte[cells.size()]));
                if (text != null) { composingText.setLength(0); composingText.append(text); }
            } else composingText.setLength(0);
            EditingUtilities.Word word = new EditingUtilities.Word(deleted, 1, 0); word.moveLeft = true;
            return word;
        }
        return null;
    }

    @Override public void finishComposingText() { finishComposingText(true); }
    private void finishComposingText(boolean commit) { finishComposingText(commit, true); }
    private void finishComposingText(boolean commit, boolean clearCells) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) { composingText.setLength(0); if (clearCells) cells.clear(); return; }
        if (composingText.length() > 0) { if (predictionOn && commit) ic.commitText(composingText, 1); composingText.setLength(0); }
        if (clearCells) cells.clear();
    }

    private void setCells(byte dots) { if (cells.size() == 0) cells.add((byte) 0); cells.add(dots); }
    private static String stringDifference(String str1, String str2) { int i = -1; while (++i < Math.min(str1.length(), str2.length()) && Character.toLowerCase(str1.charAt(i)) == Character.toLowerCase(str2.charAt(i))) ; return i >= str2.length() ? str2 : str2.substring(i, str2.length()); }

    private void updateShiftState() {
        caps = 0; EditorInfo info = getCurrentInputEditorInfo(); InputConnection ic = getCurrentInputConnection();
        if (info != null && info.inputType != InputType.TYPE_NULL && ic != null) caps = ic.getCursorCapsMode(info.inputType);
    }

    @Override
    public void commitText(String text, int newCursorPosition) {
        InputConnection ic = getCurrentInputConnection(); if (ic == null) return;
        resumeKeepAwake();
        if (selectAll) { toggleMark(); selectAll = false; }
        checkAndExpandAbbreviation(KeyboardListener.EXPANSION_TYPE_SPACE);
        finishComposingText();
        updateShiftState(); text = capitalise(text.subSequence(0, text.length())).toString();
        ic.commitText(text, newCursorPosition);
    }

    @Override public void closeKeyboard() { setKeepAwake(false); if (brailleView != null) { brailleView.setVisibility(View.GONE); brailleView.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); } requestHideSelf(0); }
    @Override public String translateOnly(byte dots) { if (brailleParser != null) { List<Byte> l = new ArrayList<Byte>(); l.add((byte) 0); l.add(dots); return brailleParser.backTranslate(this, l.toArray(new Byte[l.size()])); } return null; }
    @Override public String translateLiteral(byte dots) { if (brailleParser != null) { List<Byte> l = new ArrayList<Byte>(); l.add(dots); return brailleParser.backTranslateLiteral(this, l.toArray(new Byte[l.size()])); } return null; }

    @Override
    public boolean checkAndExpandAbbreviation(int type) {
        boolean enabled = false;
        if (type == EXPANSION_TYPE_INSTANT) {
            enabled = Options.getSharedPreferences(this).getBoolean("ABBREVIATIONS_INSTANT", false);
        } else if (type == EXPANSION_TYPE_SPACE) {
            boolean spaceEnabled = Options.getBooleanPreference(this, R.string.pref_abbreviations_enabled_key,
                    Boolean.parseBoolean(getString(R.string.pref_abbreviations_enabled_default)));
            boolean addSpaceEnabled = Options.getSharedPreferences(this).getBoolean("ABBREVIATIONS_ADD_SPACE", false);
            enabled = spaceEnabled || addSpaceEnabled;
        }

        if (!enabled) return false;

        if (composingText.length() > 0) {
            String word = composingText.toString();
            int cellCount = (type == EXPANSION_TYPE_INSTANT) ? cells.size() - 1 : -1;
            String expansion = AbbreviationManager.getInstance(this).getExpansion(word, cellCount, this);
            
            if (expansion != null) {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) return false;
                
                ic.beginBatchEdit();
                try {
                    ic.deleteSurroundingText(composingText.length(), 0);
                    ic.commitText(expansion, 1);
                    composingText.setLength(0);
                    cells.clear();
                    return true;
                } finally {
                    ic.endBatchEdit();
                }
            }
        }
        return false;
    }

    @Override
    public boolean hasComposingText() {
        return composingText.length() > 0;
    }

    @Override public byte[] getDotsForText(String text) { return brailleParser != null ? brailleParser.getDotsForText(text) : null; }
    @Override public byte[] getDotsForTextLiteral(String text) { return brailleParser != null ? brailleParser.getDotsForTextLiteral(this, text) : null; }
    @Override public String backTranslate(byte[] cells) { return brailleParser != null ? brailleParser.backTranslate(this, cells) : null; }

    @Override
    public boolean isPasswordField() {
        EditorInfo info = getCurrentInputEditorInfo(); if (info == null) return false;
        if ((info.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        int var = info.inputType & InputType.TYPE_MASK_VARIATION;
        return var == InputType.TYPE_TEXT_VARIATION_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || var == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }

    @Override public int switchBrailleType() { return brailleParser != null ? brailleParser.switchBrailleType(this).dots : -1; }
    @Override public String switchTable() { return brailleParser != null ? brailleParser.switchTable(this) : null; }
}
