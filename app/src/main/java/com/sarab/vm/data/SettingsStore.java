package com.sarab.vm.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.sarab.vm.core.EmulatorSettings;

/** Keeps {@link EmulatorSettings} in SharedPreferences. */
public final class SettingsStore {

    private final SharedPreferences prefs;

    public SettingsStore(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public EmulatorSettings load() {
        EmulatorSettings s = new EmulatorSettings();
        try {
            s.accel = EmulatorSettings.Accel.valueOf(prefs.getString("accel", "TCG"));
        } catch (IllegalArgumentException e) {
            s.accel = EmulatorSettings.Accel.TCG;
        }
        s.network = prefs.getBoolean("network", true);
        s.haptics = prefs.getBoolean("haptics", true);
        s.defaultDiskGb = prefs.getInt("defaultDiskGb", 16);
        return s;
    }

    public void save(EmulatorSettings s) {
        prefs.edit()
                .putString("accel", s.accel.name())
                .putBoolean("network", s.network)
                .putBoolean("haptics", s.haptics)
                .putInt("defaultDiskGb", s.defaultDiskGb)
                .apply();
    }
}
