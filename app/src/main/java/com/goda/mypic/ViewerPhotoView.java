package com.goda.mypic;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewParent;

import com.github.chrisbanes.photoview.PhotoView;

/**
 * 针对长图与放大浏览优化的 PhotoView：
 * PhotoView 原生只要水平边缘贴边就会放行父 ViewPager2 拦截，导致上下滑长图时轻微横向抖动就会误翻页。
 * 本类在手指超出 touchSlop 时锁定滑动主方向，长图下需更明确的横向主导才交还给 ViewPager2 翻页。
 */
public class ViewerPhotoView extends PhotoView {

    private static final int AXIS_NONE = 0;
    private static final int AXIS_VERTICAL = 1;
    private static final int AXIS_HORIZONTAL = 2;

    // 长图下触发横向翻页的最小横向阈值倍率与横纵比阈值（适度降低灵敏度，避免上下滑误翻页）
    private static final float LONG_IMAGE_HORIZ_SLOP_FACTOR = 1.35f;
    private static final float LONG_IMAGE_HORIZ_DOMINANCE = 1.65f;
    private static final float ZOOM_HORIZ_SLOP_FACTOR = 1.2f;
    private static final float ZOOM_HORIZ_DOMINANCE = 1.35f;

    private final int touchSlop;
    private boolean longImageMode = false;

    private float downX;
    private float downY;
    private int lockedAxis = AXIS_NONE;
    private boolean multiTouchInGesture = false;

    public ViewerPhotoView(Context context) {
        this(context, null);
    }

    public ViewerPhotoView(Context context, AttributeSet attr) {
        this(context, attr, 0);
    }

    public ViewerPhotoView(Context context, AttributeSet attr, int defStyle) {
        super(context, attr, defStyle);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    public void setLongImageMode(boolean longImageMode) {
        this.longImageMode = longImageMode;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getRawX();
                downY = ev.getRawY();
                lockedAxis = AXIS_NONE;
                multiTouchInGesture = false;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                multiTouchInGesture = true;
                break;
            default:
                break;
        }

        boolean handled = super.dispatchTouchEvent(ev);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            lockedAxis = AXIS_NONE;
            multiTouchInGesture = false;
            return handled;
        }

        if (!longImageMode && getScale() <= 1.01f) {
            return handled;
        }

        if (multiTouchInGesture || ev.getPointerCount() > 1) {
            multiTouchInGesture = true;
            disallowParentIntercept(true);
            return handled;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            updateParentInterceptPolicy(ev);
        } else if (action == MotionEvent.ACTION_DOWN) {
            disallowParentIntercept(true);
        }

        return handled;
    }

    private void updateParentInterceptPolicy(MotionEvent ev) {
        float totalDx = ev.getRawX() - downX;
        float totalDy = ev.getRawY() - downY;
        float absDx = Math.abs(totalDx);
        float absDy = Math.abs(totalDy);

        boolean canPanHorizontally = canScrollHorizontally(-1) || canScrollHorizontally(1);
        if (canPanHorizontally) {
            int direction = totalDx > 0 ? -1 : 1;
            if (canScrollHorizontally(direction)) {
                disallowParentIntercept(true);
            } else if (absDx >= touchSlop * ZOOM_HORIZ_SLOP_FACTOR
                    && absDx > absDy * ZOOM_HORIZ_DOMINANCE) {
                disallowParentIntercept(false);
            } else {
                disallowParentIntercept(true);
            }
            return;
        }

        if (lockedAxis == AXIS_VERTICAL) {
            disallowParentIntercept(true);
            return;
        }
        if (lockedAxis == AXIS_HORIZONTAL) {
            disallowParentIntercept(false);
            return;
        }

        if (absDx * absDx + absDy * absDy < (float) touchSlop * touchSlop) {
            disallowParentIntercept(true);
            return;
        }

        float slopFactor = longImageMode ? LONG_IMAGE_HORIZ_SLOP_FACTOR : ZOOM_HORIZ_SLOP_FACTOR;
        float dominance = longImageMode ? LONG_IMAGE_HORIZ_DOMINANCE : ZOOM_HORIZ_DOMINANCE;
        if (absDx >= touchSlop * slopFactor && absDx > absDy * dominance) {
            lockedAxis = AXIS_HORIZONTAL;
            disallowParentIntercept(false);
        } else {
            lockedAxis = AXIS_VERTICAL;
            disallowParentIntercept(true);
        }
    }

    private void disallowParentIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
        }
    }
}
