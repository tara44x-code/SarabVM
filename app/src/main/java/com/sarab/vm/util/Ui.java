package com.sarab.vm.util;

import com.sarab.vm.core.Lang;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sarab.vm.R;
import com.sarab.vm.core.VmConfig;

/** Colours and small builders so every screen matches the Sarab design. */
public final class Ui {
    private Ui() { }

    // Colours: dark (default) or light, chosen in Settings. Set by init() before a screen is built.
    public static int BG, BG_DEEP, NAV, SURFACE, SURFACE_2, BORDER, DIVIDER, TEXT, TEXT_2, MUTED;
    public static int ACCENT, ON_ACCENT, ACCENT_BG, SAND, ON_SAND, SAND_BG, SAND_BORDER;
    private static boolean light;

    static {
        applyColors(false);
    }

    private static void applyColors(boolean isLight) {
        light = isLight;
        if (isLight) {
            BG = 0xFFF5F8F8; BG_DEEP = 0xFFE6EDED; NAV = 0xFFFFFFFF; SURFACE = 0xFFFFFFFF; SURFACE_2 = 0xFFEEF3F3;
            BORDER = 0xFFD3DDDF; DIVIDER = 0xFFE1E8E9; TEXT = 0xFF0F1A1D; TEXT_2 = 0xFF33474C; MUTED = 0xFF56696E;
            ACCENT = 0xFF0B7F74; ON_ACCENT = 0xFFFFFFFF; ACCENT_BG = 0xFFE0F4F1;
            SAND = 0xFFB45309; ON_SAND = 0xFFFFFFFF; SAND_BG = 0xFFFFF1E3; SAND_BORDER = 0xFFF2C79B;
        } else {
            BG = 0xFF0B1215; BG_DEEP = 0xFF070C0E; NAV = 0xFF0F181B; SURFACE = 0xFF131D21; SURFACE_2 = 0xFF1A272C;
            BORDER = 0xFF24343A; DIVIDER = 0xFF1E2C31; TEXT = 0xFFE8F1F2; TEXT_2 = 0xFFC9D6D9; MUTED = 0xFF8FA5AB;
            ACCENT = 0xFF2EC4B6; ON_ACCENT = 0xFF062320; ACCENT_BG = 0xFF10201F;
            SAND = 0xFFF4A259; ON_SAND = 0xFF2B1606; SAND_BG = 0xFF2A1A0C; SAND_BORDER = 0xFF5A3A1F;
        }
    }

    public static boolean isLight() {
        return light;
    }

    /** Language + theme from Settings. Call first thing in every screen's onCreate. */
    public static void init(Activity a) {
        SharedPreferences p = a.getSharedPreferences("ui", Context.MODE_PRIVATE);
        Lang.setEnglish("en".equals(p.getString("lang", "ar")));
        applyColors("light".equals(p.getString("theme", "dark")));
        a.setTheme(light ? R.style.Theme_Sarab_Light : R.style.Theme_Sarab);
        Window w = a.getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(NAV);
        int flags = 0;
        if (light) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Changes a stored UI preference ("lang" → ar/en, "theme" → dark/light). */
    public static void setPref(Context c, String key, String value) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString(key, value).apply();
    }

    public static String pref(Context c, String key, String def) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE).getString(key, def);
    }

    /** Identifies the current language + theme, so a screen can rebuild itself after a change. */
    public static String stamp() {
        return (Lang.isEnglish() ? "en" : "ar") + (light ? "L" : "D");
    }

    public static int dir() {
        return Lang.isEnglish() ? View.LAYOUT_DIRECTION_LTR : View.LAYOUT_DIRECTION_RTL;
    }

    /** "Back" arrow pointing the right way for the language. */
    public static String backGlyph() {
        return Lang.isEnglish() ? "‹" : "›";
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static GradientDrawable rounded(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        return d;
    }

    /** Background with a touch ripple. */
    public static Drawable clickable(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable content = rounded(c, fill, radiusDp, stroke, strokeDp);
        GradientDrawable mask = rounded(c, 0xFFFFFFFF, radiusDp, 0, 0);
        return new RippleDrawable(ColorStateList.valueOf(light ? 0x22000000 : 0x33FFFFFF), content, mask);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    /** Monospace, left-to-right text for technical values like "x86_64 · 4 GB". */
    public static TextView mono(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        return t;
    }

    public static TextView primaryButton(Context c, CharSequence label) {
        TextView b = text(c, label, 16, ON_ACCENT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(clickable(c, ACCENT, 16, 0, 0));
        b.setMinHeight(dp(c, 52));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    public static TextView secondaryButton(Context c, CharSequence label) {
        TextView b = text(c, label, 15, TEXT, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(clickable(c, SURFACE_2, 14, BORDER, 1));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** Square icon-like button with a short text glyph (we avoid icon libraries). */
    public static TextView iconButton(Context c, CharSequence glyph, String description, int fg) {
        TextView b = text(c, glyph, 18, fg, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(clickable(c, SURFACE, 12, BORDER, 1));
        b.setContentDescription(description);
        b.setClickable(true);
        b.setFocusable(true);
        int s = dp(c, 44);
        b.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return b;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams weight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    public static LinearLayout.LayoutParams margins(Context c, int w, int h, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMarginStart(dp(c, start));
        p.topMargin = dp(c, top);
        p.setMarginEnd(dp(c, end));
        p.bottomMargin = dp(c, bottom);
        return p;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        p.setMarginStart(dp(c, 14));
        p.setMarginEnd(dp(c, 14));
        v.setLayoutParams(p);
        return v;
    }

    /** Coloured letter badge for an OS type (W / L / A / ?). */
    public static TextView osBadge(Context c, VmConfig.OsType type, int sizeDp) {
        int fg, bg;
        String letter;
        switch (type) {
            case WINDOWS: fg = light ? 0xFF1D5FA8 : 0xFF6CB4EE; bg = light ? 0xFFDCEBFA : 0xFF10263A; letter = "W"; break;
            case LINUX: fg = SAND; bg = light ? 0xFFFCE7D2 : 0xFF3A2410; letter = "L"; break;
            case ANDROID: fg = light ? 0xFF237A38 : 0xFF7BD389; bg = light ? 0xFFDDF3E2 : 0xFF16301E; letter = "A"; break;
            default: fg = light ? 0xFF7B3FB0 : 0xFFC79BE8; bg = light ? 0xFFEFE2FA : 0xFF2A1A33; letter = "?"; break;
        }
        TextView t = text(c, letter, sizeDp >= 44 ? 18 : 16, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, bg, sizeDp * 0.3f, 0, 0));
        int s = dp(c, sizeDp);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return t;
    }

    public static String osLabel(VmConfig.OsType type) {
        switch (type) {
            case WINDOWS: return Lang.t("ويندوز");
            case LINUX: return Lang.t("لينكس");
            case ANDROID: return Lang.t("أندرويد");
            default: return Lang.t("أخرى");
        }
    }

    public static String formatSize(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
        return String.format(java.util.Locale.US, "%d KB", Math.max(1, bytes / 1024));
    }
}
