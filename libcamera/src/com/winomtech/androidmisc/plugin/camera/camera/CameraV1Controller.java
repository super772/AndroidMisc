package com.winomtech.androidmisc.plugin.camera.camera;

import android.annotation.TargetApi;
import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Build;
import android.view.MotionEvent;
import android.view.Surface;

import com.winom.olog.Log;
import com.winomtech.androidmisc.common.thread.ThreadPool;
import com.winomtech.androidmisc.common.utils.ApiLevel;
import com.winomtech.androidmisc.plugin.camera.utils.Rotation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * @author kevinhuang
 * @since 2015-04-01
 */
public class CameraV1Controller {
    final static String TAG = "CameraV1Controller";

    Camera mCamera;

    // 摄像头支持的比例放大比例的列表，和camera一起被初始化，如果不支持缩放，则不允许设置放大比例
    List<Integer> mZoomRatios = null;
    float mZoomValue = 100f;

    Activity mActivity;
    boolean mFocusEnd;
    boolean mUseFrontFace;        // 当前是否是使用前置摄像头
    Point mPreviewSize;

    int mFlashMode = ICameraLoader.MODE_OFF;

    int mDisplayRotate;
    int mMaxWidth;
    int mMaxHeight;
    int mFrameRate;

    public CameraV1Controller(Activity activity, boolean useFrontFace, CameraConfig config) {
        mActivity = activity;
        mFocusEnd = true;

        mUseFrontFace = useFrontFace;
        mMaxWidth = config.getWidth();
        mMaxHeight = config.getHeight();
        mFrameRate = config.getFrameRate();
    }

    public void setZoom(float factor) {
        if (null == mZoomRatios || null == mCamera) {
            return;
        }

        mZoomValue *= factor;
        try {
            if (mZoomValue < mZoomRatios.get(0)) {
                mZoomValue = mZoomRatios.get(0);
            }

            if (mZoomValue > mZoomRatios.get(mZoomRatios.size() - 1)) {
                mZoomValue = mZoomRatios.get(mZoomRatios.size() - 1);
            }

            Camera.Parameters params = mCamera.getParameters();
            int zoomIndex = getNearestZoomIndex((int) (mZoomValue));
            if (params.getZoom() != zoomIndex) {
                params.setZoom(zoomIndex);
                mCamera.setParameters(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "setZoom failed, " + e.getMessage());
        }
    }

    public void focusOnTouch(final MotionEvent event, final int viewWidth, final int viewHeight) {
        ThreadPool.post(new Runnable() {
            @Override
            public void run() {
                focusOnWorkerThread(event, viewWidth, viewHeight);
            }
        }, "autofocus");
    }

    void focusOnWorkerThread(final MotionEvent event, final int viewWidth, final int viewHeight) {
        if (null == mCamera) {
            Log.e(TAG, "camera not initialized");
            return;
        }

        if (false == mFocusEnd) {
            Log.d(TAG, "autofocusing...");
            return;
        }

        if (ApiLevel.getApiLevel() < ApiLevel.API14_ICE_CREAM_SANDWICH_40) {
            Log.e(TAG, "api level below 14, can't use foucs area");
            return;
        }

        Rect focusRect = calculateTapArea(event.getRawX(), event.getRawY(), mDisplayRotate, viewWidth, viewHeight, 1f);
        Rect meteringRect = calculateTapArea(event.getRawX(), event.getRawY(), mDisplayRotate, viewWidth, viewHeight, 1.5f);

        Camera.Parameters parameters = mCamera.getParameters();
        List<String> modes = parameters.getSupportedFocusModes();
        if (!modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
            Log.e(TAG, "camera don't support auto focus");
            return;
        }
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);

        if (parameters.getMaxNumFocusAreas() > 0) {
            List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
            focusAreas.add(new Camera.Area(focusRect, 1000));
            parameters.setFocusAreas(focusAreas);
        }

        if (parameters.getMaxNumMeteringAreas() > 0) {
            List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
            meteringAreas.add(new Camera.Area(meteringRect, 1000));

            parameters.setMeteringAreas(meteringAreas);
        }

        if (ICameraLoader.MODE_MANUAL == mFlashMode) {
            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
        }

        try {
            mCamera.setParameters(parameters);
            mCamera.autoFocus(mAutoFocusCallback);
            Log.i(TAG, "start autoFocus");
        } catch (Exception e) {
            Log.e(TAG, "autofocus failed, " + e.getMessage());
            mFocusEnd = true;
        }
    }

