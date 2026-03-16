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

    private static final double MAX_TOTAL_TIME = 5.0; // 5 seconds display window

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
        int w = getWidth();
        float padding = 15f;
        float graphW = w - 2 * padding;

        float xAttack = (float) (params.attackSec / MAX_TOTAL_TIME * graphW) + padding;
        float xSustainEnd = (float) ((params.attackSec + params.durationSec) / MAX_TOTAL_TIME * graphW) + padding;
        float xReleaseEnd = (float) ((params.attackSec + params.durationSec + params.releaseSec) / MAX_TOTAL_TIME * graphW) + padding;

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (Math.abs(x - xAttack) < 50) {
                    draggingPoint = 0;
                    return true;
                } else if (Math.abs(x - xSustainEnd) < 50) {
                    draggingPoint = 1;
                    return true;
                } else if (Math.abs(x - xReleaseEnd) < 50) {
                    draggingPoint = 2;
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (draggingPoint == 0) {
                    float newX = Math.max(padding, Math.min(x, xSustainEnd - 10));
                    params.attackSec = ((newX - padding) / graphW) * MAX_TOTAL_TIME;
                } else if (draggingPoint == 1) {
                    float newX = Math.max(xAttack + 10, Math.min(x, xReleaseEnd - 10));
                    params.durationSec = ((newX - xAttack) / graphW) * MAX_TOTAL_TIME;
                } else if (draggingPoint == 2) {
                    float newX = Math.max(xSustainEnd + 10, Math.min(x, padding + graphW));
                    params.releaseSec = ((newX - xSustainEnd) / graphW) * MAX_TOTAL_TIME;
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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (params == null) return;

        int w = getWidth();
        int h = getHeight();
        float padding = 15f;
        float graphW = w - 2 * padding;
        float graphH = h - 2 * padding;

        float xAttack = (float) (params.attackSec / MAX_TOTAL_TIME * graphW) + padding;
        float xSustainEnd = (float) ((params.attackSec + params.durationSec) / MAX_TOTAL_TIME * graphW) + padding;
        float xReleaseEnd = (float) ((params.attackSec + params.durationSec + params.releaseSec) / MAX_TOTAL_TIME * graphW) + padding;

        path.reset();
        path.moveTo(padding, h - padding);
        path.lineTo(xAttack, padding);
        path.lineTo(xSustainEnd, padding);
        path.lineTo(xReleaseEnd, h - padding);
        path.lineTo(padding, h - padding);

        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, linePaint);

        // Handles
        Paint hPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hPaint.setColor(Color.YELLOW);
        canvas.drawCircle(xAttack, padding, 10f, hPaint);
        canvas.drawCircle(xSustainEnd, padding, 10f, hPaint);
        canvas.drawCircle(xReleaseEnd, h - padding, 10f, hPaint);

        // Labels
        Paint tPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tPaint.setColor(Color.GRAY);
        tPaint.setTextSize(20f);
        canvas.drawText("A", xAttack - 5, h - 5, tPaint);
        canvas.drawText("S", xSustainEnd - 5, h - 5, tPaint);
        canvas.drawText("R", xReleaseEnd - 5, h - 5, tPaint);

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

    private float getNormalizedFreqY(double freq, float height) {
        double minLog = Math.log10(50);
        double maxLog = Math.log10(2000);
        double currentLog = Math.log10(Math.max(50, Math.min(2000, freq)));
        float normalized = (float) ((currentLog - minLog) / (maxLog - minLog));
        return (1.0f - normalized) * height;
    }
}
