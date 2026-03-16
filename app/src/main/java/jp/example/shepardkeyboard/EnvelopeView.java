package jp.example.shepardkeyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class EnvelopeView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spectralPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private ShepardGenerator.Params params;

    public EnvelopeView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        linePaint.setColor(Color.CYAN);
        linePaint.setStrokeWidth(4f);
        linePaint.setStyle(Paint.Style.STROKE);

        // Semi-transparent fill
        fillPaint.setColor(Color.parseColor("#4400FFFF"));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    public void setParams(ShepardGenerator.Params params) {
        this.params = params;
        invalidate(); // Redraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (params == null) return;

        int w = getWidth();
        int h = getHeight();
        float padding = 10f;

        float graphW = w - 2 * padding;
        float graphH = h - 2 * padding;

        double totalTime = params.attackSec + params.durationSec + params.releaseSec;
        if (totalTime <= 0) totalTime = 1.0;

        float xAttack = (float) (params.attackSec / totalTime * graphW) + padding;
        float xReleaseStart = (float) ((params.attackSec + params.durationSec) / totalTime * graphW) + padding;
        float xEnd = (float) (graphW + padding);

        path.reset();
        path.moveTo(padding, h - padding); // Start
        path.lineTo(xAttack, padding);      // End of Attack
        path.lineTo(xReleaseStart, padding); // Start of Release
        path.lineTo(xEnd, h - padding);     // End
        path.lineTo(padding, h - padding);  // Close path

        canvas.drawPath(path, fillPaint);
        canvas.drawPath(path, linePaint);

        // --- Spectral Visualization ---
        // Visualize Center Frequency (vertical position) and Sigma (gradient spread)
        float centerLineY = getNormalizedFreqY(params.centerFreq, graphH) + padding;
        
        // Define gradient colors based on sigma
        // Small sigma = tight gradient, large sigma = wide gradient
        float sigmaOffset = (float)(params.sigma * 40f); 
        
        int centerColor = Color.argb(100, 255, 255, 0); // Yellow glow
        int edgeColor = Color.TRANSPARENT;

        LinearGradient spectralGradient = new LinearGradient(
                0, centerLineY - sigmaOffset - 20,
                0, centerLineY + sigmaOffset + 20,
                new int[]{edgeColor, centerColor, edgeColor},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        
        spectralPaint.setShader(spectralGradient);
        
        // Clip to the envelope path so it only glows inside the envelope
        canvas.save();
        canvas.clipPath(path);
        canvas.drawRect(padding, padding, graphW + padding, graphH + padding, spectralPaint);
        canvas.restore();
    }

    private float getNormalizedFreqY(double freq, float height) {
        // Log scale mapping from 50Hz to 2000Hz (based on MainActivity ranges)
        double minLog = Math.log10(50);
        double maxLog = Math.log10(2000);
        double currentLog = Math.log10(Math.max(50, Math.min(2000, freq)));
        
        float normalized = (float) ((currentLog - minLog) / (maxLog - minLog));
        // High frequency at top (y=0), low frequency at bottom (y=height)
        return (1.0f - normalized) * height;
    }

}