    /**
     * Convert touch position x:y to {@link Camera.Area} position -1000:-1000 to 1000:1000.
     */
    @SuppressWarnings("SuspiciousNameCombination")
    Rect calculateTapArea(float x, float y, int rotation, int viewWidth, int viewHeight, float coefficient) {
        float focusAreaSize = 300;
        int areaSize = Float.valueOf(focusAreaSize * coefficient).intValue();

        int tempX = (int) (x / viewWidth * 2000 - 1000);
        int tempY = (int) (y / viewHeight * 2000 - 1000);

        int centerX = 0, centerY = 0;
        if (90 == rotation) {
            centerX = tempY;
            centerY = (2000 - (tempX + 1000) - 1000);
        } else if (270 == rotation) {
            centerX = (2000 - (tempY + 1000)) - 1000;
            centerY = tempX;
        }

        int left = clamp(centerX - areaSize / 2, -1000, 1000);
        int right = clamp(left + areaSize, -1000, 1000);
        int top = clamp(centerY - areaSize / 2, -1000, 1000);
        int bottom = clamp(top + areaSize, -1000, 1000);

        return new Rect(left, top, right, bottom);
    }

    int clamp(int x, int min, int max) {
        if (x > max) return max;
        if (x < min) return min;
        return x;
    }

    int getNearestZoomIndex(int prefectVal) {
        int left = 0, right = mZoomRatios.size() - 1, middle;
        while (right - left > 1) {
            middle = (left + right) / 2;
            if (prefectVal > mZoomRatios.get(middle)) {
                left = middle;
            } else {
                right = middle;
            }
        }

        if (Math.abs(prefectVal - mZoomRatios.get(left)) > Math.abs(prefectVal - mZoomRatios.get(right))) {
            return right;
        } else {
            return left;
        }
    }

    Camera safeOpenCamera(boolean useFrontFace) {
        Log.i(TAG, "useFrontFace: " + useFrontFace);
        Camera camera = openCameraByHighApiLvl(useFrontFace);
        if (null == camera) {
            try {
                camera = Camera.open();
            } catch (Exception e) {
                Log.e(TAG, "openCameraFailed, " + e.getMessage());
            }
        }
        return camera;
    }

    Camera openCameraByHighApiLvl(boolean useFrontFace) {
        if (CameraCompat.gCameraInfo.getCameraNum() <= 0) {
            Log.i(TAG, "CameraNum is 0");
            return null;
        }

        Camera camera = null;
        try {
            if (true == useFrontFace) {
                camera = Camera.open(CameraCompat.gCameraInfo.getFrontId());
            } else {
                camera = Camera.open(CameraCompat.gCameraInfo.getBackId());
            }
        } catch (Exception e) {
            Log.e(TAG, "openCamera by high api level failed, " + e.getMessage());
        }

        return camera;
    }

    void safeSetPreviewFrameRate(Camera camera) {
        Camera.Parameters params = camera.getParameters();
        int fitRate = -1;

        List<Integer> rateList = params.getSupportedPreviewFrameRates();
        if (null == rateList || 0 == rateList.size()) {
            Log.e(TAG, "getSupportedPrviewFrameRates failed");
            return;
        }

        for (Integer rate : rateList) {
            Log.d(TAG, "supportPriviewFrameRate, rate: " + rate);
            if (rate <= mFrameRate && (-1 == fitRate || rate > fitRate)) {
                fitRate = rate;
            }
        }

        if (-1 == fitRate) {
            Log.e(TAG, "can't find fit rate, use camera default value");
            return;
        }

        try {
            Log.i(TAG, "setPreviewFrameRate, fitRate: " + fitRate);
            //noinspection deprecation
            params.setPreviewFrameRate(fitRate);
            camera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "setPreviewFrameRate failed, " + e.getMessage());
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    boolean safeSetPreviewSize(Camera camera) {
        Camera.Parameters params = camera.getParameters();
        Point size = null;

        List<Camera.Size> sizeLst = params.getSupportedPreviewSizes();
        if (null == sizeLst || 0 == sizeLst.size()) {
            Log.e(TAG, "getSupportedPrviewSizes failed");
            return false;
        }

        int diff = Integer.MAX_VALUE;
        for (Camera.Size it : sizeLst) {
            int width = it.width;
            int height = it.height;
            if (mDisplayRotate == 90 || mDisplayRotate == 270) {
                height = it.width;
                width = it.height;
            }

            Log.i(TAG, "supportPreview, width: %d, height: %d", width, height);
            if (width * height <= mMaxHeight * mMaxWidth) {
                int newDiff = diff(height, width, mMaxHeight, mMaxWidth);
                Log.d(TAG, "diff: " + newDiff);
                if (null == size || newDiff < diff) {
                    size = new Point(it.width, it.height);
                    diff = newDiff;
                }
            }
        }

        if (null == size) {
            Collections.sort(sizeLst, new Comparator<Camera.Size>() {
                @Override
                public int compare(Camera.Size lhs, Camera.Size rhs) {
                    return lhs.width * lhs.height - rhs.width * rhs.height;
                }
            });

            Camera.Size it = sizeLst.get(sizeLst.size() / 2);
            size = new Point(it.width, it.height);
        }

        try {
            Log.i(TAG, "setPreviewSize, width: %d, height: %d", size.x, size.y);
            params.setPreviewSize(size.x, size.y);
            if (mDisplayRotate == 90 || mDisplayRotate == 270) {
                mPreviewSize = new Point(size.y, size.x);
            } else {
                mPreviewSize = new Point(size.x, size.y);
            }
            camera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "setPreviewSize failed, " + e.getMessage());
            return false;
        }
        return true;
    }

