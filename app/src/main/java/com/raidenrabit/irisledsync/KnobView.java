package com.raidenrabit.irisledsync;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

/**
 * Rotary knob replacing a horizontal slider. Dragging turns it relative to where the
 * finger started, so touching never makes the value jump. Like Slider, onChange fires
 * while dragging and onStop once the finger lifts.
 */
public class KnobView extends View {

    public interface Listener {
        void onChange(int value);
        void onStop(int value);
    }

    private static final float START_ANGLE = 135f;
    private static final float SWEEP       = 270f;

    private final Paint trackPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect     = new RectF();

    private int min = 0, max = 100, value = 0;
    private String valueText = "";
    @Nullable private Listener listener;

    private float lastTouchAngle;
    private float dragValue;

    public KnobView(Context context) { this(context, null); }

    public KnobView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int primary   = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary);
        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface);
        int surface   = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(MaterialColors.compositeARGBWithAlpha(onSurface, 0x33));

        activePaint.set(trackPaint);
        activePaint.setColor(primary);

        knobPaint.setColor(MaterialColors.layer(surface, onSurface, 0.08f));
        dotPaint.setColor(primary);

        textPaint.setColor(onSurface);
        textPaint.setTextAlign(Paint.Align.CENTER);

        setFocusable(true);
    }

    public void setRange(int min, int max) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        setValue(value);
    }

    public void setValue(int v) {
        value = Math.max(min, Math.min(max, v));
        invalidate();
    }

    public int getValue() { return value; }

    public void setValueText(String text) {
        valueText = text;
        invalidate();
    }

    public void setListener(@Nullable Listener l) { listener = l; }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int def  = (int) (88 * getResources().getDisplayMetrics().density);
        int size = Math.min(resolveSize(def, widthSpec), resolveSize(def, heightSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // Stroke and text scale with the knob so a larger knob keeps the same proportions
        // (88dp → 6dp track, 14dp text).
        float size = Math.min(w, h);
        trackPaint.setStrokeWidth(size * 0.068f);
        activePaint.setStrokeWidth(size * 0.068f);
        textPaint.setTextSize(size * 0.16f);
    }

    @Override
    protected void onDraw(Canvas c) {
        float inset = trackPaint.getStrokeWidth();
        arcRect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float cx = arcRect.centerX(), cy = arcRect.centerY();
        float r  = arcRect.width() / 2f;

        float frac = (value - min) / (float) (max - min);
        c.drawArc(arcRect, START_ANGLE, SWEEP, false, trackPaint);
        if (frac > 0f) c.drawArc(arcRect, START_ANGLE, SWEEP * frac, false, activePaint);

        float knobR = r - inset * 1.5f;
        c.drawCircle(cx, cy, knobR, knobPaint);

        double a = Math.toRadians(START_ANGLE + SWEEP * frac);
        float dotDist = knobR - inset * 1.4f;
        c.drawCircle(cx + (float) (Math.cos(a) * dotDist),
                     cy + (float) (Math.sin(a) * dotDist), inset * 0.6f, dotPaint);

        float textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f;
        c.drawText(valueText, cx, textY, textPaint);
    }

    private float touchAngle(MotionEvent e) {
        return (float) Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f,
                                                 e.getX() - getWidth() / 2f));
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                lastTouchAngle = touchAngle(e);
                dragValue = value;
                return true;

            case MotionEvent.ACTION_MOVE: {
                float angle = touchAngle(e);
                float delta = angle - lastTouchAngle;
                if (delta > 180f)  delta -= 360f;
                if (delta < -180f) delta += 360f;
                lastTouchAngle = angle;

                dragValue = Math.max(min, Math.min(max, dragValue + delta / SWEEP * (max - min)));
                int v = Math.round(dragValue);
                if (v != value) {
                    value = v;
                    invalidate();
                    if (listener != null) listener.onChange(value);
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                getParent().requestDisallowInterceptTouchEvent(false);
                if (listener != null) listener.onStop(value);
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, min, max, value));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, @Nullable Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            int step = Math.max(1, (max - min) / 20);
            setValue(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step : -step));
            if (listener != null) {
                listener.onChange(value);
                listener.onStop(value);
            }
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
