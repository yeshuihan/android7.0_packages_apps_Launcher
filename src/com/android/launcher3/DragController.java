/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3;

import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import com.android.launcher3.accessibility.DragViewStateAnnouncer;
import com.android.launcher3.util.Thunk;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Class for initiating a drag within a view or across multiple views.
 */
public class DragController {
    private static final String TAG = "Launcher.DragController";

    /** Indicates the drag is a move.  */
    public static int DRAG_ACTION_MOVE = 0;

    /** Indicates the drag is a copy.  */
    public static int DRAG_ACTION_COPY = 1;

    public static final int SCROLL_DELAY = 500;
    public static final int RESCROLL_DELAY = PagedView.PAGE_SNAP_ANIMATION_DURATION + 150;

    private static final boolean PROFILE_DRAWING_DURING_DRAG = false;

    private static final int SCROLL_OUTSIDE_ZONE = 0;
    private static final int SCROLL_WAITING_IN_ZONE = 1;

    static final int SCROLL_NONE = -1;
    static final int SCROLL_LEFT = 0;
    static final int SCROLL_RIGHT = 1;

    private static final float MAX_FLING_DEGREES = 35f;

    @Thunk Launcher mLauncher;
    private Handler mHandler;

    // temporaries to avoid gc thrash
    private Rect mRectTemp = new Rect();
    private final int[] mCoordinatesTemp = new int[2];
    private final boolean mIsRtl;

    /** Whether or not we're dragging. */
    private boolean mDragging;

    /** Whether or not this is an accessible drag operation */
    private boolean mIsAccessibleDrag;

    /** X coordinate of the down event. */
    private int mMotionDownX;

    /** Y coordinate of the down event. */
    private int mMotionDownY;

    /** the area at the edge of the screen that makes the workspace go left
     *   or right while you're dragging.
     */
    private int mScrollZone;

    private DropTarget.DragObject mDragObject;

    /** Who can receive drop events */
    private ArrayList<DropTarget> mDropTargets = new ArrayList<DropTarget>();
    private ArrayList<DragListener> mListeners = new ArrayList<DragListener>();
    private DropTarget mFlingToDeleteDropTarget;

    /** The window token used as the parent for the DragView. */
    private IBinder mWindowToken;

    /** The view that will be scrolled when dragging to the left and right edges of the screen. */
    private View mScrollView;

    private View mMoveTarget;

    @Thunk DragScroller mDragScroller;
    @Thunk int mScrollState = SCROLL_OUTSIDE_ZONE;
    private ScrollRunnable mScrollRunnable = new ScrollRunnable();

    private DropTarget mLastDropTarget;

    private InputMethodManager mInputMethodManager;

    @Thunk int mLastTouch[] = new int[2];
    @Thunk long mLastTouchUpTime = -1;
    @Thunk int mDistanceSinceScroll = 0;

    private int mTmpPoint[] = new int[2];
    private Rect mDragLayerRect = new Rect();

    protected int mFlingToDeleteThresholdVelocity;
    private VelocityTracker mVelocityTracker;

    /**
     * Interface to receive notifications when a drag starts or stops
     */
    public interface DragListener {
        /**
         * A drag has begun
         *
         * @param source An object representing where the drag originated
         * @param info The data associated with the object that is being dragged
         * @param dragAction The drag action: either {@link DragController#DRAG_ACTION_MOVE}
         *        or {@link DragController#DRAG_ACTION_COPY}
         */
        void onDragStart(DragSource source, Object info, int dragAction);

        /**
         * The drag has ended
         */
        void onDragEnd();
    }
    
    /**
     * Used to create a new DragLayer from XML.
     */
    public DragController(Launcher launcher) {
        Resources r = launcher.getResources();
        mLauncher = launcher;
        mHandler = new Handler();
        mScrollZone = r.getDimensionPixelSize(R.dimen.scroll_zone);
        mVelocityTracker = VelocityTracker.obtain();

        float density = r.getDisplayMetrics().density;
        mFlingToDeleteThresholdVelocity =
                (int) (r.getInteger(R.integer.config_flingToDeleteMinVelocity) * density);
        mIsRtl = Utilities.isRtl(r);
    }

