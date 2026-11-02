package com.sarab.vm.core;

/** X11 keysym values used by the VNC protocol. */
public final class Keysyms {
    private Keysyms() { }

    public static final int BACKSPACE = 0xFF08;
    public static final int TAB = 0xFF09;
    public static final int RETURN = 0xFF0D;
    public static final int ESCAPE = 0xFF1B;
    public static final int DELETE = 0xFFFF;
    public static final int INSERT = 0xFF63;
    public static final int HOME = 0xFF50;
    public static final int LEFT = 0xFF51;
    public static final int UP = 0xFF52;
    public static final int RIGHT = 0xFF53;
    public static final int DOWN = 0xFF54;
    public static final int PAGE_UP = 0xFF55;
    public static final int PAGE_DOWN = 0xFF56;
    public static final int END = 0xFF57;
    public static final int F1 = 0xFFBE;
    public static final int SHIFT_L = 0xFFE1;
    public static final int CONTROL_L = 0xFFE3;
    public static final int ALT_L = 0xFFE9;
    public static final int SUPER_L = 0xFFEB;
    public static final int SPACE = 0x0020;

    /** F1..F12 → keysym. */
    public static int function(int n) {
        if (n < 1 || n > 12) throw new IllegalArgumentException("F" + n);
        return F1 + (n - 1);
    }

    /** Keysym for a typed character (Latin-1 maps directly, the rest uses the Unicode range). */
    public static int forChar(int codePoint) {
        if (codePoint == '\n' || codePoint == '\r') return RETURN;
        if (codePoint == '\t') return TAB;
        if (codePoint == '\b') return BACKSPACE;
        if ((codePoint >= 0x20 && codePoint <= 0x7E) || (codePoint >= 0xA0 && codePoint <= 0xFF)) {
            return codePoint;
        }
        return 0x01000000 | codePoint;
    }
}
