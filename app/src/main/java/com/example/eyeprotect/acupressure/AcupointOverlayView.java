package com.example.eyeprotect.acupressure;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.example.eyeprotect.R;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import com.google.mlkit.vision.face.FaceLandmark;

import java.util.List;

public class AcupointOverlayView extends View {

    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint debugPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String acupointType = "太陽";

    private Face currentFace;
    private int sourceWidth = 0;
    private int sourceHeight = 0;
    private boolean mirrorFrontCamera = true;

    private ImageView leftHandGuide;
    private ImageView rightHandGuide;

    private PointF currentLeftScreenPoint;
    private PointF currentRightScreenPoint;

    private ValueAnimator leftHandAnimator;
    private ValueAnimator rightHandAnimator;

    private boolean massageActive = false;

    public AcupointOverlayView(Context context) {
        super(context);
        init();
    }

    public AcupointOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AcupointOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        pointPaint.setColor(Color.argb(235, 255, 70, 70));
        pointPaint.setStyle(Paint.Style.FILL);

        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(3.5f);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(30f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setShadowLayer(6f, 0f, 2f, Color.BLACK);

        hintPaint.setColor(Color.WHITE);
        hintPaint.setTextSize(34f);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setShadowLayer(5f, 0f, 2f, Color.BLACK);

        debugPaint.setColor(Color.YELLOW);
        debugPaint.setStyle(Paint.Style.FILL);
    }

    public void setHandViews(ImageView leftHandGuide, ImageView rightHandGuide) {
        this.leftHandGuide = leftHandGuide;
        this.rightHandGuide = rightHandGuide;
    }

    public void setSourceInfo(int sourceWidth, int sourceHeight, boolean mirrorFrontCamera) {
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.mirrorFrontCamera = mirrorFrontCamera;
    }

    public void setAcupointType(String acupointType) {
        this.acupointType = acupointType;
        stopHandAnimators();
        invalidate();
    }

    public void setMassageActive(boolean active) {
        this.massageActive = active;

        if (!active) {
            stopHandAnimators();

            if (leftHandGuide != null && currentLeftScreenPoint != null) {
                applyStaticHandPosition(leftHandGuide, currentLeftScreenPoint, true);
            }

            if (rightHandGuide != null && currentRightScreenPoint != null) {
                applyStaticHandPosition(rightHandGuide, currentRightScreenPoint, false);
            }
        } else {
            ensureHandAnimator(true);
            ensureHandAnimator(false);
        }
    }

    public void updateFace(Face face, String acupointType) {
        if (!this.acupointType.equals(acupointType)) {
            stopHandAnimators();
        }

        this.currentFace = face;
        this.acupointType = acupointType;
        invalidate();
    }

    public void clearFace() {
        this.currentFace = null;
        currentLeftScreenPoint = null;
        currentRightScreenPoint = null;

        hideHands();
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopHandAnimators();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (currentFace == null || sourceWidth <= 0 || sourceHeight <= 0) {
            canvas.drawText("請將臉部置中", getWidth() / 2f, getHeight() / 2f, hintPaint);
            hideHands();
            return;
        }

        drawSimplifiedDebugPoints(canvas, currentFace);

        PointF[] imagePoints = calculateSymmetricAcupoints(currentFace, acupointType);

        if (imagePoints == null || imagePoints.length < 2) {
            hideHands();
            return;
        }

        PointF p1 = imageToViewPoint(imagePoints[0]);
        PointF p2 = imageToViewPoint(imagePoints[1]);

        PointF screenLeft = p1.x <= p2.x ? p1 : p2;
        PointF screenRight = p1.x <= p2.x ? p2 : p1;

        currentLeftScreenPoint = screenLeft;
        currentRightScreenPoint = screenRight;

        drawAcupoint(canvas, screenLeft);
        drawAcupoint(canvas, screenRight);
        drawLabel(canvas, screenLeft, screenRight);

        updateHandState(screenLeft, screenRight);
    }

