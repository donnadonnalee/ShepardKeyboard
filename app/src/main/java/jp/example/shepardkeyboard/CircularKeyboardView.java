package jp.example.shepardkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

public class CircularKeyboardView extends View {
    private Paint paintKey;
    private Paint paintText;
    private Paint paintStroke;
    private Paint paintActive;
    private RectF bounds = new RectF();
    private boolean[] enabledKeys = new boolean[12];
    private float[] envelopeLevels = new float[12];
    private int transposeOffset = 0;
    
    private static final int[] BLACK_KEYS = {1, 3, 6, 8, 10};

    public interface OnCircularTouchListener {
        void onNoteOn(int noteIndex, int oldNoteIndex, float x, float y, float pressure);
        void onNoteMove(int noteIndex, float x, float y, float pressure);
        void onNoteOff(int noteIndex);
    }

    private OnCircularTouchListener listener;
    private final Map<Integer, Integer> pointerToNote = new HashMap<>();

    public CircularKeyboardView(Context context) {
        super(context);
        init();
    }

    public CircularKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        paintKey = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintKey.setStyle(Paint.Style.FILL);

        paintStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintStroke.setStyle(Paint.Style.STROKE);
        paintStroke.setStrokeWidth(2f);
        paintStroke.setColor(Color.LTGRAY);

        paintText = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintText.setColor(Color.BLACK);
        paintText.setTextAlign(Paint.Align.CENTER);
        paintText.setTextSize(36f);

        paintActive = new Paint(Paint.ANTI_ALIAS_FLAG);
        paintActive.setStyle(Paint.Style.FILL);
        paintActive.setColor(Color.parseColor("#FFCC00"));

        for (int i = 0; i < 12; i++) {
            enabledKeys[i] = true;
        }
    }

    public void setOnCircularTouchListener(OnCircularTouchListener listener) {
        this.listener = listener;
    }

    public void setEnabledKeys(boolean[] enabled) {
        System.arraycopy(enabled, 0, this.enabledKeys, 0, 12);
        invalidate();
    }

    public void setTransposeOffset(int offset) {
        this.transposeOffset = offset;
        invalidate();
    }

    public void setEnvelopeLevels(float[] levels) {
        System.arraycopy(levels, 0, this.envelopeLevels, 0, 12);
        invalidate();
    }

    private boolean isBlackKey(int index) {
        for (int b : BLACK_KEYS) {
            if (b == index) return true;
        }
        return false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float centerX = w / 2f;
        float centerY = h / 2f;
        float radius = Math.min(centerX, centerY) * 0.9f;
        float innerRadius = radius * 0.4f;

        bounds.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);

        for (int i = 0; i < 12; i++) {
            // Start from 12 o'clock (-90 degrees)
            float startAngle = -90f + (i * 30f) - 15f;
            float sweepAngle = 30f;

            if (!enabledKeys[i]) {
                paintKey.setColor(Color.parseColor("#333333"));
            } else if (isBlackKey(i)) {
                paintKey.setColor(Color.BLACK);
            } else {
                paintKey.setColor(Color.WHITE);
            }

            canvas.drawArc(bounds, startAngle, sweepAngle, true, paintKey);
            
            // Visual feedback synchronized with transposed engine levels
            int engineLevelIndex = (i + transposeOffset + 12) % 12;
            float level = envelopeLevels[engineLevelIndex];
            if (enabledKeys[i] && level > 0.01f) {
                paintActive.setAlpha((int) (Math.min(1.0f, level) * 200));
                canvas.drawArc(bounds, startAngle, sweepAngle, true, paintActive);
            }

            canvas.drawArc(bounds, startAngle, sweepAngle, true, paintStroke);
            
            // Note names removed as requested
        }

        // Draw center circle
        paintKey.setColor(Color.parseColor("#1A1A1A"));
        canvas.drawCircle(centerX, centerY, innerRadius, paintKey);
        canvas.drawCircle(centerX, centerY, innerRadius, paintStroke);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                handleTouch(pointerId, event.getX(pointerIndex), event.getY(pointerIndex), true);
                break;
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    handleTouch(event.getPointerId(i), event.getX(i), event.getY(i), false);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                Integer note = pointerToNote.remove(pointerId);
                if (note != null && listener != null) {
                    listener.onNoteOff(note);
                }
                break;
        }
        invalidate();
        return true;
    }

    private void handleTouch(int pointerId, float x, float y, boolean isDown) {
        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float dx = x - centerX;
        float dy = y - centerY;
        float dist = (float) Math.hypot(dx, dy);
        float radiusMax = Math.min(centerX, centerY) * 0.9f;
        float innerRadius = radiusMax * 0.4f;

        if (dist < innerRadius || dist > radiusMax * 1.5f) {
            Integer prevNote = pointerToNote.remove(pointerId);
            if (prevNote != null && listener != null) {
                listener.onNoteOff(prevNote);
            }
            return;
        }

        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90;
        if (angle < 0) angle += 360;
        int noteIndex = (int) Math.round(angle / 30.0) % 12;

        if (!enabledKeys[noteIndex]) return;

        float normDist = (dist - innerRadius) / (radiusMax - innerRadius);
        normDist = Math.max(0, Math.min(1, normDist));

        Integer prevNote = pointerToNote.get(pointerId);
        if (isDown || (prevNote != null && prevNote != noteIndex)) {
            int oldNote = (prevNote != null) ? prevNote : -1;
            pointerToNote.put(pointerId, noteIndex);
            if (listener != null) {
                listener.onNoteOn(noteIndex, oldNote, x, y, normDist);
            }
        } else if (prevNote != null) {
            if (listener != null) {
                listener.onNoteMove(noteIndex, x, y, normDist);
            }
        }
    }
}