    public boolean dragging() {
        return mDragging;
    }

    /**
     * Starts a drag.
     *
     * @param v The view that is being dragged
     * @param bmp The bitmap that represents the view being dragged
     * @param source An object representing where the drag originated
     * @param dragInfo The data associated with the object that is being dragged
     * @param dragAction The drag action: either {@link #DRAG_ACTION_MOVE} or
     *        {@link #DRAG_ACTION_COPY}
     * @param viewImageBounds the position of the image inside the view
     * @param dragRegion Coordinates within the bitmap b for the position of item being dragged.
     *          Makes dragging feel more precise, e.g. you can clip out a transparent border
     */
    public void startDrag(View v, Bitmap bmp, DragSource source, Object dragInfo,
            Rect viewImageBounds, int dragAction, float initialDragViewScale) {
        int[] loc = mCoordinatesTemp;
        mLauncher.getDragLayer().getLocationInDragLayer(v, loc);
        int dragLayerX = loc[0] + viewImageBounds.left
                + (int) ((initialDragViewScale * bmp.getWidth() - bmp.getWidth()) / 2);
        int dragLayerY = loc[1] + viewImageBounds.top
                + (int) ((initialDragViewScale * bmp.getHeight() - bmp.getHeight()) / 2);

        startDrag(bmp, dragLayerX, dragLayerY, source, dragInfo, dragAction, null,
                null, initialDragViewScale, false);

        if (dragAction == DRAG_ACTION_MOVE) {
            v.setVisibility(View.GONE);
        }
    }

    /**
     * Starts a drag.
     *
     * @param b The bitmap to display as the drag image.  It will be re-scaled to the
     *          enlarged size.
     * @param dragLayerX The x position in the DragLayer of the left-top of the bitmap.
     * @param dragLayerY The y position in the DragLayer of the left-top of the bitmap.
     * @param source An object representing where the drag originated
     * @param dragInfo The data associated with the object that is being dragged
     * @param dragAction The drag action: either {@link #DRAG_ACTION_MOVE} or
     *        {@link #DRAG_ACTION_COPY}
     * @param dragRegion Coordinates within the bitmap b for the position of item being dragged.
     *          Makes dragging feel more precise, e.g. you can clip out a transparent border
     * @param accessible whether this drag should occur in accessibility mode
     */
    public DragView startDrag(Bitmap b, int dragLayerX, int dragLayerY,
            DragSource source, Object dragInfo, int dragAction, Point dragOffset, Rect dragRegion,
            float initialDragViewScale, boolean accessible) {
        if (PROFILE_DRAWING_DURING_DRAG) {
            android.os.Debug.startMethodTracing("Launcher");
        }
		// 隐藏软件盘
        // Hide soft keyboard, if visible
        if (mInputMethodManager == null) {
            mInputMethodManager = (InputMethodManager)
                    mLauncher.getSystemService(Context.INPUT_METHOD_SERVICE);
        }
        mInputMethodManager.hideSoftInputFromWindow(mWindowToken, 0);
		//调用监听器中onDragStart方法
        for (DragListener listener : mListeners) {
            listener.onDragStart(source, dragInfo, dragAction);
        }

        final int registrationX = mMotionDownX - dragLayerX;
        final int registrationY = mMotionDownY - dragLayerY;

        final int dragRegionLeft = dragRegion == null ? 0 : dragRegion.left;
        final int dragRegionTop = dragRegion == null ? 0 : dragRegion.top;

		// 记录当前的状态，设置为拖拽状态
        mDragging = true;
        mIsAccessibleDrag = accessible;

        mDragObject = new DropTarget.DragObject();
		//创建拖拽对象
        final DragView dragView = mDragObject.dragView = new DragView(mLauncher, b, registrationX,
                registrationY, 0, 0, b.getWidth(), b.getHeight(), initialDragViewScale);

        mDragObject.dragComplete = false;
        if (mIsAccessibleDrag) {
            // For an accessible drag, we assume the view is being dragged from the center.
             //对于访问的拖拽，我们假设视图被从中心拖动。
            mDragObject.xOffset = b.getWidth() / 2;
            mDragObject.yOffset = b.getHeight() / 2;
            mDragObject.accessibleDrag = true;
        } else {
            mDragObject.xOffset = mMotionDownX - (dragLayerX + dragRegionLeft);
            mDragObject.yOffset = mMotionDownY - (dragLayerY + dragRegionTop);
            mDragObject.stateAnnouncer = DragViewStateAnnouncer.createFor(dragView);
        }

        mDragObject.dragSource = source;
        mDragObject.dragInfo = dragInfo;

        if (dragOffset != null) {
            dragView.setDragVisualizeOffset(new Point(dragOffset));
        }
        if (dragRegion != null) {
            dragView.setDragRegion(new Rect(dragRegion));
        }
		// 触摸反馈
        mLauncher.getDragLayer().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        dragView.show(mMotionDownX, mMotionDownY);//显示DragView对象(将该DragView添加到DragLayer上)
        handleMoveEvent(mMotionDownX, mMotionDownY);//根据当前的位置处理移动事件
        return dragView;
    }