    private void drawSimplifiedDebugPoints(Canvas canvas, Face face) {
        List<PointF> leftEyeContour = getContourPoints(face, FaceContour.LEFT_EYE);
        List<PointF> rightEyeContour = getContourPoints(face, FaceContour.RIGHT_EYE);
        List<PointF> leftBrowTop = getContourPoints(face, FaceContour.LEFT_EYEBROW_TOP);
        List<PointF> rightBrowTop = getContourPoints(face, FaceContour.RIGHT_EYEBROW_TOP);
        List<PointF> noseBridge = getContourPoints(face, FaceContour.NOSE_BRIDGE);
        List<PointF> noseBottom = getContourPoints(face, FaceContour.NOSE_BOTTOM);

        PointF leftBrowCenter = getCenterPoint(leftBrowTop);
        PointF rightBrowCenter = getCenterPoint(rightBrowTop);
        PointF leftEyeCenter = getCenterPoint(leftEyeContour);
        PointF rightEyeCenter = getCenterPoint(rightEyeContour);
        PointF leftInnerEye = getEyeInnerCorner(leftEyeContour, rightEyeContour, true);
        PointF rightInnerEye = getEyeInnerCorner(leftEyeContour, rightEyeContour, false);
        PointF noseBridgeCenter = getCenterPoint(noseBridge);
        PointF noseBottomCenter = getCenterPoint(noseBottom);

        drawDebugPoint(canvas, leftBrowCenter);
        drawDebugPoint(canvas, rightBrowCenter);
        drawDebugPoint(canvas, leftEyeCenter);
        drawDebugPoint(canvas, rightEyeCenter);
        drawDebugPoint(canvas, leftInnerEye);
        drawDebugPoint(canvas, rightInnerEye);
        drawDebugPoint(canvas, noseBridgeCenter);
        drawDebugPoint(canvas, noseBottomCenter);
    }

    private void drawDebugPoint(Canvas canvas, PointF imagePoint) {
        if (imagePoint == null) {
            return;
        }

        PointF screenPoint = imageToViewPoint(imagePoint);
        canvas.drawCircle(screenPoint.x, screenPoint.y, 6f, debugPaint);
    }

    private void drawAcupoint(Canvas canvas, PointF point) {
        canvas.drawCircle(point.x, point.y, 14f, pointPaint);
        canvas.drawCircle(point.x, point.y, 19f, ringPaint);
    }

    private void drawLabel(Canvas canvas, PointF screenLeft, PointF screenRight) {
        float labelX = (screenLeft.x + screenRight.x) / 2f;
        float labelY;

        if ("承泣".equals(acupointType)) {
            labelY = Math.max(screenLeft.y, screenRight.y) + 38f;
        } else if ("睛明".equals(acupointType)) {
            labelY = Math.min(screenLeft.y, screenRight.y) - 28f;
        } else {
            labelY = Math.min(screenLeft.y, screenRight.y) - 32f;
        }

        canvas.drawText(acupointType, labelX, labelY, textPaint);
    }

