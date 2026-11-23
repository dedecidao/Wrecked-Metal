package io.github.dedecidao.dd64;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-screen N64 controller, drawn above the game.
 *
 * Layout (landscape), sized in dp and scaled by the launcher's "touch size":
 *   left half       analog stick: it appears where the thumb lands; or, with the
 *                   launcher's "arrows" option, a compact cross: left and right
 *                   (big, for steering) and up and down (small, for the menus),
 *                   each the stick at full tilt that way
 *   right, bottom   A (gas, the biggest), B (brake) to its left
 *   right, above    R (the handbrake) and C-Up (the camera: the only C button the
 *                   game uses)
 *   top right       Start, a small pause button
 * Wrecked Metal draws icons on A, B, R, C-Up and Start (Tabler Icons, MIT); they are
 * still the N64's A (gas, confirm), B (brake, back), R (handbrake), C-Up (camera) and
 * Start. L (the HUD toggle and the dev page) is a small button under the HUD's score;
 * there is no Z (looking back: kept for a mirror later), and the arrows' up and down
 * show only in menus (nativeDriving). The Fidelity profile keeps the N64 controller:
 * Z below B, L top left, Start top centre, four C buttons.
 *
 * A finger can slide from one button to another (A to B to brake, say).
 * The state goes to native code (android/touch.c) on every change and is
 * merged into player 1's controller there.
 */
public class TouchControlsView extends View implements InputManager.InputDeviceListener {
    // N64 button bits (port/src/input.c)
    static final int CONT_A = 0x8000, CONT_B = 0x4000, CONT_Z = 0x2000, CONT_START = 0x1000;
    static final int CONT_L = 0x0020, CONT_R = 0x0010;
    static final int CONT_CU = 0x0008, CONT_CD = 0x0004, CONT_CL = 0x0002, CONT_CR = 0x0001;
    static final int STICK_MAX = 80; // N64 units at full tilt
    // The arrows (launcher option): not N64 buttons, the stick's axes at full tilt
    static final int ARROW_LEFT = 0x10000, ARROW_RIGHT = 0x20000, ARROW_UP = 0x40000, ARROW_DOWN = 0x80000;
    static final int ARROWS_MASK = ARROW_LEFT | ARROW_RIGHT | ARROW_UP | ARROW_DOWN;

    private static native void nativeSetState(int buttons, int x, int y);
    /** 1 while the player is driving in a race (not in a menu or paused): android/touch.c */
    private static native int nativeDriving();

    private static final class Button {
        final String label;
        final int bit;
        // centre, in dp from an anchor corner; negative x/y are from the right/bottom
        final float dx, dy, radiusDp;
        float cx, cy, r; // pixels
        boolean pressed;
        int icon;          // a drawable drawn instead of the label, 0 none
        boolean pill;      // a rounded bar (the N64's L, R, Z and Start), else a circle
        boolean menuOnly;  // shown only in menus (the arrows' up and down)

        Button(String label, int bit, float dx, float dy, float radiusDp) {
            this.label = label;
            this.bit = bit;
            this.dx = dx;
            this.dy = dy;
            this.radiusDp = radiusDp;
            this.pill = bit == CONT_START || bit == CONT_L || bit == CONT_R || bit == CONT_Z;
        }

        Button icon(int res) {
            icon = res;
            pill = false;
            return this;
        }

        Button menuOnly() {
            menuOnly = true;
            return this;
        }

        boolean hit(float x, float y, float slop) {
            float ddx = x - cx, ddy = y - cy;
            return ddx * ddx + ddy * ddy <= (r + slop) * (r + slop);
        }
    }

    private final List<Button> mButtons = new ArrayList<>();
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity, mScale, mOpacity;
    private final boolean mHideWithGamepad;
    private final boolean mArrows;
    private final InputManager mInput;

    // Stick
    private int mStickPointer = -1;
    private float mStickBaseX, mStickBaseY, mStickX, mStickY, mStickRadius;
    private int mOutX, mOutY;

