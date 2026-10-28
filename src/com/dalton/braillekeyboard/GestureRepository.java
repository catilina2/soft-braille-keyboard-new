package com.dalton.braillekeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import com.dalton.braillekeyboard.Pad.Swipe;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages the mapping between gestures (Swipe) and logical actions (BrailleAction).
 * Loads user preferences and provides default values.
 */
public class GestureRepository {
    private static final String PREF_GESTURE_PREFIX = "gesture_mapping_";
    private static Map<Swipe, BrailleAction> defaultMappings;

    static {
        defaultMappings = new HashMap<Swipe, BrailleAction>();
        defaultMappings.put(Swipe.ONE_LEFT, BrailleAction.MOVE_LEFT_CHAR);
        defaultMappings.put(Swipe.ONE_RIGHT, BrailleAction.MOVE_RIGHT_CHAR);
        defaultMappings.put(Swipe.ONE_DOWN, BrailleAction.CYCLE_KEYBOARD_FEEDBACK);
        defaultMappings.put(Swipe.ONE_UP, BrailleAction.READ_CHAR);
        
        defaultMappings.put(Swipe.TWO_LEFT, BrailleAction.MOVE_LEFT_WORD);
        defaultMappings.put(Swipe.TWO_RIGHT, BrailleAction.MOVE_RIGHT_WORD);
        defaultMappings.put(Swipe.TWO_UP, BrailleAction.READ_WORD);
        defaultMappings.put(Swipe.TWO_DOWN, BrailleAction.CYCLE_ECHO_FEEDBACK);

        defaultMappings.put(Swipe.LEFT_TWO_DOWN, BrailleAction.CLOSE_KEYBOARD);
        defaultMappings.put(Swipe.RIGHT_TWO_DOWN, BrailleAction.NONE);
        defaultMappings.put(Swipe.LEFT_TWO_LEFT, BrailleAction.NONE);
        defaultMappings.put(Swipe.LEFT_TWO_RIGHT, BrailleAction.NONE);
        defaultMappings.put(Swipe.LEFT_TWO_UP, BrailleAction.NONE);
        defaultMappings.put(Swipe.RIGHT_TWO_LEFT, BrailleAction.NONE);
        defaultMappings.put(Swipe.RIGHT_TWO_RIGHT, BrailleAction.NONE);
        defaultMappings.put(Swipe.RIGHT_TWO_UP, BrailleAction.NONE);

        defaultMappings.put(Swipe.THREE_LEFT, BrailleAction.MOVE_LEFT_LINE);
        defaultMappings.put(Swipe.THREE_RIGHT, BrailleAction.MOVE_RIGHT_LINE);
        defaultMappings.put(Swipe.THREE_UP, BrailleAction.READ_LINE);
        defaultMappings.put(Swipe.THREE_DOWN, BrailleAction.SET_DOT_7);
        
        defaultMappings.put(Swipe.FOUR_LEFT, BrailleAction.BACKSPACE_CHAR);
        defaultMappings.put(Swipe.FOUR_RIGHT, BrailleAction.SPACE);
        defaultMappings.put(Swipe.FIVE_RIGHT, BrailleAction.SPACE);
        defaultMappings.put(Swipe.FOUR_DOWN, BrailleAction.NEWLINE);
        defaultMappings.put(Swipe.FOUR_UP, BrailleAction.TOGGLE_PRIVACY);
        
        defaultMappings.put(Swipe.FIVE_LEFT, BrailleAction.BACKSPACE_WORD);
        defaultMappings.put(Swipe.FIVE_DOWN, BrailleAction.OPEN_INPUT_SWITCHER);
        defaultMappings.put(Swipe.FIVE_UP, BrailleAction.OPEN_SETTINGS);
        
        defaultMappings.put(Swipe.SIX_LEFT, BrailleAction.BACKSPACE_LINE);
        defaultMappings.put(Swipe.SIX_RIGHT, BrailleAction.NEXT_FIELD_ACTION);
        defaultMappings.put(Swipe.SIX_UP, BrailleAction.SELECT_TEXT_ACTION);
        defaultMappings.put(Swipe.SIX_DOWN, BrailleAction.SET_DOT_8);
        
        defaultMappings.put(Swipe.EMOJI_MODE, BrailleAction.NONE);
        defaultMappings.put(Swipe.AUTOCAPS_TOGGLE, BrailleAction.WORD_COUNT);
        
        defaultMappings.put(Swipe.HOLD_ONE_DOWN, BrailleAction.WORD_COUNT);
        defaultMappings.put(Swipe.HOLD_ONE_UP, BrailleAction.TOGGLE_AUTO_CAPS);
        defaultMappings.put(Swipe.HOLD_ONE_RIGHT, BrailleAction.TOGGLE_MARK);
        defaultMappings.put(Swipe.HOLD_ONE_LEFT, BrailleAction.SHRINK_KEYBOARD);
        
        defaultMappings.put(Swipe.HOLD_SIX_LEFT, BrailleAction.MOVE_LEFT_ALL);
        defaultMappings.put(Swipe.HOLD_SIX_RIGHT, BrailleAction.MOVE_RIGHT_ALL);
        defaultMappings.put(Swipe.HOLD_SIX_DOWN, BrailleAction.TOGGLE_ECHO_PASSWORDS);
        defaultMappings.put(Swipe.HOLD_SIX_UP, BrailleAction.READ_ALL);
        
        defaultMappings.put(Swipe.HOLD_THREE_LEFT, BrailleAction.BACKSPACE_ALL);
        defaultMappings.put(Swipe.HOLD_THREE_RIGHT, BrailleAction.SWITCH_BRAILLE_TYPE);
        defaultMappings.put(Swipe.HOLD_THREE_DOWN, BrailleAction.SWITCH_BRAILLE_TABLE);
        defaultMappings.put(Swipe.HOLD_THREE_UP, BrailleAction.VOICE_INPUT);
        
        defaultMappings.put(Swipe.HOLD_FOUR_LEFT, BrailleAction.SPELL_CHECK_LEFT);
        defaultMappings.put(Swipe.HOLD_FOUR_RIGHT, BrailleAction.SPELL_CHECK_RIGHT);
        defaultMappings.put(Swipe.HOLD_FOUR_DOWN, BrailleAction.SPELL_CHECK_NEXT_SUGGESTION);
        defaultMappings.put(Swipe.HOLD_FOUR_UP, BrailleAction.SPELL_CHECK_PREV_SUGGESTION);

        defaultMappings.put(Swipe.HOLD_TWO_LEFT, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_TWO_RIGHT, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_TWO_UP, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_TWO_DOWN, BrailleAction.EMOJI_MODE_TOGGLE);

        defaultMappings.put(Swipe.HOLD_FIVE_LEFT, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_FIVE_RIGHT, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_FIVE_UP, BrailleAction.NONE);
        defaultMappings.put(Swipe.HOLD_FIVE_DOWN, BrailleAction.TOGGLE_ABBREVIATIONS);
    }

