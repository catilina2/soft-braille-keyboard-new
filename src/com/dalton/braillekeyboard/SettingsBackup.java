/*
 * Copyright (C) 2026 The Soft Braille Keyboard Authors
 */

package com.dalton.braillekeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Backs up and restores every preference of the keyboard (gestures, feedback, braille tables,
 * TTS, etc.) as a single typed JSON document.
 */
public class SettingsBackup {
    private static final String TAG = "SettingsBackup";

    private static final String FORMAT = "soft-braille-keyboard-settings";
    private static final int VERSION = 1;

    private static final String KEY_FORMAT = "format";
    private static final String KEY_VERSION = "version";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_TYPE = "type";
    private static final String KEY_VALUE = "value";

    private static final String TYPE_STRING = "string";
    private static final String TYPE_BOOLEAN = "boolean";
    private static final String TYPE_INT = "int";
    private static final String TYPE_LONG = "long";
    private static final String TYPE_FLOAT = "float";
    private static final String TYPE_STRING_SET = "stringset";

    // The sync folder grant is tied to this installation, so it is neither saved nor overwritten.
    private static final Set<String> DEVICE_SPECIFIC_KEYS =
            new HashSet<String>(Arrays.asList("sync_folder_uri"));

    public static boolean exportTo(Context context, OutputStream out) {
        try {
            JSONObject settings = new JSONObject();
            for (Map.Entry<String, ?> entry : Options.getSharedPreferences(context).getAll().entrySet()) {
                if (DEVICE_SPECIFIC_KEYS.contains(entry.getKey())) continue;
                JSONObject item = encode(entry.getValue());
                if (item != null) settings.put(entry.getKey(), item);
            }

            JSONObject root = new JSONObject();
            root.put(KEY_FORMAT, FORMAT);
            root.put(KEY_VERSION, VERSION);
            root.put(KEY_SETTINGS, settings);

            out.write(root.toString(2).getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Backup error", e);
            return false;
        }
    }

    public static boolean importFrom(Context context, InputStream in) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int len;
            while ((len = in.read(chunk)) > 0) buffer.write(chunk, 0, len);

            JSONObject root = new JSONObject(buffer.toString("UTF-8"));
            if (!FORMAT.equals(root.optString(KEY_FORMAT))) return false;
            JSONObject settings = root.getJSONObject(KEY_SETTINGS);

            // Decode everything before touching the preferences, so a bad file changes nothing.
            Map<String, Object> decoded = new HashMap<String, Object>();
            Iterator<String> keys = settings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (DEVICE_SPECIFIC_KEYS.contains(key)) continue;
                decoded.put(key, decode(settings.getJSONObject(key)));
            }

            SharedPreferences prefs = Options.getSharedPreferences(context);
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : prefs.getAll().keySet()) {
                if (!DEVICE_SPECIFIC_KEYS.contains(key)) editor.remove(key);
            }
            for (Map.Entry<String, Object> entry : decoded.entrySet()) {
                put(editor, entry.getKey(), entry.getValue());
            }
            return editor.commit();
        } catch (Exception e) {
            Log.e(TAG, "Restore error", e);
            return false;
        }
    }

    private static JSONObject encode(Object value) throws Exception {
        JSONObject item = new JSONObject();
        if (value instanceof String) {
            item.put(KEY_TYPE, TYPE_STRING).put(KEY_VALUE, value);
        } else if (value instanceof Boolean) {
            item.put(KEY_TYPE, TYPE_BOOLEAN).put(KEY_VALUE, value);
        } else if (value instanceof Integer) {
            item.put(KEY_TYPE, TYPE_INT).put(KEY_VALUE, value);
        } else if (value instanceof Long) {
            item.put(KEY_TYPE, TYPE_LONG).put(KEY_VALUE, value);
        } else if (value instanceof Float) {
            item.put(KEY_TYPE, TYPE_FLOAT).put(KEY_VALUE, ((Float) value).doubleValue());
        } else if (value instanceof Set) {
            JSONArray array = new JSONArray();
            for (Object element : (Set<?>) value) array.put(String.valueOf(element));
            item.put(KEY_TYPE, TYPE_STRING_SET).put(KEY_VALUE, array);
        } else {
            return null;
        }
        return item;
    }

    private static Object decode(JSONObject item) throws Exception {
        String type = item.getString(KEY_TYPE);
        if (TYPE_STRING.equals(type)) return item.getString(KEY_VALUE);
        if (TYPE_BOOLEAN.equals(type)) return item.getBoolean(KEY_VALUE);
        if (TYPE_INT.equals(type)) return item.getInt(KEY_VALUE);
        if (TYPE_LONG.equals(type)) return item.getLong(KEY_VALUE);
        if (TYPE_FLOAT.equals(type)) return (float) item.getDouble(KEY_VALUE);
        if (TYPE_STRING_SET.equals(type)) {
            JSONArray array = item.getJSONArray(KEY_VALUE);
            Set<String> set = new HashSet<String>();
            for (int i = 0; i < array.length(); i++) set.add(array.getString(i));
            return set;
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
    }
}