    private PointF[] calculateSymmetricAcupoints(Face face, String type) {
        Rect rect = face.getBoundingBox();

        List<PointF> leftEyeContour = getContourPoints(face, FaceContour.LEFT_EYE);
        List<PointF> rightEyeContour = getContourPoints(face, FaceContour.RIGHT_EYE);

        List<PointF> leftBrowTop = getContourPoints(face, FaceContour.LEFT_EYEBROW_TOP);
        List<PointF> rightBrowTop = getContourPoints(face, FaceContour.RIGHT_EYEBROW_TOP);

        PointF leftEyeCenter = getCenterPoint(leftEyeContour);
        PointF rightEyeCenter = getCenterPoint(rightEyeContour);

        if (leftEyeCenter == null || rightEyeCenter == null) {
            leftEyeCenter = getLandmarkPoint(face, FaceLandmark.LEFT_EYE);
            rightEyeCenter = getLandmarkPoint(face, FaceLandmark.RIGHT_EYE);
        }

        if (leftEyeCenter == null || rightEyeCenter == null) {
            float fallbackY = rect.top + rect.height() * 0.42f;
            leftEyeCenter = new PointF(rect.left + rect.width() * 0.35f, fallbackY);
            rightEyeCenter = new PointF(rect.left + rect.width() * 0.65f, fallbackY);
        }

        PointF eyeA = leftEyeCenter.x <= rightEyeCenter.x ? leftEyeCenter : rightEyeCenter;
        PointF eyeB = leftEyeCenter.x <= rightEyeCenter.x ? rightEyeCenter : leftEyeCenter;

        List<PointF> browA = null;
        List<PointF> browB = null;

        PointF leftBrowCenter = getCenterPoint(leftBrowTop);
        PointF rightBrowCenter = getCenterPoint(rightBrowTop);

        if (leftBrowCenter != null && rightBrowCenter != null) {
            browA = leftBrowCenter.x <= rightBrowCenter.x ? leftBrowTop : rightBrowTop;
            browB = leftBrowCenter.x <= rightBrowCenter.x ? rightBrowTop : leftBrowTop;
        }

        float eyeDistance = Math.max(1f, Math.abs(eyeB.x - eyeA.x));
        float faceHeight = Math.max(1f, rect.height());

        PointF leftPoint;
        PointF rightPoint;

        switch (type) {
            case "睛明":
                leftPoint = getEyeInnerCorner(leftEyeContour, rightEyeContour, true);
                rightPoint = getEyeInnerCorner(leftEyeContour, rightEyeContour, false);

                if (leftPoint == null || rightPoint == null) {
                    leftPoint = new PointF(eyeA.x + eyeDistance * 0.18f, eyeA.y);
                    rightPoint = new PointF(eyeB.x - eyeDistance * 0.18f, eyeB.y);
                }

                leftPoint = new PointF(leftPoint.x, leftPoint.y - faceHeight * 0.010f);
                rightPoint = new PointF(rightPoint.x, rightPoint.y - faceHeight * 0.010f);
                break;

            case "承泣":
                PointF leftEyeCenterForChengqi = getCenterPoint(leftEyeContour);
                PointF rightEyeCenterForChengqi = getCenterPoint(rightEyeContour);

                PointF leftEyeBottom = getEyeBottomCenter(leftEyeContour);
                PointF rightEyeBottom = getEyeBottomCenter(rightEyeContour);

                if (leftEyeCenterForChengqi == null || rightEyeCenterForChengqi == null) {
                    leftPoint = new PointF(eyeA.x, eyeA.y + faceHeight * 0.075f);
                    rightPoint = new PointF(eyeB.x, eyeB.y + faceHeight * 0.075f);
                } else {
                    PointF aCenter = leftEyeCenterForChengqi.x <= rightEyeCenterForChengqi.x
                            ? leftEyeCenterForChengqi
                            : rightEyeCenterForChengqi;

                    PointF bCenter = leftEyeCenterForChengqi.x <= rightEyeCenterForChengqi.x
                            ? rightEyeCenterForChengqi
                            : leftEyeCenterForChengqi;

                    PointF aBottom = leftEyeCenterForChengqi.x <= rightEyeCenterForChengqi.x
                            ? leftEyeBottom
                            : rightEyeBottom;

                    PointF bBottom = leftEyeCenterForChengqi.x <= rightEyeCenterForChengqi.x
                            ? rightEyeBottom
                            : leftEyeBottom;

                    float aY = aBottom != null ? aBottom.y : aCenter.y;
                    float bY = bBottom != null ? bBottom.y : bCenter.y;

                    leftPoint = new PointF(aCenter.x, aY + faceHeight * 0.038f);
                    rightPoint = new PointF(bCenter.x, bY + faceHeight * 0.038f);
                }
                break;

            case "攢竹":
                leftPoint = getBrowInnerPoint(browA, browB, true);
                rightPoint = getBrowInnerPoint(browA, browB, false);

                if (leftPoint == null || rightPoint == null) {
                    leftPoint = new PointF(eyeA.x + eyeDistance * 0.10f, eyeA.y - faceHeight * 0.12f);
                    rightPoint = new PointF(eyeB.x - eyeDistance * 0.10f, eyeB.y - faceHeight * 0.12f);
                }

                leftPoint = new PointF(leftPoint.x, leftPoint.y + faceHeight * 0.020f);
                rightPoint = new PointF(rightPoint.x, rightPoint.y + faceHeight * 0.020f);
                break;

            case "魚腰":
                leftPoint = getBrowMiddlePoint(browA);
                rightPoint = getBrowMiddlePoint(browB);

                if (leftPoint == null || rightPoint == null) {
                    leftPoint = new PointF(eyeA.x, eyeA.y - faceHeight * 0.13f);
                    rightPoint = new PointF(eyeB.x, eyeB.y - faceHeight * 0.13f);
                }

                leftPoint = new PointF(leftPoint.x, leftPoint.y + faceHeight * 0.018f);
                rightPoint = new PointF(rightPoint.x, rightPoint.y + faceHeight * 0.018f);
                break;

            case "絲竹空":
                leftPoint = getBrowOuterPoint(browA, browB, true);
                rightPoint = getBrowOuterPoint(browA, browB, false);

                if (leftPoint == null || rightPoint == null) {
                    leftPoint = new PointF(eyeA.x - eyeDistance * 0.42f, eyeA.y - faceHeight * 0.10f);
                    rightPoint = new PointF(eyeB.x + eyeDistance * 0.42f, eyeB.y - faceHeight * 0.10f);
                }

                leftPoint = new PointF(leftPoint.x - eyeDistance * 0.045f, leftPoint.y + faceHeight * 0.020f);
                rightPoint = new PointF(rightPoint.x + eyeDistance * 0.045f, rightPoint.y + faceHeight * 0.020f);
                break;

            case "太陽":
                PointF leftOuterEye = getEyeOuterCorner(leftEyeContour, rightEyeContour, true);
                PointF rightOuterEye = getEyeOuterCorner(leftEyeContour, rightEyeContour, false);

                if (leftOuterEye == null || rightOuterEye == null) {
                    leftOuterEye = new PointF(eyeA.x - eyeDistance * 0.35f, eyeA.y);
                    rightOuterEye = new PointF(eyeB.x + eyeDistance * 0.35f, eyeB.y);
                }

                leftPoint = new PointF(
                        leftOuterEye.x - eyeDistance * 0.24f,
                        leftOuterEye.y + faceHeight * 0.020f
                );

                rightPoint = new PointF(
                        rightOuterEye.x + eyeDistance * 0.24f,
                        rightOuterEye.y + faceHeight * 0.020f
                );
                break;

            default:
                leftPoint = eyeA;
                rightPoint = eyeB;
                break;
        }

        PointF screenA = leftPoint.x <= rightPoint.x ? leftPoint : rightPoint;
        PointF screenB = leftPoint.x <= rightPoint.x ? rightPoint : leftPoint;

        return new PointF[]{screenA, screenB};
    }

