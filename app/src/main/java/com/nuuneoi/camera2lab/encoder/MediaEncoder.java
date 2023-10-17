package com.nuuneoi.camera2lab.encoder;

import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

public class MediaEncoder {

    private static final String TAG = "MediaEncoder";
    private static final String DIR_NAME = "CameraRecorder";

    String VIDEO_FORMAT = "video/avc";
    int VIDEO_FRAME_PER_SECOND = 30;
    int VIDEO_I_FRAME_INTERVAL = 10;
    int VIDEO_BITRATE = 3000 * 1000;

    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);

    private Worker mWorker;
    private int mWidth = 1280;
    private int mHeight = 720;


    public MediaEncoder(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    protected void onSurfaceCreated(Surface surface) {
    }

    protected void onSurfaceDestroyed(Surface surface) {
        Log.d(TAG, "onSurfaceDestroyed");
    }

    protected void onEncodedSample(MediaCodec.BufferInfo info, ByteBuffer data) {
        Log.d(TAG, "onEncodedSample");
    }

    public synchronized void start() {
        stop();
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRecording(false);
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public synchronized void stop() {
        if (mWorker != null) {
            mWorker.setRecording(false);
            mWorker.setRunning(false);
            mWorker = null;
        }
    }

    public synchronized void startRecording() {
        stopRecording();
        if (mWorker == null) {
            mWorker = new Worker();
            mWorker.setRecording(true);
            mWorker.setRunning(true);
            mWorker.start();
        }
    }

    public synchronized void stopRecording() {
        if (mWorker != null) {
            mWorker.setRecording(false);
            mWorker.setRunning(false);
            mWorker = null;
        }
    }

    // Internal Thread

    class Worker extends Thread {

        MediaCodec.BufferInfo mBufferInfo;
        MediaCodec mCodec;
        int mCodeTrackIndex;
        MediaMuxer mMediaMuxer;
        volatile boolean mRunning;

        volatile boolean mRecording;
        Surface mSurface;
        final long mTimeoutUsec;

        private String mOutputPath;

        public Worker() {
            mBufferInfo = new MediaCodec.BufferInfo();
            mTimeoutUsec = 30000l;
            mOutputPath = getCaptureFile(Environment.DIRECTORY_MOVIES, ".mp4").toString();
        }

        public void setRunning(boolean running) {
            mRunning = running;
        }

        public void setRecording(boolean recording) {
            mRecording = recording;
        }

        @Override
        public void run() {
            prepare();
            try {
                while (mRunning) {
                    encode();
                }
                encode();
            } finally {
                release();
            }
        }

        @SuppressWarnings("deprecation")
        void encode() {
            if (!mRunning) {
                // if not running anymore, complete stream
                mCodec.signalEndOfInputStream();
            }

            for (; ; ) {
                int status = mCodec.dequeueOutputBuffer(mBufferInfo, mTimeoutUsec);
                Log.d(TAG, "Encoding Status: " + status);
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!mRunning) break;
                } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (mRecording) {
                        mCodeTrackIndex = mMediaMuxer.addTrack(mCodec.getOutputFormat());
                        mMediaMuxer.start();
                    }
                } else if (status >= 0) {
                    // encoded sample
                    ByteBuffer data = mCodec.getOutputBuffer(status);
                    if (!mRecording) {
                        mCodec.releaseOutputBuffer(status, false);
                        continue;
                    }
                    if (data != null) {
                        final int endOfStream = mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM;
                        // pass to whoever listens to
                        if (endOfStream == 0) {
                            mBufferInfo.presentationTimeUs = getPTSUs();
                            Log.d(TAG, mBufferInfo.size + " : Size");
                            onEncodedSample(mBufferInfo, data);
                            mMediaMuxer.writeSampleData(mCodeTrackIndex, data, mBufferInfo);
                            prevOutputPTSUs = mBufferInfo.presentationTimeUs;
                        }
                        // releasing buffer is important
                        mCodec.releaseOutputBuffer(status, false);
                        if (endOfStream == MediaCodec.BUFFER_FLAG_END_OF_STREAM) break;
                    }
                }
            }
        }

        void release() {
            if (mRecording) {
                try {
                    mMediaMuxer.stop();
                } catch (Exception e) {

                }
            }

            // notify about destroying surface first before actually destroying it
            // otherwise unexpected exceptions can happen, since we working in multiple threads
            // simultaneously
            onSurfaceDestroyed(mSurface);

            mCodec.stop();
            mCodec.release();
            mSurface.release();
        }

        void prepare() {
            // configure video output
            MediaFormat format = MediaFormat.createVideoFormat(VIDEO_FORMAT, mWidth, mHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_PER_SECOND);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, VIDEO_I_FRAME_INTERVAL);

            try {
                if (mRecording)
                    mMediaMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                mCodec = MediaCodec.createEncoderByType(VIDEO_FORMAT);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            mCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            // create surface associated with code
            mSurface = mCodec.createInputSurface();
            // notify codec to start watch surface and encode samples
            mCodec.start();

            onSurfaceCreated(mSurface);
        }

        private final File getCaptureFile(final String type, final String ext) {
            final File dir = new File(Environment.getExternalStoragePublicDirectory(type), DIR_NAME);
            Log.d(TAG, "path=" + dir.toString());
            dir.mkdirs();
            if (dir.canWrite()) {
                return new File(dir, getDateTimeString() + ext);
            }
            return null;
        }

        private final String getDateTimeString() {
            final GregorianCalendar now = new GregorianCalendar();
            return mDateTimeFormat.format(now.getTime());
        }

        private long prevOutputPTSUs = 0;
        protected long getPTSUs() {
            long result = System.nanoTime() / 1000L;
            // presentationTimeUs should be monotonic
            // otherwise muxer fail to write
            if (result < prevOutputPTSUs)
                result = (prevOutputPTSUs - result) + result;
            return result;
        }
    }

}
