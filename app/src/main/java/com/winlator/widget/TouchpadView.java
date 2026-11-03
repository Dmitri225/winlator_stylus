package com.winlator.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.winlator.core.AppUtils;
import com.winlator.math.Mathf;
import com.winlator.math.XForm;
import com.winlator.renderer.ViewTransformation;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

public class TouchpadView extends View {
    private static final byte MAX_FINGERS = 4;
    private static final short MAX_TWO_FINGERS_SCROLL_DISTANCE = 350;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.25f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;
    private final Finger[] fingers = new Finger[MAX_FINGERS];
    private byte numFingers = 0;
    private float sensitivity = 1.0f;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private Finger fingerPointerButtonLeft;
    private Finger fingerPointerButtonRight;
    private float scrollAccumY = 0;
    private boolean scrolling = false;
    private final XServer xServer;
    private Runnable fourFingersTapCallback;
    private final float[] xform = XForm.getInstance();

    // ---- Stylus (S Pen) support ----
    public static final byte STYLUS_RMB_MODE_SIDE_BUTTON = 0;
    public static final byte STYLUS_RMB_MODE_LONG_PRESS = 1;
    public static final short DEFAULT_STYLUS_LONG_PRESS_MILLISECONDS = 400;
    public static final short STYLUS_PALM_REJECTION_MILLISECONDS = 300;
    private boolean stylusEnabled = true;
    private byte stylusRightButtonMode = STYLUS_RMB_MODE_LONG_PRESS;
    private int stylusLongPressMillis = DEFAULT_STYLUS_LONG_PRESS_MILLISECONDS;
    private boolean stylusHoverButtonRightClick = false;
    private boolean stylusPalmRejection = true;
    private Pointer.Button stylusTipButton = null;
    private boolean stylusHoverButtonDown = false;
    private boolean stylusTipDown = false;
    private boolean stylusPendingLeft = false;
    private float stylusDownX, stylusDownY;
    private int stylusLastX = Integer.MIN_VALUE, stylusLastY = Integer.MIN_VALUE;
    private long stylusLastEventTime = 0;
    private final int touchSlop;
    private final Runnable stylusLongPressRunnable = () -> {
        if (stylusTipDown && stylusPendingLeft) {
            stylusPendingLeft = false;
            stylusPressTipButton(Pointer.Button.BUTTON_RIGHT);
            // tactile confirmation that the long press turned into a right button
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING);
        }
    };

    public TouchpadView(Context context, XServer xServer) {
        super(context);
        this.xServer = xServer;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setBackground(createTransparentBg());
        setClickable(true);
        setFocusable(true);
        setFocusableInTouchMode(false);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        updateXform(AppUtils.getScreenWidth(), AppUtils.getScreenHeight(), xServer.screenInfo.width, xServer.screenInfo.height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateXform(w, h, xServer.screenInfo.width, xServer.screenInfo.height);
    }

    private void updateXform(int outerWidth, int outerHeight, int innerWidth, int innerHeight) {
        ViewTransformation viewTransformation = new ViewTransformation();
        viewTransformation.update(outerWidth, outerHeight, innerWidth, innerHeight);

        float invAspect = 1.0f / viewTransformation.aspect;
        if (!xServer.getRenderer().isFullscreen()) {
            XForm.makeTranslation(xform, -viewTransformation.viewOffsetX, -viewTransformation.viewOffsetY);
            XForm.scale(xform, invAspect, invAspect);
        }
        else XForm.makeScale(xform, invAspect, invAspect);
    }

    private class Finger {
        private int x;
        private int y;
        private final int startX;
        private final int startY;
        private int lastX;
        private int lastY;
        private final long touchTime;

        public Finger(float x, float y) {
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = this.startX = this.lastX = (int)transformedPoint[0];
            this.y = this.startY = this.lastY = (int)transformedPoint[1];
            touchTime = System.currentTimeMillis();
        }

        public void update(float x, float y) {
            lastX = this.x;
            lastY = this.y;
            float[] transformedPoint = XForm.transformPoint(xform, x, y);
            this.x = (int)transformedPoint[0];
            this.y = (int)transformedPoint[1];
        }

        private int deltaX() {
            float dx = (x - lastX) * sensitivity;
            if (Math.abs(dx) > CURSOR_ACCELERATION_THRESHOLD) dx *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dx);
        }

        private int deltaY() {
            float dy = (y - lastY) * sensitivity;
            if (Math.abs(dy) > CURSOR_ACCELERATION_THRESHOLD) dy *= CURSOR_ACCELERATION;
            return Mathf.roundPoint(dy);
        }

        private boolean isTap() {
            return (System.currentTimeMillis() - touchTime) < MAX_TAP_MILLISECONDS && travelDistance() < MAX_TAP_TRAVEL_DISTANCE;
        }

        private float travelDistance() {
            return (float)Math.hypot(x - startX, y - startY);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int actionIndex = event.getActionIndex();
        int pointerId = event.getPointerId(actionIndex);
        int actionMasked = event.getActionMasked();

        if (stylusEnabled) {
            if (actionMasked == MotionEvent.ACTION_CANCEL) {
                if (stylusTipDown) handleStylusTouchEvent(event); // then fall through to reset fingers
            }
            else if (actionMasked == MotionEvent.ACTION_MOVE) {
                if (stylusTipDown && findStylusPointerIndex(event) >= 0) return handleStylusTouchEvent(event);
            }
            else if (isStylusPointer(event, actionIndex)) {
                return handleStylusTouchEvent(event);
            }
            else if (isFingerDownAction(actionMasked) && (stylusTipDown || (stylusPalmRejection && isStylusRecentlyActive()))) {
                return true; // palm/finger touching the screen while the pen is in use
            }
        }

        if (pointerId >= MAX_FINGERS) return true;

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true;
                scrollAccumY = 0;
                scrolling = false;
                fingers[pointerId] = new Finger(event.getX(actionIndex), event.getY(actionIndex));
                numFingers++;
                break;
            case MotionEvent.ACTION_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                }
                else {
                    for (byte i = 0; i < MAX_FINGERS; i++) {
                        if (fingers[i] != null) {
                            int pointerIndex = event.findPointerIndex(i);
                            if (pointerIndex >= 0) {
                                fingers[i].update(event.getX(pointerIndex), event.getY(pointerIndex));
                                handleFingerMove(fingers[i]);
                            }
                            else {
                                handleFingerUp(fingers[i]);
                                fingers[i] = null;
                                numFingers--;
                            }
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                if (fingers[pointerId] != null) {
                    fingers[pointerId].update(event.getX(actionIndex), event.getY(actionIndex));
                    handleFingerUp(fingers[pointerId]);
                    fingers[pointerId] = null;
                    numFingers--;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                for (byte i = 0; i < MAX_FINGERS; i++) fingers[i] = null;
                numFingers = 0;
                break;
        }

        return true;
    }

    private void handleFingerUp(Finger finger1) {
        switch (numFingers) {
            case 1:
                if (finger1.isTap()) pressPointerButtonLeft(finger1);
                break;
            case 2:
                Finger finger2 = findSecondFinger(finger1);
                if (finger2 != null && finger1.isTap()) pressPointerButtonRight(finger1);
                break;
            case 4:
                if (fourFingersTapCallback != null) {
                    for (byte i = 0; i < 4; i++) {
                        if (fingers[i] != null && !fingers[i].isTap()) return;
                    }
                    fourFingersTapCallback.run();
                }
                break;
        }

        releasePointerButtonLeft(finger1);
        releasePointerButtonRight(finger1);
    }

    private void handleFingerMove(Finger finger1) {
        boolean skipPointerMove = false;

        Finger finger2 = numFingers == 2 ? findSecondFinger(finger1) : null;
        if (finger2 != null) {
            final float resolutionScale = 1000.0f / Math.min(xServer.screenInfo.width, xServer.screenInfo.height);
            float currDistance = (float)Math.hypot(finger1.x - finger2.x, finger1.y - finger2.y) * resolutionScale;

            if (currDistance < MAX_TWO_FINGERS_SCROLL_DISTANCE) {
                scrollAccumY += ((finger1.y + finger2.y) * 0.5f) - (finger1.lastY + finger2.lastY) * 0.5f;

                if (scrollAccumY < -100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    scrollAccumY = 0;
                }
                else if (scrollAccumY > 100) {
                    xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                    xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    scrollAccumY = 0;
                }
                scrolling = true;
            }
            else if (currDistance >= MAX_TWO_FINGERS_SCROLL_DISTANCE && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT) &&
                     finger2.travelDistance() < MAX_TAP_TRAVEL_DISTANCE) {
                pressPointerButtonLeft(finger1);
                skipPointerMove = true;
            }
        }

        if (!scrolling && numFingers <= 2 && !skipPointerMove) {
            int dx = finger1.deltaX();
            int dy = finger1.deltaY();

            if (xServer.isRelativeMouseMovement()) {
                WinHandler winHandler = xServer.getWinHandler();
                winHandler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            }
            else xServer.injectPointerMoveDelta(dx, dy);
        }
    }

    private Finger findSecondFinger(Finger finger) {
        for (byte i = 0; i < MAX_FINGERS; i++) {
            if (fingers[i] != null && fingers[i] != finger) return fingers[i];
        }
        return null;
    }

    private void pressPointerButtonLeft(Finger finger) {
        if (pointerButtonLeftEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
            fingerPointerButtonLeft = finger;
        }
    }

    private void pressPointerButtonRight(Finger finger) {
        if (pointerButtonRightEnabled && !xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
            fingerPointerButtonRight = finger;
        }
    }

    private void releasePointerButtonLeft(final Finger finger) {
        if (pointerButtonLeftEnabled && finger == fingerPointerButtonLeft && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_LEFT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                fingerPointerButtonLeft = null;
            }, 30);
        }
    }

    private void releasePointerButtonRight(final Finger finger) {
        if (pointerButtonRightEnabled && finger == fingerPointerButtonRight && xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) {
            postDelayed(() -> {
                xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                fingerPointerButtonRight = null;
            }, 30);
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        if (stylusEnabled && isStylusPointer(event, 0)) return handleStylusGenericEvent(event);
        boolean handled = false;
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int actionButton = event.getActionButton();
            switch (event.getAction()) {
                case MotionEvent.ACTION_BUTTON_PRESS:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_BUTTON_RELEASE:
                    if (actionButton == MotionEvent.BUTTON_PRIMARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_LEFT);
                    }
                    else if (actionButton == MotionEvent.BUTTON_SECONDARY) {
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
                    }
                    handled = true;
                    break;
                case MotionEvent.ACTION_HOVER_MOVE:
                    float[] transformedPoint = XForm.transformPoint(xform, event.getX(), event.getY());
                    xServer.injectPointerMove((int)transformedPoint[0], (int)transformedPoint[1]);
                    handled = true;
                    break;
                case MotionEvent.ACTION_SCROLL:
                    float scrollY = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
                    if (scrollY <= -1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_DOWN);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_DOWN);
                    }
                    else if (scrollY >= 1.0f) {
                        xServer.injectPointerButtonPress(Pointer.Button.BUTTON_SCROLL_UP);
                        xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_SCROLL_UP);
                    }
                    handled = true;
                    break;
            }
        }
        return handled;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        final float[] result = {0, 0};

        XForm.transformPoint(xform, lastX, lastY, result);
        lastX = result[0];
        lastY = result[1];

        XForm.transformPoint(xform, x, y, result);
        x = result[0];
        y = result[1];

        result[0] = x - lastX;
        result[1] = y - lastY;
        return result;
    }

    // =====================================================================
    // Stylus (S Pen) as an absolute pointing device
    //   hover            -> pointer follows the pen tip (no buttons)
    //   tip              -> LMB press/drag/release at the pen position
    //   side button + tip-> RMB held while the tip touches (HoMM3 info popups)
    //   long press (opt) -> RMB held, like the ExaGear touch mode
    //   hover + side btn (opt) -> RMB without touching the screen
    // =====================================================================

    private static boolean isStylusPointer(MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) return false;
        int toolType = event.getToolType(pointerIndex);
        return toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER;
    }

    private static int findStylusPointerIndex(MotionEvent event) {
        for (int i = 0, count = event.getPointerCount(); i < count; i++) {
            if (isStylusPointer(event, i)) return i;
        }
        return -1;
    }

    private static boolean isFingerDownAction(int actionMasked) {
        return actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN;
    }

    private static boolean isStylusSideButtonPressed(MotionEvent event) {
        // BUTTON_SECONDARY: some firmwares/older Android versions report the pen button this way
        return (event.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
    }

    private boolean isStylusRecentlyActive() {
        return stylusTipDown || (System.currentTimeMillis() - stylusLastEventTime) < STYLUS_PALM_REJECTION_MILLISECONDS;
    }

    private void stylusMoveTo(float viewX, float viewY) {
        float[] p = XForm.transformPoint(xform, viewX, viewY);
        int x = (int)p[0];
        int y = (int)p[1];
        if (xServer.isRelativeMouseMovement()) {
            if (stylusLastX != Integer.MIN_VALUE) {
                int dx = x - stylusLastX;
                int dy = y - stylusLastY;
                if (dx != 0 || dy != 0) xServer.getWinHandler().mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            }
        }
        else if (x != stylusLastX || y != stylusLastY) {
            xServer.injectPointerMove(x, y);
        }
        stylusLastX = x;
        stylusLastY = y;
    }

    private void stylusPressTipButton(Pointer.Button button) {
        stylusReleaseTipButton();
        if (!xServer.pointer.isButtonPressed(button)) xServer.injectPointerButtonPress(button);
        stylusTipButton = button;
    }

    private void stylusReleaseTipButton() {
        if (stylusTipButton != null) {
            if (xServer.pointer.isButtonPressed(stylusTipButton)) xServer.injectPointerButtonRelease(stylusTipButton);
            stylusTipButton = null;
        }
    }

    private void stylusClick(final Pointer.Button button) {
        xServer.injectPointerButtonPress(button);
        // same 30ms gap the touchpad uses, so games that poll button state don't miss the click
        postDelayed(() -> xServer.injectPointerButtonRelease(button), 30);
    }

    private void stylusReleaseHoverButton() {
        if (stylusHoverButtonDown) {
            stylusHoverButtonDown = false;
            if (xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) xServer.injectPointerButtonRelease(Pointer.Button.BUTTON_RIGHT);
        }
    }

    private boolean handleStylusTouchEvent(MotionEvent event) {
        stylusLastEventTime = System.currentTimeMillis();
        int actionMasked = event.getActionMasked();
        int index = (actionMasked == MotionEvent.ACTION_MOVE || actionMasked == MotionEvent.ACTION_CANCEL) ?
            findStylusPointerIndex(event) : event.getActionIndex();
        if (index < 0) return true;

        float viewX = event.getX(index);
        float viewY = event.getY(index);

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(stylusLongPressRunnable);
                stylusReleaseHoverButton();
                stylusTipDown = true;
                stylusPendingLeft = false;
                stylusDownX = viewX;
                stylusDownY = viewY;
                stylusMoveTo(viewX, viewY);

                if (isStylusSideButtonPressed(event)) {
                    stylusPressTipButton(Pointer.Button.BUTTON_RIGHT);
                }
                else if (event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER) {
                    stylusPressTipButton(Pointer.Button.BUTTON_MIDDLE);
                }
                else if (stylusRightButtonMode == STYLUS_RMB_MODE_LONG_PRESS) {
                    // defer LMB: short tap = LMB click, movement = LMB drag,
                    // holding still for N ms = RMB held until the pen is lifted
                    stylusPendingLeft = true;
                    postDelayed(stylusLongPressRunnable, stylusLongPressMillis);
                }
                else {
                    stylusPressTipButton(Pointer.Button.BUTTON_LEFT);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (!stylusTipDown) break;
                if (!stylusPendingLeft) stylusMoveTo(viewX, viewY);
                if (stylusPendingLeft && Math.hypot(viewX - stylusDownX, viewY - stylusDownY) > touchSlop) {
                    removeCallbacks(stylusLongPressRunnable);
                    stylusPendingLeft = false;
                    // start the drag where the pen landed, then catch up to the current position
                    stylusMoveTo(stylusDownX, stylusDownY);
                    stylusPressTipButton(Pointer.Button.BUTTON_LEFT);
                    stylusMoveTo(viewX, viewY);
                }
                else if (stylusPendingLeft) {
                    stylusMoveTo(viewX, viewY); // tiny jitter within the slop: still follow the pen
                }
                else if (stylusTipButton == Pointer.Button.BUTTON_LEFT && isStylusSideButtonPressed(event)) {
                    // side button pressed after the tip already landed: switch to RMB
                    stylusPressTipButton(Pointer.Button.BUTTON_RIGHT);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(stylusLongPressRunnable);
                if (actionMasked != MotionEvent.ACTION_CANCEL) stylusMoveTo(viewX, viewY);
                if (stylusPendingLeft && actionMasked != MotionEvent.ACTION_CANCEL) stylusClick(Pointer.Button.BUTTON_LEFT);
                stylusPendingLeft = false;
                stylusReleaseTipButton();
                stylusTipDown = false;
                break;
        }
        return true;
    }

    private boolean handleStylusGenericEvent(MotionEvent event) {
        stylusLastEventTime = System.currentTimeMillis();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                if (!stylusTipDown) {
                    stylusMoveTo(event.getX(), event.getY());
                    if (stylusHoverButtonRightClick) {
                        boolean pressed = isStylusSideButtonPressed(event);
                        if (pressed && !stylusHoverButtonDown) {
                            stylusHoverButtonDown = true;
                            if (!xServer.pointer.isButtonPressed(Pointer.Button.BUTTON_RIGHT)) xServer.injectPointerButtonPress(Pointer.Button.BUTTON_RIGHT);
                        }
                        else if (!pressed) stylusReleaseHoverButton();
                    }
                }
                return true;
            case MotionEvent.ACTION_HOVER_EXIT:
                stylusReleaseHoverButton();
                if (!stylusTipDown) {
                    stylusLastX = Integer.MIN_VALUE;
                    stylusLastY = Integer.MIN_VALUE;
                }
                return true;
            case MotionEvent.ACTION_BUTTON_PRESS:
            case MotionEvent.ACTION_BUTTON_RELEASE:
                // tip + button is handled in onTouchEvent (button state), hover + button above
                return true;
        }
        return false;
    }

    @Override
    public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
        // hide Samsung's hover dot: the X server already draws the Windows cursor
        if (stylusEnabled && isStylusPointer(event, pointerIndex)) {
            return PointerIcon.getSystemIcon(getContext(), PointerIcon.TYPE_NULL);
        }
        return super.onResolvePointerIcon(event, pointerIndex);
    }

    public void setStylusEnabled(boolean stylusEnabled) {
        this.stylusEnabled = stylusEnabled;
    }

    public void setStylusRightButtonMode(byte stylusRightButtonMode) {
        this.stylusRightButtonMode = stylusRightButtonMode;
    }

    public void setStylusLongPressMillis(int stylusLongPressMillis) {
        this.stylusLongPressMillis = Math.max(100, stylusLongPressMillis);
    }

    public void setStylusHoverButtonRightClick(boolean stylusHoverButtonRightClick) {
        this.stylusHoverButtonRightClick = stylusHoverButtonRightClick;
    }

    public void setStylusPalmRejection(boolean stylusPalmRejection) {
        this.stylusPalmRejection = stylusPalmRejection;
    }

    private StateListDrawable createTransparentBg() {
        StateListDrawable stateListDrawable = new StateListDrawable();

        ColorDrawable focusedDrawable = new ColorDrawable(Color.TRANSPARENT);
        ColorDrawable defaultDrawable = new ColorDrawable(Color.TRANSPARENT);

        stateListDrawable.addState(new int[]{android.R.attr.state_focused}, focusedDrawable);
        stateListDrawable.addState(new int[]{}, defaultDrawable);

        return stateListDrawable;
    }
}