    private void updateHandState(PointF screenLeft, PointF screenRight) {
        currentLeftScreenPoint = screenLeft;
        currentRightScreenPoint = screenRight;

        if (leftHandGuide == null || rightHandGuide == null) {
            return;
        }

        leftHandGuide.setVisibility(VISIBLE);
        rightHandGuide.setVisibility(VISIBLE);
        leftHandGuide.setAlpha(0.62f);
        rightHandGuide.setAlpha(0.62f);

        if (massageActive) {
            ensureHandAnimator(true);
            ensureHandAnimator(false);
        } else {
            stopHandAnimators();
            applyStaticHandPosition(leftHandGuide, screenLeft, true);
            applyStaticHandPosition(rightHandGuide, screenRight, false);
        }
    }

    private void applyStaticHandPosition(ImageView hand, PointF point, boolean isLeftSide) {
        if (hand == null || point == null) {
            return;
        }

        int w = hand.getWidth();
        int h = hand.getHeight();

        if (w <= 0) {
            w = dp(88);
        }

        if (h <= 0) {
            h = dp(88);
        }

        float anchorX = w * 0.50f;
        float anchorY = h * 0.32f;

        PointF tune = getHandFineTune(isLeftSide);

        hand.setVisibility(VISIBLE);
        hand.setAlpha(0.62f);
        hand.setX(point.x - anchorX + tune.x);
        hand.setY(point.y - anchorY + tune.y);
        hand.setRotation(isLeftSide ? -8f : 8f);
    }

