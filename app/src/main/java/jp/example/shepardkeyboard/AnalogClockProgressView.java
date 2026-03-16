package jp.example.shepardkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AnalogClockProgressView extends View {
    private Paint circlePaint;
    private Paint markPaint;
    private Paint hourHandPaint;
    private Paint minuteHandPaint;
    
    private int noteIndex = 0; // 0 to 11
    private double noteProgress = 0.0; // 0.0 to 1.0

    public AnalogClockProgressView(Context context) {
        super(context);
        init();
    }

    public AnalogClockProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.LTGRAY);
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(8f);

        markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        markPaint.setColor(Color.DKGRAY);
        markPaint.setStrokeWidth(4f);

        hourHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hourHandPaint.setColor(Color.RED);
        hourHandPaint.setStrokeWidth(12f);
        hourHandPaint.setStrokeCap(Paint.Cap.ROUND);

        minuteHandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        minuteHandPaint.setColor(Color.BLUE);
        minuteHandPaint.setStrokeWidth(8f);
        minuteHandPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setProgress(int noteIndex, double noteProgress) {
        this.noteIndex = noteIndex;
        this.noteProgress = noteProgress;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int centerX = getWidth() / 2;
        int centerY = getHeight() / 2;
        int radius = Math.min(centerX, centerY) - 20;

        // Draw clock circle
        canvas.drawCircle(centerX, centerY, radius, circlePaint);

        // Draw hour marks (12 positions)
        for (int i = 0; i < 12; i++) {
            double angle = Math.toRadians(i * 30 - 90);
            float startX = (float) (centerX + radius * 0.85 * Math.cos(angle));
            float startY = (float) (centerY + radius * 0.85 * Math.sin(angle));
            float endX = (float) (centerX + radius * Math.cos(angle));
            float endY = (float) (centerY + radius * Math.sin(angle));
            canvas.drawLine(startX, startY, endX, endY, markPaint);
        }

        // Draw hour hand (short hand) - points to the note index
        double hourAngle = Math.toRadians(noteIndex * 30 - 90);
        float hourHandLen = radius * 0.5f;
        canvas.drawLine(centerX, centerY,
                (float) (centerX + hourHandLen * Math.cos(hourAngle)),
                (float) (centerY + hourHandLen * Math.sin(hourAngle)),
                hourHandPaint);

        // Draw minute hand (long hand) - sweeps 360 degrees for each note progress
        double minuteAngle = Math.toRadians(noteProgress * 360 - 90);
        float minuteHandLen = radius * 0.8f;
        canvas.drawLine(centerX, centerY,
                (float) (centerX + minuteHandLen * Math.cos(minuteAngle)),
                (float) (centerY + minuteHandLen * Math.sin(minuteAngle)),
                minuteHandPaint);
        
        // Draw center dot
        canvas.drawCircle(centerX, centerY, 10f, markPaint);
    }
}
