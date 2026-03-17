package jp.example.shepardkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class XYPadView extends View {

    private Paint paintGrid;
    private Paint paintIndicator;
    private Paint paintBackground;
    private Paint paintText;

    private float touchX = 0.5f;
    private float touchY = 0.5f;

    private String labelX = "X";
    private String labelY = "Y";

    public interface OnXYChangedListener {
        void onXYChanged(float x, float y);
    }

    private OnXYChangedListener listener;

    public XYPadView(Context context) {
        super(context);
        init();
    }

    public XYPadView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public XYPadView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paintBackground = new Paint();
        paintBackground.setColor(Color.parseColor("#1A1A1A"));
        paintBackground.setStyle(Paint.Style.FILL);

        paintGrid = new Paint();
        paintGrid.setColor(Color.parseColor("#333333"));
        paintGrid.setStrokeWidth(2f);
        paintGrid.setStyle(Paint.Style.STROKE);

        paintIndicator = new Paint();
        paintIndicator.setColor(Color.parseColor("#FFCC00"));
        paintIndicator.setStrokeWidth(4f);
        paintIndicator.setAntiAlias(true);
        paintIndicator.setStyle(Paint.Style.FILL);

        paintText = new Paint();
        paintText.setColor(Color.parseColor("#666666"));
        paintText.setTextSize(24f);
        paintText.setAntiAlias(true);
    }

    public void setOnXYChangedListener(OnXYChangedListener listener) {
        this.listener = listener;
    }

    public void setLabels(String x, String y) {
        this.labelX = x;
        this.labelY = y;
        invalidate();
    }

    public void setValues(float x, float y) {
        this.touchX = Math.max(0, Math.min(1, x));
        this.touchY = Math.max(0, Math.min(1, y));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        // Draw background with rounded corners (using canvas clip or just rect)
        canvas.drawRect(0, 0, w, h, paintBackground);

        // Draw grid
        canvas.drawLine(w / 2f, 0, w / 2f, h, paintGrid);
        canvas.drawLine(0, h / 2f, w, h / 2f, paintGrid);

        // Draw labels
        canvas.drawText(labelX, w - 40, h / 2f - 10, paintText);
        canvas.drawText(labelY, w / 2f + 10, 30, paintText);

        // Draw indicator (Crosshair and Circle)
        float px = touchX * w;
        float py = (1 - touchY) * h; // Y is up

        // Crosshair lines
        paintIndicator.setAlpha(80);
        canvas.drawLine(px, 0, px, h, paintIndicator);
        canvas.drawLine(0, py, w, py, paintIndicator);

        // Dot
        paintIndicator.setAlpha(255);
        canvas.drawCircle(px, py, 15f, paintIndicator);
        
        // Inner Glow/Style
        paintIndicator.setColor(Color.WHITE);
        canvas.drawCircle(px, py, 5f, paintIndicator);
        paintIndicator.setColor(Color.parseColor("#FFCC00"));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                float x = event.getX() / getWidth();
                float y = 1 - (event.getY() / getHeight());
                
                touchX = Math.max(0, Math.min(1, x));
                touchY = Math.max(0, Math.min(1, y));
                
                if (listener != null) {
                    listener.onXYChanged(touchX, touchY);
                }
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
}