    private void ensureHandAnimator(boolean isLeftSide) {
        ImageView hand = isLeftSide ? leftHandGuide : rightHandGuide;
        ValueAnimator animator = isLeftSide ? leftHandAnimator : rightHandAnimator;

        if (hand == null) {
            return;
        }

        if (animator != null && animator.isRunning()) {
            return;
        }

        ValueAnimator newAnimator = ValueAnimator.ofFloat(0f, 360f);
        newAnimator.setDuration(getAnimationDuration());
        newAnimator.setInterpolator(new LinearInterpolator());
        newAnimator.setRepeatCount(ValueAnimator.INFINITE);

        newAnimator.addUpdateListener(animation -> {
            float progressValue = (float) animation.getAnimatedValue();

            if (isLeftSide) {
                applyMassageMotion(leftHandGuide, currentLeftScreenPoint, true, progressValue);
            } else {
                applyMassageMotion(rightHandGuide, currentRightScreenPoint, false, progressValue);
            }
        });

        newAnimator.start();

        if (isLeftSide) {
            leftHandAnimator = newAnimator;
        } else {
            rightHandAnimator = newAnimator;
        }
    }

    private int getAnimationDuration() {
        switch (acupointType) {
            case "太陽":
                return 1400;
            case "絲竹空":
                return 1300;
            case "睛明":
            case "承泣":
            case "攢竹":
            case "魚腰":
                return 1000;
            default:
                return 1100;
        }
    }

    private void applyMassageMotion(ImageView hand, PointF point, boolean isLeftSide, float progressValue) {
        if (hand == null || point == null) {
            return;
        }

        int w = hand.getWidth();
        int h = hand.getHeight();

        if (w <= 0) {
            w = dp(88);
        }

        if (h <= 0) {
            h = dp(88);
        }

        float anchorX = w * 0.50f;
        float anchorY = h * 0.32f;

        PointF tune = getHandFineTune(isLeftSide);

        float baseX = point.x - anchorX + tune.x;
        float baseY = point.y - anchorY + tune.y;

        float x = baseX;
        float y = baseY;

        float press = (float) Math.sin(Math.toRadians(progressValue));
        float inwardDirection = isLeftSide ? 1f : -1f;
        float outwardDirection = isLeftSide ? -1f : 1f;

        switch (acupointType) {
            case "太陽": {
                double rad = Math.toRadians(progressValue);
                float radius = dp(10);

                x = baseX + (float) Math.cos(rad) * radius;
                y = baseY + (float) Math.sin(rad) * radius;
                hand.setRotation(isLeftSide ? -8f : 8f);
                break;
            }

            case "睛明":
                x = baseX + inwardDirection * dp(2);
                y = baseY + press * dp(8);
                hand.setRotation(isLeftSide ? -4f : 4f);
                break;

            case "承泣":
                x = baseX;
                y = baseY + press * dp(7);
                hand.setRotation(isLeftSide ? -3f : 3f);
                break;

            case "攢竹":
                x = baseX + inwardDirection * press * dp(8);
                y = baseY - press * dp(5);
                hand.setRotation(isLeftSide ? -6f : 6f);
                break;

            case "魚腰":
                x = baseX;
                y = baseY - press * dp(7);
                hand.setRotation(isLeftSide ? -5f : 5f);
                break;

            case "絲竹空":
                x = baseX + outwardDirection * press * dp(9);
                y = baseY + (float) Math.sin(Math.toRadians(progressValue * 2)) * dp(4);
                hand.setRotation(isLeftSide ? -10f : 10f);
                break;

            default:
                x = baseX;
                y = baseY + press * dp(6);
                hand.setRotation(isLeftSide ? -6f : 6f);
                break;
        }

        hand.setX(x);
        hand.setY(y);
    }