    /**
     * Draw the view into a bitmap.
     */
    Bitmap getViewBitmap(View v) {
        v.clearFocus();
        v.setPressed(false);

        boolean willNotCache = v.willNotCacheDrawing();
        v.setWillNotCacheDrawing(false);

        // Reset the drawing cache background color to fully transparent
        // for the duration of this operation
        int color = v.getDrawingCacheBackgroundColor();
        v.setDrawingCacheBackgroundColor(0);
        float alpha = v.getAlpha();
        v.setAlpha(1.0f);

        if (color != 0) {
            v.destroyDrawingCache();
        }
        v.buildDrawingCache();
        Bitmap cacheBitmap = v.getDrawingCache();
        if (cacheBitmap == null) {
            Log.e(TAG, "failed getViewBitmap(" + v + ")", new RuntimeException());
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(cacheBitmap);

        // Restore the view
        v.destroyDrawingCache();
        v.setAlpha(alpha);
        v.setWillNotCacheDrawing(willNotCache);
        v.setDrawingCacheBackgroundColor(color);

        return bitmap;
    }

    /**
     * Call this from a drag source view like this:
     *
     * <pre>
     *  @Override
     *  public boolean dispatchKeyEvent(KeyEvent event) {
     *      return mDragController.dispatchKeyEvent(this, event)
     *              || super.dispatchKeyEvent(event);
     * </pre>
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mDragging;
    }

    public boolean isDragging() {
        return mDragging;
    }

    /**
     * Stop dragging without dropping.
     */
    public void cancelDrag() {
        if (mDragging) {
            if (mLastDropTarget != null) {
                mLastDropTarget.onDragExit(mDragObject);
            }
            mDragObject.deferDragViewCleanupPostAnimation = false;
            mDragObject.cancelled = true;
            mDragObject.dragComplete = true;
            mDragObject.dragSource.onDropCompleted(null, mDragObject, false, false);
        }
        endDrag();
    }

    public void onAppsRemoved(final HashSet<String> packageNames, HashSet<ComponentName> cns) {
        // Cancel the current drag if we are removing an app that we are dragging
        if (mDragObject != null) {
            Object rawDragInfo = mDragObject.dragInfo;
            if (rawDragInfo instanceof ShortcutInfo) {
                ShortcutInfo dragInfo = (ShortcutInfo) rawDragInfo;
                for (ComponentName componentName : cns) {
                    if (dragInfo.intent != null) {
                        ComponentName cn = dragInfo.intent.getComponent();
                        boolean isSameComponent = cn != null && (cn.equals(componentName) ||
                                packageNames.contains(cn.getPackageName()));
                        if (isSameComponent) {
                            cancelDrag();
                            return;
                        }
                    }
                }
            }
        }
    }

    private void endDrag() {
        if (mDragging) {
            mDragging = false;
            mIsAccessibleDrag = false;
            clearScrollRunnable();
            boolean isDeferred = false;
            if (mDragObject.dragView != null) {
                isDeferred = mDragObject.deferDragViewCleanupPostAnimation;
                if (!isDeferred) {
                    mDragObject.dragView.remove();
                }
                mDragObject.dragView = null;
            }

            // Only end the drag if we are not deferred
            if (!isDeferred) {
                for (DragListener listener : new ArrayList<>(mListeners)) {
                    listener.onDragEnd();
                }
            }
        }

        releaseVelocityTracker();
    }

    /**
     * This only gets called as a result of drag view cleanup being deferred in endDrag();
     */
    void onDeferredEndDrag(DragView dragView) {
        dragView.remove();

        if (mDragObject.deferDragViewCleanupPostAnimation) {
            // If we skipped calling onDragEnd() before, do it now
            for (DragListener listener : new ArrayList<>(mListeners)) {
                listener.onDragEnd();
            }
        }
    }

    public void onDeferredEndFling(DropTarget.DragObject d) {
        d.dragSource.onFlingToDeleteCompleted();
    }

    /**
     * Clamps the position to the drag layer bounds.
     */
    private int[] getClampedDragLayerPos(float x, float y) {
        mLauncher.getDragLayer().getLocalVisibleRect(mDragLayerRect);
        mTmpPoint[0] = (int) Math.max(mDragLayerRect.left, Math.min(x, mDragLayerRect.right - 1));
        mTmpPoint[1] = (int) Math.max(mDragLayerRect.top, Math.min(y, mDragLayerRect.bottom - 1));
        return mTmpPoint;
    }

    long getLastGestureUpTime() {
        if (mDragging) {
            return System.currentTimeMillis();
        } else {
            return mLastTouchUpTime;
        }
    }

    void resetLastGestureUpTime() {
        mLastTouchUpTime = -1;
    }

    /**
     * Call this from a drag source view.
     */
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        @SuppressWarnings("all") // suppress dead code warning
        final boolean debug = false;
        if (debug) {
            Log.d(Launcher.TAG, "DragController.onInterceptTouchEvent " + ev + " mDragging="
                    + mDragging);
        }

        if (mIsAccessibleDrag) {
            return false;
        }

        // Update the velocity tracker
        acquireVelocityTrackerAndAddMovement(ev);

        final int action = ev.getAction();
        final int[] dragLayerPos = getClampedDragLayerPos(ev.getX(), ev.getY());
        final int dragLayerX = dragLayerPos[0];
        final int dragLayerY = dragLayerPos[1];

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                break;
            case MotionEvent.ACTION_DOWN:
                // Remember location of down touch
                mMotionDownX = dragLayerX;
                mMotionDownY = dragLayerY;
                mLastDropTarget = null;
                break;
            case MotionEvent.ACTION_UP:
                mLastTouchUpTime = System.currentTimeMillis();
                if (mDragging) {
                    PointF vec = isFlingingToDelete(mDragObject.dragSource);
                    if (!DeleteDropTarget.supportsDrop(mDragObject.dragInfo)) {
                        vec = null;
                    }
                    if (vec != null) {
                        dropOnFlingToDeleteTarget(dragLayerX, dragLayerY, vec);
                    } else {
                        drop(dragLayerX, dragLayerY);
                    }
                }
                endDrag();
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelDrag();
                break;
        }

