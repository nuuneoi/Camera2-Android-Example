package com.nuuneoi.camera2lab.encoder;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

public class MediaMuxerRecorder {

    private static final String TAG = "MediaMuxerRecorder";
    private static final String DIR_NAME = "CameraRecorder";
    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 15;
    private static final float BPP = 0.50f;

    private final int mWidth = 1280;
    private final int mHeight = 720;

    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);


    private String mOutputPath;
    private final MediaMuxer mMediaMuxer;
    private MediaCodec mMediaCodec;
    private Surface mSurface;

    MediaMuxerRecorder() throws IOException {
        mOutputPath = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4").toString();
        mMediaMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    // Public Functions
    public void prepare() throws IOException {
        final MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, mWidth, mHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, calcBitRate());
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10);

        mMediaCodec = MediaCodec.createEncoderByType(MIME_TYPE);
        mMediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();
    }

    synchronized void start() {
        mMediaMuxer.start();
    }

    synchronized void stop() {
        if (mMediaMuxer != null) {
            try {
                mMediaMuxer.stop();
            } catch (Exception e) {
            }
        }
    }

    protected void release() {
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
        if (mMediaCodec != null) {
            try {
                mMediaCodec.stop();
                mMediaCodec.release();
                mMediaCodec = null;
            } catch (final Exception e) {
                Log.e(TAG, "failed releasing MediaCodec", e);
            }
        }
        stop();
    }

    public Surface getInputSurface() {
        return mSurface;
    }

    // Internal Functions

    private int calcBitRate() {
        final int bitrate = (int)(BPP * FRAME_RATE * mWidth * mHeight);
        Log.i(TAG, String.format("bitrate=%5.2f[Mbps]", bitrate / 1024f / 1024f));
        return bitrate;
    }

    private static final File getCaptureFile(final String type, final String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(type), DIR_NAME);
        Log.d(TAG, "path=" + dir.toString());
        dir.mkdirs();
        if (dir.canWrite()) {
            return new File(dir, getDateTimeString() + ext);
        }
        return null;
    }

    private static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return mDateTimeFormat.format(now.getTime());
    }
}