    private PointF getHandFineTune(boolean isLeftSide) {
        float inward = isLeftSide ? 1f : -1f;
        float outward = isLeftSide ? -1f : 1f;

        switch (acupointType) {
            case "睛明":
                return new PointF(inward * dp(4), dp(2));
            case "承泣":
                return new PointF(0, dp(5));
            case "攢竹":
                return new PointF(inward * dp(3), -dp(2));
            case "魚腰":
                return new PointF(0, -dp(2));
            case "絲竹空":
                return new PointF(outward * dp(4), 0);
            case "太陽":
                return new PointF(outward * dp(4), dp(2));
            default:
                return new PointF(0, 0);
        }
    }

    private void hideHands() {
        stopHandAnimators();

        if (leftHandGuide != null) {
            leftHandGuide.setVisibility(INVISIBLE);
        }

        if (rightHandGuide != null) {
            rightHandGuide.setVisibility(INVISIBLE);
        }
    }

    private void stopHandAnimators() {
        if (leftHandAnimator != null) {
            leftHandAnimator.cancel();
            leftHandAnimator = null;
        }

        if (rightHandAnimator != null) {
            rightHandAnimator.cancel();
            rightHandAnimator = null;
        }
    }

    private List<PointF> getContourPoints(Face face, int contourType) {
        FaceContour contour = face.getContour(contourType);

        if (contour == null) {
            return null;
        }

        List<PointF> points = contour.getPoints();

        if (points == null || points.isEmpty()) {
            return null;
        }

        return points;
    }

    private PointF getCenterPoint(List<PointF> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        float sumX = 0f;
        float sumY = 0f;

        for (PointF p : points) {
            sumX += p.x;
            sumY += p.y;
        }

        return new PointF(sumX / points.size(), sumY / points.size());
    }

    private PointF getLeftMostPoint(List<PointF> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        PointF result = points.get(0);

        for (PointF p : points) {
            if (p.x < result.x) {
                result = p;
            }
        }

        return result;
    }

    private PointF getRightMostPoint(List<PointF> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        PointF result = points.get(0);

        for (PointF p : points) {
            if (p.x > result.x) {
                result = p;
            }
        }

        return result;
    }

    private PointF getLowestPoint(List<PointF> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        PointF result = points.get(0);

        for (PointF p : points) {
            if (p.y > result.y) {
                result = p;
            }
        }

        return result;
    }

    private PointF getMiddleByX(List<PointF> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }

        PointF left = getLeftMostPoint(points);
        PointF right = getRightMostPoint(points);

        if (left == null || right == null) {
            return getCenterPoint(points);
        }

        float targetX = (left.x + right.x) / 2f;
        PointF nearest = points.get(0);
        float minDistance = Math.abs(nearest.x - targetX);

