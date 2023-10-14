package com.nuuneoi.camera2lab.manager;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Camera2ApiManager {
    private final String TAG = "Camera2ApiManager";

    private Context mContext;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private CameraManager mCameraManager;
    private TextureView mPreviewTextureView;
    private CameraDevice mCameraDevice;
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private CameraCaptureSession mCameraCaptureSessions;
    private ImageReader mImageReader;

    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    private boolean isCameraStarted;

    private final int FPS_TIMESTAMP_INTERVAL_MS = 3000;

    private ArrayList<Long> mFpsTimestampCounter = new ArrayList<>();

    private int mPreviewWidth = 1280;
    private int mPreviewHeight = 720;
    private int mImageReaderImageFormat = ImageFormat.YUV_420_888;

    private ImageReader.OnImageAvailableListener mImageAvailableListener;

    public Camera2ApiManager(Context context) {
        mContext = context;
    }

    public void setPreviewTextureView(TextureView textureView) {
        if (isCameraStarted) {
            throw new RuntimeException("Cannot set preview texture view once the camera has started");
        }

        if (mPreviewTextureView != null) {
            mPreviewTextureView.setSurfaceTextureListener(null);
            mPreviewTextureView = null;
        }
        mPreviewTextureView = textureView;
        if (mPreviewTextureView != null) {
            mPreviewTextureView.setSurfaceTextureListener(textureListener);
        }
    }

    public void setOnImageAvailableListener(ImageReader.OnImageAvailableListener listener) {
        if (isCameraStarted) {
            throw new RuntimeException("Cannot set Image Available Listner once the camera has started");
        }

        mImageAvailableListener = listener;
    }

    public boolean hasOnImageAvailableListener() {
        return mImageAvailableListener != null;
    }

    public void setPreviewDimension(int width, int height) {
        if (isCameraStarted) {
            throw new RuntimeException("Cannot set preview dimension once the camera has started");
        }

        mPreviewWidth = width;
        mPreviewHeight = height;
    }

    public void setImageReaderImageFormat(int format) {
        if (isCameraStarted) {
            throw new RuntimeException("Cannot set preview image format once the camera has started");
        }

        mImageReaderImageFormat = format;
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    public void startCamera() {
        if (isCameraStarted)
            return;

        isCameraStarted = true;

        startBackgroundThread();

        if (mPreviewTextureView == null || mPreviewTextureView.isAvailable()) {
            openCamera();
        }
    }

    public void stopCamera() {
        closeCamera();
        stopBackgroundThread();

        isCameraStarted = false;
    }

    // Internal
    @RequiresPermission(Manifest.permission.CAMERA)
    private void openCamera() {
        if (!isCameraStarted)
            return;

        try {
            mCameraManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = mCameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics cameraCharacteristics = mCameraManager.getCameraCharacteristics(id);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                    continue;

                Size[] previewSize = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
                Log.d(TAG, Arrays.toString(previewSize));

                mCameraManager.openCamera(id, stateCallback, null);
                break;
            }
        } catch (CameraAccessException e) {

        }
    }

    private void updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return");
        }
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        try {
            mCameraCaptureSessions.setRepeatingRequest(mCaptureRequestBuilder.build(), null, mBackgroundHandler);
        } catch (CameraAccessException e) {

        }
    }

    private void closeCamera() {
        if (null != mCameraDevice) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if (null != mImageReader) {
            mImageReader.close();
            mImageReader = null;
        }
    }

    private void createCameraPreview() {
        try {
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }

            mImageReader = ImageReader.newInstance(mPreviewWidth, mPreviewHeight, mImageReaderImageFormat, 2);
            List<Surface> outputSurfaces = new ArrayList<Surface>(2);
            if (mImageAvailableListener != null)
                outputSurfaces.add(mImageReader.getSurface());

            Surface previewSurface = null;
            if (mPreviewTextureView != null) {
                SurfaceTexture texture = mPreviewTextureView.getSurfaceTexture();
                texture.setDefaultBufferSize(mPreviewWidth, mPreviewHeight);
                previewSurface = new Surface(texture);
                outputSurfaces.add(previewSurface);
            }

            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (mPreviewTextureView != null && previewSurface != null)
                mCaptureRequestBuilder.addTarget(previewSurface);
            if (mImageAvailableListener != null)
                mCaptureRequestBuilder.addTarget(mImageReader.getSurface());

            if (mPreviewTextureView != null) {
                WindowManager windowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
                int rotation = windowManager.getDefaultDisplay().getRotation();
                if (rotation == Surface.ROTATION_90)
                    mPreviewTextureView.setRotation(270);
            }

            ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    // Add Timestamp to FPS Calculator
                    if (mPreviewTextureView == null)
                        addFpsCurrentTimestamp();

                    // Call the ImageAvailableListener if set
                    if (mImageAvailableListener != null) {
                        mImageAvailableListener.onImageAvailable(imageReader);
                        return;
                    }

                    // Clear stack for the no-listener case
                    Image image = null;
                    try {
                        image = mImageReader.acquireLatestImage();
                    } finally {
                        if (image != null) {
                            image.close();
                        }
                    }
                }
            };
            mImageReader.setOnImageAvailableListener(readerListener, mBackgroundHandler);

            mCameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    //The camera is already closed
                    if (null == mCameraDevice) {
                        return;
                    }
                    // When the session is ready, we start displaying the preview.
                    mCameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(mContext, "Configuration change", Toast.LENGTH_SHORT).show();
                }
            }, null);
        } catch (CameraAccessException e) {

        }
    }

    private void addFpsCurrentTimestamp() {
        mFpsTimestampCounter.add(System.currentTimeMillis());
        long lastTimestampToPrune = System.currentTimeMillis() - FPS_TIMESTAMP_INTERVAL_MS;
        for (int i = mFpsTimestampCounter.size() - 1; i >= 0; i--) {
            if (mFpsTimestampCounter.get(i) < lastTimestampToPrune)
                mFpsTimestampCounter.remove(i);
        }
    }

    public long getCurrentPreviewFps() {
        return mFpsTimestampCounter.size() * 1000 / FPS_TIMESTAMP_INTERVAL_MS;
    }

    protected void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    protected void stopBackgroundThread() {
        if (mBackgroundThread == null || mBackgroundHandler == null)
            return;

        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Listener
    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @SuppressLint("MissingPermission")
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            // open your camera here
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            // Transform you image captured size according to the surface width and height
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            addFpsCurrentTimestamp();
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            // This is called when the camera is open
            Log.d(TAG, "onOpened");
            mCameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mCameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    };
}
