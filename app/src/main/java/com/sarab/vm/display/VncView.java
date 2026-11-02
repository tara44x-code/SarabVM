package com.sarab.vm.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import com.sarab.vm.core.Keysyms;
import com.sarab.vm.core.RfbClient;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Shows the virtual machine's screen and turns touches and typing into mouse
 * and keyboard events.
 *
 * Direct mode: tap = left click where you touch, hold = right click,
 * drag = drag with the left button, two fingers up/down = scroll wheel.
 * Touchpad mode: drag moves the pointer like a laptop touchpad, tap clicks.
 */
public final class VncView extends View implements RfbClient.Listener {

    public interface Callback {
        void onConnected(String name, int width, int height);
        void onDisconnected(Exception reason);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix = new Matrix();
    private final Rect dirty = new Rect();
    private final Object dirtyLock = new Object();
    private final Set<Integer> stickyModifiers = new LinkedHashSet<>();
    private final int touchSlop;

    private RfbClient client;
    private Callback callback;
    private Bitmap bitmap;
    private boolean fullRedraw;
    private boolean invalidatePosted;

    private float scale = 1f;
    private float offsetX;
    private float offsetY;

    private boolean touchpadMode;
    private boolean haptics = true;
    private float pointerX;
    private float pointerY;

    // gesture state
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private long downTime;
    private boolean dragging;
    private boolean moved;
    private boolean twoFinger;
    private boolean longPressFired;
    private float wheelAccumulator;
    private final Runnable longPress = this::onLongPress;

    public VncView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF000000);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void setHaptics(boolean on) {
        haptics = on;
    }

    public boolean isTouchpadMode() {
        return touchpadMode;
    }

    public void setTouchpadMode(boolean on) {
        touchpadMode = on;
        if (client != null) {
            pointerX = client.getWidth() / 2f;
            pointerY = client.getHeight() / 2f;
        }
    }

    /** Connects on a background thread; the callback is invoked on the UI thread. */
    public void connect(int port) {
        disconnect();
        RfbClient c = new RfbClient(this);
        client = c;
        Thread t = new Thread(() -> {
            try {
                c.connect("127.0.0.1", port, 20000);
            } catch (Exception e) {
                if (client == c) onDisconnected(e);
            }
        }, "vnc-connect");
        t.setDaemon(true);
        t.start();
    }

    public void disconnect() {
        RfbClient c = client;
        client = null;
        if (c != null) c.close();
    }

    public RfbClient client() {
        return client;
    }

    // ------------------------------------------------------------ RfbClient.Listener (background threads)

    @Override
    public void onConnected(String desktopName, int width, int height) {
        main.post(() -> {
            recreateBitmap(width, height);
            pointerX = width / 2f;
            pointerY = height / 2f;
            requestFocus();
            if (callback != null) callback.onConnected(desktopName, width, height);
        });
    }

    @Override
    public void onResize(int width, int height) {
        main.post(() -> recreateBitmap(width, height));
    }

    @Override
    public void onFrameUpdated(int x, int y, int w, int h) {
        synchronized (dirtyLock) {
            if (dirty.isEmpty()) dirty.set(x, y, x + w, y + h);
            else dirty.union(x, y, x + w, y + h);
            if (invalidatePosted) return;
            invalidatePosted = true;
        }
        postInvalidateOnAnimation();
    }

    @Override
    public void onBell() {
        // ignored
    }

    @Override
    public void onDisconnected(Exception reason) {
        main.post(() -> {
            if (callback != null) callback.onDisconnected(reason);
        });
    }

    // ------------------------------------------------------------ drawing

