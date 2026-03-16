package jp.example.shepardkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class EnvelopeView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spectralPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private ShepardGenerator.Params params;

    public interface OnEnvelopeChangeListener {
        void onEnvelopeChanged(double attack, double sustain, double release);
    }

    private OnEnvelopeChangeListener listener;
    private int draggingPoint = -1; // 0: Attack, 1: SustainEnd, 2: ReleaseEnd

    private static final double MAX_TOTAL_TIME = 5.0; // Total time shown in Fixed Duration mode
    private static final float SUSTAIN_WAVE_WIDTH = 60f; // Fixed symbolic width for sustain "}}"

    public EnvelopeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setColor(Color.CYAN);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);

        fillPaint.setColor(Color.parseColor("#4400FFFF"));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setParams(ShepardGenerator.Params params) {
        this.params = params;
        invalidate();
    }

    public void setOnEnvelopeChangeListener(OnEnvelopeChangeListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (params == null) return false;

        float x = event.getX();
        float y = event.getY();
        int w = getWidth();
        int h = getHeight();
        float padding = 15f;
        float graphW = w - 2 * padding;

        // Coordinate calculations for hit testing
        float xA, xS, xR, yR;
        if (params.fixedDuration) {
            xA = padding + (float) (params.attackSec / MAX_TOTAL_TIME * graphW);
            xS = xA + (float) (params.durationSec / MAX_TOTAL_TIME * graphW);
            xR = xS + (float) (params.releaseSec / MAX_TOTAL_TIME * graphW);
            xR = Math.min(xR, padding + graphW);
        } else {
            // Symbolic mode
            xA = padding + (float) (params.attackSec / MAX_TOTAL_TIME * graphW);
            xS = xA + SUSTAIN_WAVE_WIDTH;
            xR = xS + (float) (params.releaseSec / MAX_TOTAL_TIME * graphW);
            xR = Math.min(xR, padding + graphW);
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (dist(x, y, xA, padding) < 60) {
                    draggingPoint = 0;
                    return true;
                } else if (params.fixedDuration && dist(x, y, xS, padding) < 60) {
                    draggingPoint = 1;
                    return true;
                } else if (dist(x, y, xR, h - padding) < 60) {
                    draggingPoint = 2;
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (draggingPoint == 0) {
                    float limitX = params.fixedDuration ? xS - 10 : padding + graphW - SUSTAIN_WAVE_WIDTH - 20;
                    float newX = Math.max(padding, Math.min(x, limitX));
                    params.attackSec = ((newX - padding) / graphW) * MAX_TOTAL_TIME;
                } else if (draggingPoint == 1 && params.fixedDuration) {
                    float newX = Math.max(xA + 10, Math.min(x, xR - 10));
                    params.durationSec = ((newX - xA) / graphW) * MAX_TOTAL_TIME;
                } else if (draggingPoint == 2) {
                    float minX = xS + 5;
                    float newX = Math.max(minX, Math.min(x, padding + graphW));
                    params.releaseSec = ((newX - xS) / graphW) * MAX_TOTAL_TIME;
                }
                
                if (draggingPoint != -1) {
                    if (listener != null) listener.onEnvelopeChanged(params.attackSec, params.durationSec, params.releaseSec);
                    invalidate();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingPoint = -1;
                break;
        }
        return true;
    }

    private float dist(float x1, float y1, float x2, float y2) {
        return (float) Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (params == null) return;

        int w = getWidth();
        int h = getHeight();
        float padding = 15f;
        float graphW = w - 2 * padding;
        float graphH = h - 2 * padding;

        float xA, xS, xR;
        if (params.fixedDuration) {
            xA = padding + (float) (params.attackSec / MAX_TOTAL_TIME * graphW);
            xS = xA + (float) (params.durationSec / MAX_TOTAL_TIME * graphW);
            xR = xS + (float) (params.releaseSec / MAX_TOTAL_TIME * graphW);
        } else {
            // Symbolic mode: /}}\
            xA = padding + (float) (params.attackSec / MAX_TOTAL_TIME * graphW);
            xS = xA + SUSTAIN_WAVE_WIDTH;
            xR = xS + (float) (params.releaseSec / MAX_TOTAL_TIME * graphW);
        }
        float xR_vis = Math.min(xR, padding + graphW);

        path.reset();
        path.moveTo(padding, h - padding);
        path.lineTo(xA, padding);
        
        if (params.fixedDuration) {
            path.lineTo(xS, padding);
            path.lineTo(xR_vis, h - padding);
        } else {
            // Symbolic sustain wave "}}"
            drawWaveline(path, xA, padding, xS, padding, 15, 6);
            path.lineTo(xS, padding);
            path.lineTo(xR_vis, h - padding);
            // Optionally close the plateau for visualization if release is short
            if (xR_vis < padding + graphW) {
                // path.lineTo(padding + graphW, h - padding);
            }
        }
        
        path.lineTo(padding, h - padding);
        path.close();

        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, linePaint);

        // Handles
        Paint hPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hPaint.setColor(Color.YELLOW);
        canvas.drawCircle(xA, padding, 10f, hPaint);
        
        if (params.fixedDuration) {
            canvas.drawCircle(xS, padding, 10f, hPaint);
        }
        
        canvas.drawCircle(xR_vis, h - padding, 10f, hPaint);

        // Labels
        Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tPaint.setColor(Color.GRAY);
        tPaint.setTextSize(20f);
        canvas.drawText("A", xA - 5, h - 5, tPaint);
        if (params.fixedDuration) {
            canvas.drawText("S", xS - 5, h - 5, tPaint);
        }
        canvas.drawText("R", xR_vis - 5, h - 5, tPaint);

        // Spectral glow
        float centerLineY = getNormalizedFreqY(params.centerFreq, graphH) + padding;
        float sigmaOffset = (float)(params.sigma * 40f); 
        int centerColor = Color.argb(120, 255, 204, 0);
        int edgeColor = Color.TRANSPARENT;
        LinearGradient spectralGradient = new LinearGradient(0, centerLineY - sigmaOffset - 20, 0, centerLineY + sigmaOffset + 20,
                new int[]{edgeColor, centerColor, edgeColor}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        spectralPaint.setShader(spectralGradient);
        canvas.save();
        canvas.clipPath(path);
        canvas.drawRect(padding, padding, graphW + padding, graphH + padding, spectralPaint);
        canvas.restore();
    }

    private void drawWaveline(Path p, float xStart, float yStart, float xEnd, float yEnd, float waveLen, float waveHeight) {
        float dx = xEnd - xStart;
        if (dx <= 0) return;
        
        int steps = (int) (dx / waveLen);
        if (steps < 1) {
            p.lineTo(xEnd, yEnd);
            return;
        }

        for (int i = 1; i <= steps; i++) {
            float t = (float) i / steps;
            float x = xStart + dx * t;
            // Symbolic wave pattern }}
            float offset = (i % 2 == 1) ? waveHeight : -waveHeight;
            p.lineTo(x, yStart + offset);
        }
        p.lineTo(xEnd, yEnd);
    }

    private float getNormalizedFreqY(double freq, float height) {
        double minLog = Math.log10(50);
        double maxLog = Math.log10(2000);
        double currentLog = Math.log10(Math.max(50, Math.min(2000, freq)));
        float normalized = (float) ((currentLog - minLog) / (maxLog - minLog));
        return (1.0f - normalized) * height;
    }
}
