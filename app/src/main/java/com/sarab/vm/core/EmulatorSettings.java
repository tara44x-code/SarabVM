package com.sarab.vm.core;

/** App-wide emulator options (the Settings screen). Pure Java. */
public final class EmulatorSettings {

    public enum Accel { TCG, KVM }

    /** TCG works everywhere. KVM needs root and only helps aarch64 guests on an arm64 phone. */
    public Accel accel = Accel.TCG;
    public boolean network = true;
    public boolean haptics = true;
    public int defaultDiskGb = 16;
}