    private void recreateBitmap(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (bitmap == null || bitmap.getWidth() != w || bitmap.getHeight() != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        fullRedraw = true;
        updateMatrix();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateMatrix();
    }

    private void updateMatrix() {
        if (bitmap == null || getWidth() == 0 || getHeight() == 0) return;
        scale = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
        offsetX = (getWidth() - bitmap.getWidth() * scale) / 2f;
        offsetY = (getHeight() - bitmap.getHeight() * scale) / 2f;
        matrix.reset();
        matrix.postScale(scale, scale);
        matrix.postTranslate(offsetX, offsetY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RfbClient c = client;
        Bitmap b = bitmap;
        if (c == null || b == null) return;

        Rect r = new Rect();
        synchronized (dirtyLock) {
            invalidatePosted = false;
            if (fullRedraw) {
                r.set(0, 0, b.getWidth(), b.getHeight());
                fullRedraw = false;
            } else {
                r.set(dirty);
            }
            dirty.setEmpty();
        }
        synchronized (c.framebufferLock()) {
            int fw = c.getWidth();
            int fh = c.getHeight();
            if (fw == b.getWidth() && fh == b.getHeight() && r.intersect(0, 0, fw, fh)) {
                b.setPixels(c.getFramebuffer(), r.top * fw + r.left, fw, r.left, r.top, r.width(), r.height());
            }
        }
        canvas.drawBitmap(b, matrix, paint);
    }

    // ------------------------------------------------------------ touch

    private int toFbX(float viewX) {
        return Math.round((viewX - offsetX) / scale);
    }

    private int toFbY(float viewY) {
        return Math.round((viewY - offsetY) / scale);
    }

    private int pointerFbX(float viewX) {
        return touchpadMode ? Math.round(pointerX) : toFbX(viewX);
    }

    private int pointerFbY(float viewY) {
        return touchpadMode ? Math.round(pointerY) : toFbY(viewY);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        RfbClient c = client;
        if (c == null || bitmap == null) return true;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = lastY = e.getY();
                downTime = e.getEventTime();
                dragging = false;
                moved = false;
                twoFinger = false;
                longPressFired = false;
                wheelAccumulator = 0;
                if (!touchpadMode) c.sendPointer(0, toFbX(downX), toFbY(downY));
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                main.removeCallbacks(longPress);
                if (dragging) {
                    c.sendPointer(0, pointerFbX(lastX), pointerFbY(lastY));
                    dragging = false;
                }
                twoFinger = true;
                lastY = averageY(e);
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (twoFinger) {
                    float y = averageY(e);
                    wheelAccumulator += y - lastY;
                    lastY = y;
                    float step = getResources().getDisplayMetrics().density * 28;
                    while (Math.abs(wheelAccumulator) >= step) {
                        int button = wheelAccumulator > 0 ? RfbClient.WHEEL_UP : RfbClient.WHEEL_DOWN;
                        int px = pointerFbX(e.getX(0));
                        int py = pointerFbY(e.getY(0));
                        c.sendPointer(button, px, py);
                        c.sendPointer(0, px, py);
                        wheelAccumulator -= Math.signum(wheelAccumulator) * step;
                    }
                    return true;
                }
                float x = e.getX();
                float y = e.getY();
                if (!moved && Math.hypot(x - downX, y - downY) > touchSlop) {
                    moved = true;
                    main.removeCallbacks(longPress);
                }
                if (!moved || longPressFired) return true;
                if (touchpadMode) {
                    float speed = 1.4f / scale;
                    pointerX = clamp(pointerX + (x - lastX) * speed, 0, bitmap.getWidth() - 1);
                    pointerY = clamp(pointerY + (y - lastY) * speed, 0, bitmap.getHeight() - 1);
                    c.sendPointer(0, Math.round(pointerX), Math.round(pointerY));
                } else {
                    if (!dragging) {
                        dragging = true;
                        c.sendPointer(RfbClient.BUTTON_LEFT, toFbX(downX), toFbY(downY));
                    }
                    c.sendPointer(RfbClient.BUTTON_LEFT, toFbX(x), toFbY(y));
                }
                lastX = x;
                lastY = y;
                return true;
            }

            case MotionEvent.ACTION_UP: {
                main.removeCallbacks(longPress);
                float x = e.getX();
                float y = e.getY();
                if (dragging) {
                    c.sendPointer(0, toFbX(x), toFbY(y));
                } else if (!moved && !twoFinger && !longPressFired) {
                    int px = pointerFbX(x);
                    int py = pointerFbY(y);
                    c.sendPointer(RfbClient.BUTTON_LEFT, px, py);
                    c.sendPointer(0, px, py);
                    performClick();
                }
                dragging = false;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                main.removeCallbacks(longPress);
                if (dragging) c.sendPointer(0, pointerFbX(lastX), pointerFbY(lastY));
                dragging = false;
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void onLongPress() {
        RfbClient c = client;
        if (c == null || moved || twoFinger) return;
        longPressFired = true;
        int px = pointerFbX(downX);
        int py = pointerFbY(downY);
        c.sendPointer(RfbClient.BUTTON_RIGHT, px, py);
        c.sendPointer(0, px, py);
        if (haptics) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }

    private static float averageY(MotionEvent e) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += e.getY(i);
        return sum / e.getPointerCount();
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    // ------------------------------------------------------------ keyboard

    /** Ctrl / Alt / Win buttons on the special-key bar: held for the next key only. */
    public boolean toggleSticky(int keysym) {
        if (!stickyModifiers.remove(keysym)) {
            stickyModifiers.add(keysym);
            return true;
        }
        return false;
    }

    public boolean isSticky(int keysym) {
        return stickyModifiers.contains(keysym);
    }

    private Runnable stickyListener;

    public void setStickyListener(Runnable r) {
        stickyListener = r;
    }

    /** Sends a key press+release, wrapped in any sticky modifiers. */
    public void pressKey(int keysym) {
        RfbClient c = client;
        if (c == null) return;
        if (haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (stickyModifiers.isEmpty()) {
            c.tapKey(keysym);
            return;
        }
        int[] combo = new int[stickyModifiers.size() + 1];
        int i = 0;
        for (int m : stickyModifiers) combo[i++] = m;
        combo[i] = keysym;
        c.sendCombo(combo);
        stickyModifiers.clear();
        if (stickyListener != null) stickyListener.run();
    }

    public void showKeyboard(boolean show) {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (show) {
            requestFocus();
            imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
        } else {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                typeText(text);
                return true;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // Keyboards should not compose with VISIBLE_PASSWORD; if one does, send it as typed text.
                typeText(text);
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                for (int i = 0; i < Math.max(1, beforeLength); i++) pressKey(Keysyms.BACKSPACE);
                return true;
            }
        };
    }

    private void typeText(CharSequence text) {
        String s = text.toString();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            pressKey(Keysyms.forChar(cp));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return handleKey(keyCode, event, true) || super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return handleKey(keyCode, event, false) || super.onKeyUp(keyCode, event);
    }

    private boolean handleKey(int keyCode, KeyEvent event, boolean down) {
        RfbClient c = client;
        if (c == null || keyCode == KeyEvent.KEYCODE_BACK) return false;
        int keysym = mapKeyCode(keyCode);
        if (keysym == 0) {
            int uc = event.getUnicodeChar(event.getMetaState());
            if (uc <= 0) return false;
            keysym = Keysyms.forChar(uc);
        }
        if (down && !stickyModifiers.isEmpty()) {
            pressKey(keysym);
            return true;
        }
        if (stickyModifiers.isEmpty()) c.sendKey(down, keysym);
        return true;
    }

    static int mapKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL: return Keysyms.BACKSPACE;
            case KeyEvent.KEYCODE_FORWARD_DEL: return Keysyms.DELETE;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: return Keysyms.RETURN;
            case KeyEvent.KEYCODE_TAB: return Keysyms.TAB;
            case KeyEvent.KEYCODE_ESCAPE: return Keysyms.ESCAPE;
            case KeyEvent.KEYCODE_DPAD_LEFT: return Keysyms.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return Keysyms.RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return Keysyms.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return Keysyms.DOWN;
            case KeyEvent.KEYCODE_MOVE_HOME: return Keysyms.HOME;
            case KeyEvent.KEYCODE_MOVE_END: return Keysyms.END;
            case KeyEvent.KEYCODE_PAGE_UP: return Keysyms.PAGE_UP;
            case KeyEvent.KEYCODE_PAGE_DOWN: return Keysyms.PAGE_DOWN;
            case KeyEvent.KEYCODE_INSERT: return Keysyms.INSERT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT: return Keysyms.CONTROL_L;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT: return Keysyms.ALT_L;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT: return Keysyms.SHIFT_L;
            case KeyEvent.KEYCODE_META_LEFT:
            case KeyEvent.KEYCODE_META_RIGHT: return Keysyms.SUPER_L;
            case KeyEvent.KEYCODE_SPACE: return Keysyms.SPACE;
            default:
                if (keyCode >= KeyEvent.KEYCODE_F1 && keyCode <= KeyEvent.KEYCODE_F12) {
                    return Keysyms.function(keyCode - KeyEvent.KEYCODE_F1 + 1);
                }
                return 0;
        }
    }

    /** Copy of the current screen for the screenshot button. */
    public Bitmap snapshot() {
        Bitmap b = bitmap;
        return b == null ? null : b.copy(Bitmap.Config.ARGB_8888, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(longPress);
        super.onDetachedFromWindow();
    }
}