        return mDragging;
    }

    /**
     * Sets the view that should handle move events.
     */
    void setMoveTarget(View view) {
        mMoveTarget = view;
    }    

    public boolean dispatchUnhandledMove(View focused, int direction) {
        return mMoveTarget != null && mMoveTarget.dispatchUnhandledMove(focused, direction);
    }

    private void clearScrollRunnable() {
        mHandler.removeCallbacks(mScrollRunnable);
        if (mScrollState == SCROLL_WAITING_IN_ZONE) {
            mScrollState = SCROLL_OUTSIDE_ZONE;
            mScrollRunnable.setDirection(SCROLL_RIGHT);
            mDragScroller.onExitScrollArea();
            mLauncher.getDragLayer().onExitScrollArea();
        }
    }

    private void handleMoveEvent(int x, int y) {
        mDragObject.dragView.move(x, y);//将拖拽对象平移移到对应位置

        // Drop on someone?
        final int[] coordinates = mCoordinatesTemp;
        DropTarget dropTarget = findDropTarget(x, y, coordinates);// 查找拖拽目标
        //更新拖拽对象的位置
        mDragObject.x = coordinates[0];
        mDragObject.y = coordinates[1];
        checkTouchMove(dropTarget);	// 检查拖动时的状态̬
        
		// 检查我们是否在滚动区域上空盘旋
        // Check if we are hovering over the scroll areas
        //计算两点之间的距离
        mDistanceSinceScroll += Math.hypot(mLastTouch[0] - x, mLastTouch[1] - y);
		//更新保存的坐标
        mLastTouch[0] = x;
        mLastTouch[1] = y;
        checkScrollState(x, y);// 对拖动时的翻页进行判断处理
    }

    public void forceTouchMove() {
        int[] dummyCoordinates = mCoordinatesTemp;
        DropTarget dropTarget = findDropTarget(mLastTouch[0], mLastTouch[1], dummyCoordinates);
        mDragObject.x = dummyCoordinates[0];
        mDragObject.y = dummyCoordinates[1];
        checkTouchMove(dropTarget);
    }

    private void checkTouchMove(DropTarget dropTarget) {
        if (dropTarget != null) {
            if (mLastDropTarget != dropTarget) {
                if (mLastDropTarget != null) {
                    mLastDropTarget.onDragExit(mDragObject);
                }
                dropTarget.onDragEnter(mDragObject);
            }
            dropTarget.onDragOver(mDragObject);
        } else {
            if (mLastDropTarget != null) {
                mLastDropTarget.onDragExit(mDragObject);
            }
        }
        mLastDropTarget = dropTarget;
    }

    @Thunk void checkScrollState(int x, int y) {
    	//获取翻页的距离
        final int slop = ViewConfiguration.get(mLauncher).getScaledWindowTouchSlop();
        final int delay = mDistanceSinceScroll < slop ? RESCROLL_DELAY : SCROLL_DELAY;
        final DragLayer dragLayer = mLauncher.getDragLayer();
        final int forwardDirection = mIsRtl ? SCROLL_RIGHT : SCROLL_LEFT;
        final int backwardsDirection = mIsRtl ? SCROLL_LEFT : SCROLL_RIGHT;

        if (x < mScrollZone) {
		//向前
            if (mScrollState == SCROLL_OUTSIDE_ZONE) {
                mScrollState = SCROLL_WAITING_IN_ZONE;
                if (mDragScroller.onEnterScrollArea(x, y, forwardDirection)) {
                    dragLayer.onEnterScrollArea(forwardDirection);
                    mScrollRunnable.setDirection(forwardDirection);
                    mHandler.postDelayed(mScrollRunnable, delay);
                }
            }
			
        } else if (x > mScrollView.getWidth() - mScrollZone) {
        //向后
            if (mScrollState == SCROLL_OUTSIDE_ZONE) {
                mScrollState = SCROLL_WAITING_IN_ZONE;
                if (mDragScroller.onEnterScrollArea(x, y, backwardsDirection)) {
                    dragLayer.onEnterScrollArea(backwardsDirection);
                    mScrollRunnable.setDirection(backwardsDirection);
                    mHandler.postDelayed(mScrollRunnable, delay);
                }
            }
        } else {
            clearScrollRunnable();
        }
    }

    /**
     * Call this from a drag source view.
     */
    public boolean onTouchEvent(MotionEvent ev) {
        if (!mDragging || mIsAccessibleDrag) {
            return false;
        }

        // Update the velocity tracker
        acquireVelocityTrackerAndAddMovement(ev);

        final int action = ev.getAction();
        final int[] dragLayerPos = getClampedDragLayerPos(ev.getX(), ev.getY());
        final int dragLayerX = dragLayerPos[0];
        final int dragLayerY = dragLayerPos[1];

        switch (action) {
        case MotionEvent.ACTION_DOWN:
            // Remember where the motion event started
            mMotionDownX = dragLayerX;
            mMotionDownY = dragLayerY;

            if ((dragLayerX < mScrollZone) || (dragLayerX > mScrollView.getWidth() - mScrollZone)) {
                mScrollState = SCROLL_WAITING_IN_ZONE;
                mHandler.postDelayed(mScrollRunnable, SCROLL_DELAY);
            } else {
                mScrollState = SCROLL_OUTSIDE_ZONE;
            }
            handleMoveEvent(dragLayerX, dragLayerY);
            break;
        case MotionEvent.ACTION_MOVE:
            handleMoveEvent(dragLayerX, dragLayerY);
            break;
        case MotionEvent.ACTION_UP:
            // Ensure that we've processed a move event at the current pointer location.
            handleMoveEvent(dragLayerX, dragLayerY);
            mHandler.removeCallbacks(mScrollRunnable);

            if (mDragging) {
				//再判断是否到达可删除的区域
                PointF vec = isFlingingToDelete(mDragObject.dragSource);
                if (!DeleteDropTarget.supportsDrop(mDragObject.dragInfo)) {
                    vec = null;
                }
                if (vec != null) {
                    dropOnFlingToDeleteTarget(dragLayerX, dragLayerY, vec);
                } else {
                    drop(dragLayerX, dragLayerY);
                }
            }
            endDrag();
            break;
        case MotionEvent.ACTION_CANCEL:
            mHandler.removeCallbacks(mScrollRunnable);
            cancelDrag();
            break;
        }

        return true;
    }

    /**
     * Since accessible drag and drop won't cause the same sequence of touch events, we manually
     * inject the appropriate state.
     */
    public void prepareAccessibleDrag(int x, int y) {
        mMotionDownX = x;
        mMotionDownY = y;
        mLastDropTarget = null;
    }

    /**
     * As above, since accessible drag and drop won't cause the same sequence of touch events,
     * we manually ensure appropriate drag and drop events get emulated for accessible drag.
     */
    public void completeAccessibleDrag(int[] location) {
        final int[] coordinates = mCoordinatesTemp;

        // We make sure that we prime the target for drop.
        DropTarget dropTarget = findDropTarget(location[0], location[1], coordinates);
        mDragObject.x = coordinates[0];
        mDragObject.y = coordinates[1];
        checkTouchMove(dropTarget);

        dropTarget.prepareAccessibilityDrop();
        // Perform the drop
        drop(location[0], location[1]);
        endDrag();
    }

    /**
     * Determines whether the user flung the current item to delete it.
     *
     * @return the vector at which the item was flung, or null if no fling was detected.
     */
    private PointF isFlingingToDelete(DragSource source) {
        if (mFlingToDeleteDropTarget == null) return null;
        if (!source.supportsFlingToDelete()) return null;

        ViewConfiguration config = ViewConfiguration.get(mLauncher);
        mVelocityTracker.computeCurrentVelocity(1000, config.getScaledMaximumFlingVelocity());

        if (mVelocityTracker.getYVelocity() < mFlingToDeleteThresholdVelocity) {
            // Do a quick dot product test to ensure that we are flinging upwards
            PointF vel = new PointF(mVelocityTracker.getXVelocity(),
                    mVelocityTracker.getYVelocity());
            PointF upVec = new PointF(0f, -1f);
            float theta = (float) Math.acos(((vel.x * upVec.x) + (vel.y * upVec.y)) /
                    (vel.length() * upVec.length()));
            if (theta <= Math.toRadians(MAX_FLING_DEGREES)) {
                return vel;
            }
        }
        return null;
    }

    private void dropOnFlingToDeleteTarget(float x, float y, PointF vel) {
        final int[] coordinates = mCoordinatesTemp;

        mDragObject.x = coordinates[0];
        mDragObject.y = coordinates[1];

        // Clean up dragging on the target if it's not the current fling delete target otherwise,
        // start dragging to it.
        if (mLastDropTarget != null && mFlingToDeleteDropTarget != mLastDropTarget) {
            mLastDropTarget.onDragExit(mDragObject);
        }

        // Drop onto the fling-to-delete target
        boolean accepted = false;
        mFlingToDeleteDropTarget.onDragEnter(mDragObject);
        // We must set dragComplete to true _only_ after we "enter" the fling-to-delete target for
        // "drop"
        mDragObject.dragComplete = true;
        mFlingToDeleteDropTarget.onDragExit(mDragObject);
        if (mFlingToDeleteDropTarget.acceptDrop(mDragObject)) {
            mFlingToDeleteDropTarget.onFlingToDelete(mDragObject, vel);
            accepted = true;
        }
        mDragObject.dragSource.onDropCompleted((View) mFlingToDeleteDropTarget, mDragObject, true,
                accepted);
    }

    private void drop(float x, float y) {
        final int[] coordinates = mCoordinatesTemp;
        final DropTarget dropTarget = findDropTarget((int) x, (int) y, coordinates);
		// x,y所在区域是否有合适的目标
        mDragObject.x = coordinates[0];
        mDragObject.y = coordinates[1];
        boolean accepted = false;
        if (dropTarget != null) {
            mDragObject.dragComplete = true;// 标记拖拽完成
            dropTarget.onDragExit(mDragObject);// 通知拖拽目的对象已离开
            if (dropTarget.acceptDrop(mDragObject)) {//是否支持放入
                dropTarget.onDrop(mDragObject);// 拖拽物被放置到拖拽目的   // 这个方法最终将拖拽对象放置到目标位置
                accepted = true;
            }
        }
        mDragObject.dragSource.onDropCompleted((View) dropTarget, mDragObject, false, accepted);
    }

    private DropTarget findDropTarget(int x, int y, int[] dropCoordinates) {
        final Rect r = mRectTemp;

        final ArrayList<DropTarget> dropTargets = mDropTargets;// 遍历拖拽目的对象
        final int count = dropTargets.size();
        for (int i=count-1; i>=0; i--) {
            DropTarget target = dropTargets.get(i);
            if (!target.isDropEnabled())// 是否支持放入
                continue;

            target.getHitRectRelativeToDragLayer(r);
			// 更新被拖拽物的位置信息
            mDragObject.x = x;
            mDragObject.y = y;
            if (r.contains(x, y)) {// 指定位置是否位于有效出发范围内

                dropCoordinates[0] = x;
                dropCoordinates[1] = y;
                mLauncher.getDragLayer().mapCoordInSelfToDescendent((View) target, dropCoordinates);

                return target;
            }
        }
        return null;
    }

    public void setDragScoller(DragScroller scroller) {
        mDragScroller = scroller;
    }

    public void setWindowToken(IBinder token) {
        mWindowToken = token;
    }

    /**
     * Sets the drag listner which will be notified when a drag starts or ends.
     */
    public void addDragListener(DragListener l) {
        mListeners.add(l);
    }

    /**
     * Remove a previously installed drag listener.
     */
    public void removeDragListener(DragListener l) {
        mListeners.remove(l);
    }

    /**
     * Add a DropTarget to the list of potential places to receive drop events.
     */
    public void addDropTarget(DropTarget target) {
        mDropTargets.add(target);
    }

    /**
     * Don't send drop events to <em>target</em> any more.
     */
    public void removeDropTarget(DropTarget target) {
        mDropTargets.remove(target);
    }

    /**
     * Sets the current fling-to-delete drop target.
     */
    public void setFlingToDeleteDropTarget(DropTarget target) {
        mFlingToDeleteDropTarget = target;
    }

    private void acquireVelocityTrackerAndAddMovement(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }

    private void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * Set which view scrolls for touch events near the edge of the screen.
     */
    public void setScrollView(View v) {
        mScrollView = v;
    }

    DragView getDragView() {
        return mDragObject.dragView;
    }

    private class ScrollRunnable implements Runnable {
        private int mDirection;

        ScrollRunnable() {
        }

        public void run() {
            if (mDragScroller != null) {
                if (mDirection == SCROLL_LEFT) {
                    mDragScroller.scrollLeft();
                } else {
                    mDragScroller.scrollRight();
                }
                mScrollState = SCROLL_OUTSIDE_ZONE;
                mDistanceSinceScroll = 0;
                mDragScroller.onExitScrollArea();
                mLauncher.getDragLayer().onExitScrollArea();

                if (isDragging()) {
                    // Check the scroll again so that we can requeue the scroller if necessary
                    checkScrollState(mLastTouch[0], mLastTouch[1]);
                }
            }
        }

        void setDirection(int direction) {
            mDirection = direction;
        }
    }
}
