package com.interviewedge.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

public class ScoreRing extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private Double score;

  public ScoreRing(Context context, Double score) {
    super(context);
    this.score = score;
    setContentDescription(
        score == null
            ? "Practice score: not assessed"
            : String.format(java.util.Locale.US, "Practice score %.0f out of 100", score));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float size = Math.min(getWidth(), getHeight()) - 40,
        left = (getWidth() - size) / 2,
        top = (getHeight() - size) / 2;
    RectF bounds = new RectF(left + 10, top + 10, left + size - 10, top + size - 10);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(14);
    paint.setColor(0xffe1e6ef);
    c.drawOval(bounds, paint);
    if (score != null) {
      paint.setColor(0xff325deb);
      paint.setStrokeCap(Paint.Cap.ROUND);
      c.drawArc(bounds, -90, (float) (score * 3.6), false, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    paint.setColor(0xff17253f);
    paint.setTextSize(Math.min(size * .25f, 48 * getResources().getDisplayMetrics().scaledDensity));
    c.drawText(
        score == null ? "—" : String.format(java.util.Locale.US, "%.0f", score),
        getWidth() / 2f,
        getHeight() / 2f,
        paint);
    paint.setTextSize(Math.min(size * .09f, 16 * getResources().getDisplayMetrics().scaledDensity));
    c.drawText(
        score == null ? "Start your baseline" : "/ 100",
        getWidth() / 2f,
        getHeight() / 2f + 35,
        paint);
  }
}