    // Which button each pointer is on (pointer id -> button bit), up to 10 pointers
    private final int[] mPointerBits = new int[16];
    private int mLastButtons = -1, mLastX, mLastY;
    private boolean mGamepadPresent;
    // Driving (no menu up): the menu-only buttons hide. Asked of the game a few times a second
    private boolean mDriving;
    private int mNotDrivingReads;
    private final Runnable mPollDriving = new Runnable() {
        @Override
        public void run() {
            boolean d;
            try {
                d = nativeDriving() != 0;
            } catch (UnsatisfiedLinkError e) {
                d = false; // the native library is not loaded yet
            }
            // into driving at once; out of it only when two reads in a row say so, so one
            // late frame never flashes the menu arrows in
            mNotDrivingReads = d ? 0 : mNotDrivingReads + 1;
            boolean next = d || (mDriving && mNotDrivingReads < 2);
            if (next != mDriving) {
                mDriving = next;
                releaseHidden();
                invalidate();
            }
            postDelayed(this, 150);
        }
    };

    /** fidelity: the N64 controller as it was (stick and all four C buttons, no arrows) */
    public TouchControlsView(Context context, boolean fidelity) {
        super(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mScale = Settings.touchScale(context);
        mOpacity = Settings.touchOpacity(context);
        mHideWithGamepad = Settings.hideWithGamepad(context);
        mArrows = !fidelity && Settings.arrows(context);

        if (fidelity) {
            mButtons.add(new Button("A", CONT_A, -90, -90, 46));
            mButtons.add(new Button("B", CONT_B, -190, -60, 36));
            mButtons.add(new Button("Z", CONT_Z, -280, -45, 30));
            mButtons.add(new Button("R", CONT_R, -70, -215, 32));
            // the Fidelity profile: the N64's four C buttons, a diamond above B
            mButtons.add(new Button("C▲", CONT_CU, -190, -238, 22));
            mButtons.add(new Button("C◀", CONT_CL, -236, -192, 22));
            mButtons.add(new Button("C▶", CONT_CR, -144, -192, 22));
            mButtons.add(new Button("C▼", CONT_CD, -190, -146, 22));
            mButtons.add(new Button("L", CONT_L, 70, 50, 30));
            mButtons.add(new Button("START", CONT_START, 0, 40, 28)); // dx 0: centred
        } else {
            mButtons.add(new Button("A", CONT_A, -90, -90, 46).icon(R.drawable.ic_touch_gas));
            mButtons.add(new Button("B", CONT_B, -190, -60, 36).icon(R.drawable.ic_touch_brake));
            // R: the handbrake, a round button the size of C-Up
            mButtons.add(new Button("R", CONT_R, -70, -215, 26).icon(R.drawable.ic_touch_handbrake));
            // C-Up only (the camera); the game makes no real use of C-Down, C-Left, C-Right
            mButtons.add(new Button("C▲", CONT_CU, -190, -200, 26).icon(R.drawable.ic_touch_camera));
            // Start: a small pause button in the top right corner, beside the HUD's cars left
            mButtons.add(new Button("START", CONT_START, -30, 30, 20).icon(R.drawable.ic_touch_pause));
            // L: the HUD toggle and the dev page; small, under the HUD's score (top left)
            mButtons.add(new Button("L", CONT_L, 45, 75, 17));
        }
        if (mArrows) {
            mButtons.add(new Button("◀", ARROW_LEFT, 70, -90, 44));
            mButtons.add(new Button("▶", ARROW_RIGHT, 185, -90, 44));
            Button up = new Button("▲", ARROW_UP, 127, -185, 26);
            Button down = new Button("▼", ARROW_DOWN, 127, -30, 22);
            mButtons.add(fidelity ? up : up.menuOnly());
            mButtons.add(fidelity ? down : down.menuOnly());
        }
        mStickRadius = 70 * mDensity * mScale;

        mFill.setStyle(Paint.Style.FILL);
        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(2 * mDensity);
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setFakeBoldText(true);

        setHapticFeedbackEnabled(true);
        mInput = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
        refreshGamepadState();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mInput != null) {
            mInput.registerInputDeviceListener(this, null);
        }
        post(mPollDriving);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mInput != null) {
            mInput.unregisterInputDeviceListener(this);
        }
        removeCallbacks(mPollDriving);
        super.onDetachedFromWindow();
    }

    @Override public void onInputDeviceAdded(int id) { refreshGamepadState(); }
    @Override public void onInputDeviceRemoved(int id) { refreshGamepadState(); }
    @Override public void onInputDeviceChanged(int id) { refreshGamepadState(); }

    /** Hide the controls while a physical gamepad is connected (launcher option). */
    void refreshGamepadState() {
        boolean present = false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                present = true;
                break;
            }
        }
        mGamepadPresent = present;
        boolean visible = !(mHideWithGamepad && present);
        setVisibility(visible ? VISIBLE : GONE);
        if (!visible) {
            releaseAll();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float k = mDensity * mScale;
        for (Button b : mButtons) {
            b.r = b.radiusDp * k;
            if (b.dx == 0) {
                b.cx = w / 2f;
            } else {
                b.cx = b.dx > 0 ? b.dx * k : w + b.dx * k;
            }
            b.cy = b.dy > 0 ? b.dy * k : h + b.dy * k;
        }
        mText.setTextSize(15 * k);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int index = e.getActionIndex();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown(e.getPointerId(index), e.getX(index), e.getY(index));
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e.getPointerId(index));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        send();
        invalidate();
        return true;
    }

    private Button buttonAt(float x, float y) {
        Button best = null;
        float bestD = Float.MAX_VALUE, slop = 8 * mDensity;
        for (Button b : mButtons) {
            if (shown(b) && b.hit(x, y, slop)) {
                float d = (x - b.cx) * (x - b.cx) + (y - b.cy) * (y - b.cy);
                if (d < bestD) {
                    bestD = d;
                    best = b;
                }
            }
        }
        return best;
    }

    private void pointerDown(int id, float x, float y) {
        if (id < 0 || id >= mPointerBits.length) return;
        Button b = buttonAt(x, y);
        if (b != null) {
            mPointerBits[id] = b.bit;
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (!mArrows && mStickPointer < 0 && x < getWidth() * 0.45f && y > getHeight() * 0.25f) {
            mStickPointer = id;
            mStickBaseX = mStickX = x;
            mStickBaseY = mStickY = y;
        }
        updateButtons();
    }

    private void pointerMove(int id, float x, float y) {
        if (id < 0 || id >= mPointerBits.length) return;
        if (id == mStickPointer) {
            mStickX = x;
            mStickY = y;
            float dx = x - mStickBaseX, dy = y - mStickBaseY;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > mStickRadius) {
                // drag the base along, so the stick never "sticks" at the rim
                mStickBaseX = x - dx / len * mStickRadius;
                mStickBaseY = y - dy / len * mStickRadius;
                dx = x - mStickBaseX;
                dy = y - mStickBaseY;
            }
            mOutX = Math.round(dx / mStickRadius * STICK_MAX);
            mOutY = Math.round(-dy / mStickRadius * STICK_MAX);
            return;
        }
        if (mPointerBits[id] != 0) {
            // slide between buttons
            Button b = buttonAt(x, y);
            int bit = b != null ? b.bit : mPointerBits[id];
            if (bit != mPointerBits[id]) {
                mPointerBits[id] = bit;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                updateButtons();
            }
        }
    }

    private void pointerUp(int id) {
        if (id < 0 || id >= mPointerBits.length) return;
        if (id == mStickPointer) {
            mStickPointer = -1;
            mOutX = mOutY = 0;
        }
        mPointerBits[id] = 0;
        updateButtons();
    }

    private boolean shown(Button b) {
        return !(b.menuOnly && mDriving);
    }

    /** Fingers on buttons that just hid let go of them */
    private void releaseHidden() {
        for (Button b : mButtons) {
            if (!shown(b)) {
                for (int i = 0; i < mPointerBits.length; i++) {
                    if (mPointerBits[i] == b.bit) mPointerBits[i] = 0;
                }
            }
        }
        updateButtons();
        send();
    }

    private void releaseAll() {
        for (int i = 0; i < mPointerBits.length; i++) mPointerBits[i] = 0;
        mStickPointer = -1;
        mOutX = mOutY = 0;
        updateButtons();
        send();
    }

    private int currentBits() {
        int bits = 0;
        for (int b : mPointerBits) bits |= b;
        return bits;
    }

    private void updateButtons() {
        int bits = currentBits();
        for (Button b : mButtons) b.pressed = (bits & b.bit) != 0;
        if (mArrows) {
            // the same axes as the analog stick, at full tilt (both ways at once cancel)
            mOutX = ((bits & ARROW_RIGHT) != 0 ? STICK_MAX : 0) - ((bits & ARROW_LEFT) != 0 ? STICK_MAX : 0);
            mOutY = ((bits & ARROW_UP) != 0 ? STICK_MAX : 0) - ((bits & ARROW_DOWN) != 0 ? STICK_MAX : 0);
        }
    }

    private void send() {
        int bits = currentBits() & ~ARROWS_MASK; // the arrows go out as the stick
        if (bits != mLastButtons || mOutX != mLastX || mOutY != mLastY) {
            mLastButtons = bits;
            mLastX = mOutX;
            mLastY = mOutY;
            try {
                nativeSetState(bits, mOutX, mOutY);
            } catch (UnsatisfiedLinkError ignored) {
                // the native library is not loaded yet
            }
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        int a = Math.round(255 * mOpacity);
        for (Button b : mButtons) {
            if (!shown(b)) continue;
            mFill.setColor(b.pressed ? 0xFFFFFFFF : 0xFF000000);
            mFill.setAlpha(b.pressed ? a / 2 : a / 3);
            mStroke.setColor(colorFor(b.bit));
            mStroke.setAlpha(a);
            if (b.pill) {
                RectF r = new RectF(b.cx - b.r * 1.4f, b.cy - b.r * 0.7f, b.cx + b.r * 1.4f, b.cy + b.r * 0.7f);
                c.drawRoundRect(r, b.r * 0.7f, b.r * 0.7f, mFill);
                c.drawRoundRect(r, b.r * 0.7f, b.r * 0.7f, mStroke);
            } else {
                c.drawCircle(b.cx, b.cy, b.r, mFill);
                c.drawCircle(b.cx, b.cy, b.r, mStroke);
            }
            if (b.icon != 0) {
                Drawable d = icon(b.icon);
                if (d != null) {
                    int s = Math.round(b.r * 0.58f);
                    d.setTint(colorFor(b.bit));
                    d.setAlpha(a);
                    d.setBounds(Math.round(b.cx) - s, Math.round(b.cy) - s, Math.round(b.cx) + s, Math.round(b.cy) + s);
                    d.draw(c);
                    continue;
                }
            }
            mText.setColor(colorFor(b.bit));
            mText.setAlpha(a);
            float size = b.bit == CONT_START ? mText.getTextSize() * 0.75f : mText.getTextSize();
            float old = mText.getTextSize();
            mText.setTextSize(size);
            c.drawText(b.label, b.cx, b.cy + size * 0.35f, mText);
            mText.setTextSize(old);
        }
        if (mArrows) {
            return; // no stick: the arrows are drawn with the buttons
        }
        // Stick: a hint where it is when idle, the base and knob while held
        float bx, by;
        if (mStickPointer >= 0) {
            bx = mStickBaseX;
            by = mStickBaseY;
        } else {
            bx = 140 * mDensity * mScale;
            by = getHeight() - 130 * mDensity * mScale;
        }
        mFill.setColor(0xFF000000);
        mFill.setAlpha(a / 4);
        mStroke.setColor(0xFFFFFFFF);
        mStroke.setAlpha(a);
        c.drawCircle(bx, by, mStickRadius, mFill);
        c.drawCircle(bx, by, mStickRadius, mStroke);
        float kx = bx + mOutX / (float) STICK_MAX * mStickRadius;
        float ky = by - mOutY / (float) STICK_MAX * mStickRadius;
        mFill.setColor(0xFFFFFFFF);
        mFill.setAlpha(mStickPointer >= 0 ? a : a / 2);
        c.drawCircle(kx, ky, mStickRadius * 0.42f, mFill);
    }

    private final android.util.SparseArray<Drawable> mIcons = new android.util.SparseArray<>();

    private Drawable icon(int res) {
        Drawable d = mIcons.get(res);
        if (d == null) {
            d = getContext().getDrawable(res);
            if (d != null) {
                d = d.mutate();
                mIcons.put(res, d);
            }
        }
        return d;
    }

    private static int colorFor(int bit) {
        switch (bit) {
            case CONT_A: return 0xFF3F7FFF; // blue, as on the N64 pad
            case CONT_B: return 0xFF2ECC40; // green
            case CONT_CU: case CONT_CD: case CONT_CL: case CONT_CR: return 0xFFFFD000; // yellow
            case ARROW_LEFT: case ARROW_RIGHT: case ARROW_UP: case ARROW_DOWN: return 0xFFFFFFFF;
            case CONT_START: return 0xFFFF4136; // red
            default: return 0xFFDDDDDD;
        }
    }
}