    public static BrailleAction getActionForSwipe(Context context, Swipe swipe) {
        if (swipe == Swipe.NONE || swipe == Swipe.UNKNOWN) {
            return BrailleAction.NONE;
        }

        SharedPreferences prefs = Options.getSharedPreferences(context);
        String actionName = prefs.getString(PREF_GESTURE_PREFIX + swipe.name(), null);

        if (actionName != null) {
            try {
                return BrailleAction.valueOf(actionName);
            } catch (IllegalArgumentException e) {
                // If saved action is no longer valid, fallback to default
            }
        }

        // If no preference is saved, use default
        BrailleAction defaultAction = defaultMappings.get(swipe);
        return defaultAction != null ? defaultAction : BrailleAction.NONE;
    }

    public static void setActionForSwipe(Context context, Swipe swipe, BrailleAction action) {
        SharedPreferences prefs = Options.getSharedPreferences(context);
        prefs.edit().putString(PREF_GESTURE_PREFIX + swipe.name(), action.name()).apply();
    }
    
    public static void resetToDefaults(Context context) {
        SharedPreferences prefs = Options.getSharedPreferences(context);
        SharedPreferences.Editor editor = prefs.edit();
        for (Swipe swipe : Swipe.values()) {
            editor.remove(PREF_GESTURE_PREFIX + swipe.name());
        }
        editor.apply();
    }

    public static BrailleAction getDefaultActionForSwipe(Swipe swipe) {
        BrailleAction defaultAction = defaultMappings.get(swipe);
        return defaultAction != null ? defaultAction : BrailleAction.NONE;
    }

    public static boolean exportToJson(Context context, java.io.File file) {
        try {
            JSONObject json = new JSONObject();
            android.content.SharedPreferences prefs = Options.getSharedPreferences(context);
            java.util.Map<String, ?> allEntries = prefs.getAll();
            for (java.util.Map.Entry<String, ?> entry : allEntries.entrySet()) {
                if (entry.getKey().startsWith("gesture_mapping_")) {
                    json.put(entry.getKey(), entry.getValue());
                }
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(file);
            fos.write(json.toString(2).getBytes("UTF-8"));
            fos.close();
            return true;
        } catch (Exception e) {
            android.util.Log.e("GestureRepository", "Export error", e);
            return false;
        }
    }

    public static boolean importFromJson(Context context, java.io.File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            String jsonStr = new String(data, "UTF-8");
            JSONObject json = new JSONObject(jsonStr);
            
            android.content.SharedPreferences.Editor editor = Options.getSharedPreferences(context).edit();
            // Reset existing mappings first
            for (Swipe swipe : Swipe.values()) {
                editor.remove("gesture_mapping_" + swipe.name());
            }
            
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("gesture_mapping_")) {
                    editor.putString(key, json.getString(key));
                }
            }
            editor.apply();
            return true;
        } catch (Exception e) {
            android.util.Log.e("GestureRepository", "Import error", e);
            return false;
        }
    }
}