    int diff(double realH, double realW, double expH, double expW) {
        double rateDiff = Math.abs(ICameraLoader.COEFFICIENT * (realH / realW - expH / expW));
        return (int) (rateDiff + Math.abs(realH - expH) + Math.abs(realW - expW));
    }

    void initRotateDegree(int cameraId) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(cameraId, info);
        Log.d(TAG, "cameraId: %d, roation: %d", cameraId, info.orientation);
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }
        mDisplayRotate = (info.orientation - degrees + 360) % 360;
    }

    Camera.AutoFocusCallback mAutoFocusCallback = new Camera.AutoFocusCallback() {
        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            mFocusEnd = true;
            if (success) {
                if (ICameraLoader.MODE_MANUAL == mFlashMode) {
                    switchLight(false);
                }
            }
        }
    };

    public boolean initCamera() {
        return initCamera(mUseFrontFace);
    }

    public boolean switchCamera() {
        releaseCamera();
        mUseFrontFace = !mUseFrontFace;
        return initCamera(mUseFrontFace);
    }

    public boolean isUseFrontFace() {
        return mUseFrontFace;
    }

    public void switchAutoFlash(boolean open) {
        if (null == mCamera) {
            return;
        }

        Log.i(TAG, "switch auto flash: " + open);
        try {
            Camera.Parameters params = mCamera.getParameters();
            if (open) {
                // android L上面有BUG会导致开了auto之后，无法再off。
                if (Build.VERSION.SDK_INT < ApiLevel.API21_KLOLLIPOP &&
                        params.getSupportedFlashModes().contains(Camera.Parameters.FLASH_MODE_AUTO)) {
                    params.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
                    mFlashMode = ICameraLoader.MODE_AUTO;
                } else {
                    mFlashMode = ICameraLoader.MODE_MANUAL;
                }
            } else {
                params.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                mFlashMode = ICameraLoader.MODE_OFF;
            }
            Log.d(TAG, "flash mode: " + params.getFlashMode());
            mCamera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "can't set flash mode");
        }
    }

    public void switchLight(boolean open) {
        if (null == mCamera) {
            return;
        }

        try {
            Camera.Parameters params = mCamera.getParameters();
            if (open) {
                params.setFlashMode(Camera.Parameters.FLASH_MODE_TORCH);
            } else {
                params.setFlashMode(ICameraLoader.MODE_MANUAL == mFlashMode ? Camera.Parameters.FLASH_MODE_OFF :
                        Camera.Parameters.FLASH_MODE_AUTO);
            }
            mCamera.setParameters(params);
        } catch (Exception e) {
            Log.e(TAG, "light up failed, " + e.getMessage());
        }
    }

    boolean initCamera(boolean useFrontFace) {
        Log.d(TAG, "initCameraInGLThread");
        mCamera = safeOpenCamera(useFrontFace);
        if (null == mCamera) {
            Log.e(TAG, "open camera failed");
            return false;
        }

        int cameraId = useFrontFace ? CameraCompat.gCameraInfo.getFrontId() : CameraCompat.gCameraInfo.getBackId();
        initRotateDegree(cameraId);

        try {
            safeSetPreviewFrameRate(mCamera);

            // 设置预览图片大小
            if (!safeSetPreviewSize(mCamera)) {
                Log.e(TAG, "safeSetPreviewSize failed");
                return false;
            }

            Camera.Parameters parameters = mCamera.getParameters();
            mZoomRatios = null;
            if (parameters.isZoomSupported()) {
                mZoomRatios = parameters.getZoomRatios();
                Collections.sort(mZoomRatios);
                Log.d(TAG, "ratios: " + mZoomRatios);
                mZoomValue = 100f;
            } else {
                Log.e(TAG, "camera don't support zoom");
            }

            List<String> supportModes = parameters.getSupportedFocusModes();
            if (supportModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (supportModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            Log.i(TAG, "focusMode: " + parameters.getFocusMode());
            mCamera.setParameters(parameters);
        } catch (Exception e) {
            releaseCamera();
            Log.e(TAG, "setParametersError false");
            return false;
        }

        return true;
    }

    public void releaseCamera() {
        if (null != mCamera) {
            try {
                mCamera.stopPreview();
                mCamera.setPreviewCallback(null);
                mCamera.release();
            } catch (Exception e) {
                Log.e(TAG, "exception on releaseCameraInGLThread, " + e.getMessage());
            }
        }
        mCamera = null;
    }

    public Camera getCamera() {
        return mCamera;
    }

    public int getDisplayRotate() {
        return mDisplayRotate;
    }

    // 获取当前期望的帧率,设置给摄像头的有可能没生效,所以外围再做处理
    public int getCameraFrameRate() {
        return mFrameRate;
    }
}
