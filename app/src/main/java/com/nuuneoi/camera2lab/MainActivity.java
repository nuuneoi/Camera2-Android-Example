package com.nuuneoi.camera2lab;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import android.Manifest;

import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.nuuneoi.camera2lab.encoder.MediaEncoder;
import com.nuuneoi.camera2lab.manager.Camera2ApiManager;
import com.nuuneoi.camera2lab.utils.BitmapUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private final int CAMERA_WIDTH = 1280;
    private final int CAMERA_HEIGHT = 720;

    private Camera2ApiManager mCamera2ApiManager;

    private TextureView mPreviewTextureView;

    private Button btnTakePicture;
    private Button btnTakeVideo;

    private TextView tvFps;

    private static final int REQUEST_CAMERA_PERMISSION = 200;

    private boolean isProcessingImage = false;

    private boolean isPictureTakingRequested = false;

    private MediaEncoder mMediaEncoder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initInstances();
        initFpsUpdater();
    }

    private void initInstances() {
        mPreviewTextureView = (TextureView) findViewById(R.id.previewTextureView);

        tvFps = (TextView) findViewById(R.id.tvFps);

        btnTakePicture = (Button) findViewById(R.id.btnTakePicture);
        btnTakePicture.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePicture();
            }
        });
        btnTakeVideo = (Button) findViewById(R.id.btnTakeVideo);
        btnTakeVideo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
            }
        });
    }

    private void initFpsUpdater() {
        Timer fpsUpdaterTimer = new Timer();
        fpsUpdaterTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (mCamera2ApiManager != null)
                            tvFps.setText("FPS: " + mCamera2ApiManager.getCurrentPreviewFps());
                    }
                });
            }
        }, 0, 1000);
    }

    private void initCamera() {
        mCamera2ApiManager = new Camera2ApiManager(this);
        mCamera2ApiManager.setPreviewDimension(CAMERA_WIDTH, CAMERA_HEIGHT);
        // Comment the next line if you want to hide the preview
        mCamera2ApiManager.setPreviewTextureView(mPreviewTextureView);
        // Comment the next line if you don't want to get the preview frame
        mCamera2ApiManager.setOnImageAvailableListener(onImageAvailableListener);
    }

    ImageReader.OnImageAvailableListener onImageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader imageReader) {
            Image image = null;

            // Drop frame if the previous frame is still being processed
            if (isProcessingImage) {
                try {
                    image = imageReader.acquireLatestImage();
                } finally {
                    if (image != null) {
                        image.close();
                    }
                }
                return;
            }

            isProcessingImage = true;

            // Process Image
            try {
                image = imageReader.acquireLatestImage();

                if (isPictureTakingRequested) {
                    isPictureTakingRequested = false;
                    saveImage(image);
                    Toast.makeText(MainActivity.this, "Picture Taken", Toast.LENGTH_SHORT).show();
                }
            } finally {
                if (image != null) {
                    image.close();
                }
            }

            isProcessingImage = false;
        }
    };

    private void takePicture() {
        if (mCamera2ApiManager == null) {
            throw new RuntimeException("Camera2ApiManager has not been initialized yet");
        }
        if (!mCamera2ApiManager.hasOnImageAvailableListener()) {
            throw new RuntimeException("onImageAvailableListener required to take a picture");
        }
        isPictureTakingRequested = true;
    }

    private void saveImage(Image image) {
        final File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/picture.jpg");

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(file);
            YuvImage yuvImage = BitmapUtils.toYuvImage(image);
            yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 80, fos);
        } catch (Exception e) {
        } finally {
            try {
                fos.close();
            } catch (IOException e) {
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(MainActivity.this, "Sorry!!!, you can't use this app without granting permission", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        mMediaEncoder = new CustomMediaEncoder(CAMERA_WIDTH, CAMERA_HEIGHT);
        mMediaEncoder.start();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mCamera2ApiManager != null)
            mCamera2ApiManager.stopCamera();
        if (mMediaEncoder != null)
            mMediaEncoder.stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    class CustomMediaEncoder extends MediaEncoder {

        private static final String TAG = "CustomMediaEncoder";

        public CustomMediaEncoder(int width, int height) {
            super(width, height);
        }

        @Override
        protected void onSurfaceCreated(Surface surface) {
            super.onSurfaceCreated(surface);
            Log.d(TAG, "onSurfaceCreated");

            // Start Camera
            initCamera();
            mCamera2ApiManager.setMediaCodecSurface(surface);

            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            mCamera2ApiManager.startCamera();
        }

        @Override
        protected void onSurfaceDestroyed(Surface surface) {
            super.onSurfaceDestroyed(surface);
            Log.d(TAG, "onSurfaceDestroyed");
        }
    }
}