        for (PointF p : points) {
            float distance = Math.abs(p.x - targetX);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = p;
            }
        }

        return nearest;
    }

    private PointF getBrowInnerPoint(List<PointF> browA, List<PointF> browB, boolean wantLeftSide) {
        PointF aCenter = getCenterPoint(browA);
        PointF bCenter = getCenterPoint(browB);

        if (aCenter == null || bCenter == null) {
            return null;
        }

        List<PointF> leftBrow = aCenter.x <= bCenter.x ? browA : browB;
        List<PointF> rightBrow = aCenter.x <= bCenter.x ? browB : browA;

        if (wantLeftSide) {
            return getRightMostPoint(leftBrow);
        } else {
            return getLeftMostPoint(rightBrow);
        }
    }

    private PointF getBrowMiddlePoint(List<PointF> brow) {
        return getMiddleByX(brow);
    }

    private PointF getBrowOuterPoint(List<PointF> browA, List<PointF> browB, boolean wantLeftSide) {
        PointF aCenter = getCenterPoint(browA);
        PointF bCenter = getCenterPoint(browB);

        if (aCenter == null || bCenter == null) {
            return null;
        }

        List<PointF> leftBrow = aCenter.x <= bCenter.x ? browA : browB;
        List<PointF> rightBrow = aCenter.x <= bCenter.x ? browB : browA;

        if (wantLeftSide) {
            return getLeftMostPoint(leftBrow);
        } else {
            return getRightMostPoint(rightBrow);
        }
    }

    private PointF getEyeInnerCorner(List<PointF> eyeA, List<PointF> eyeB, boolean wantLeftSide) {
        PointF aCenter = getCenterPoint(eyeA);
        PointF bCenter = getCenterPoint(eyeB);

        if (aCenter == null || bCenter == null) {
            return null;
        }

        List<PointF> leftEye = aCenter.x <= bCenter.x ? eyeA : eyeB;
        List<PointF> rightEye = aCenter.x <= bCenter.x ? eyeB : eyeA;

        if (wantLeftSide) {
            return getRightMostPoint(leftEye);
        } else {
            return getLeftMostPoint(rightEye);
        }
    }

    private PointF getEyeOuterCorner(List<PointF> eyeA, List<PointF> eyeB, boolean wantLeftSide) {
        PointF aCenter = getCenterPoint(eyeA);
        PointF bCenter = getCenterPoint(eyeB);

        if (aCenter == null || bCenter == null) {
            return null;
        }

        List<PointF> leftEye = aCenter.x <= bCenter.x ? eyeA : eyeB;
        List<PointF> rightEye = aCenter.x <= bCenter.x ? eyeB : eyeA;

        if (wantLeftSide) {
            return getLeftMostPoint(leftEye);
        } else {
            return getRightMostPoint(rightEye);
        }
    }

    private PointF getEyeBottomCenter(List<PointF> eye) {
        if (eye == null || eye.isEmpty()) {
            return null;
        }

        PointF middle = getMiddleByX(eye);
        PointF lowest = getLowestPoint(eye);

        if (middle == null) {
            return lowest;
        }

        if (lowest == null) {
            return middle;
        }

        return new PointF(middle.x, lowest.y);
    }

    private PointF getLandmarkPoint(Face face, int landmarkType) {
        FaceLandmark landmark = face.getLandmark(landmarkType);

        if (landmark == null) {
            return null;
        }

        return landmark.getPosition();
    }

    private PointF imageToViewPoint(PointF imagePoint) {
        float viewWidth = getWidth();
        float viewHeight = getHeight();

        float scale = Math.max(
                viewWidth / (float) sourceWidth,
                viewHeight / (float) sourceHeight
        );

        float scaledWidth = sourceWidth * scale;
        float scaledHeight = sourceHeight * scale;

        float offsetX = (viewWidth - scaledWidth) / 2f;
        float offsetY = (viewHeight - scaledHeight) / 2f;

        float x = imagePoint.x * scale + offsetX;
        float y = imagePoint.y * scale + offsetY;

        if (mirrorFrontCamera) {
            x = viewWidth - x;
        }

        return new PointF(x, y);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
