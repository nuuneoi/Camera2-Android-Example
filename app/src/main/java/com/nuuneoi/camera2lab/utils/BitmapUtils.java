package com.nuuneoi.camera2lab.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.YuvImage;
import android.media.Image;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;

import java.nio.ByteBuffer;

public class BitmapUtils {
    /*
        Bitmap yuv420ToBitmap(Image image, Context context) {
            RenderScript rs = RenderScript.create(context);
            ScriptIntrinsicYuvToRGB script = ScriptIntrinsicYuvToRGB.create(
                    rs, Element.U8_4(rs));

            // Refer the logic in a section below on how to convert a YUV_420_888 image
            // to single channel flat 1D array. For sake of this example I'll abstract it
            // as a method.
            byte[] yuvByteArray = yuv420ToByteArray(image);

            Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs))
                    .setX(yuvByteArray.length);
            Allocation in = Allocation.createTyped(
                    rs, yuvType.create(), Allocation.USAGE_SCRIPT);

            Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs))
                    .setX(image.getWidth())
                    .setY(image.getHeight());
            Allocation out = Allocation.createTyped(
                    rs, rgbaType.create(), Allocation.USAGE_SCRIPT);

            // The allocations above "should" be cached if you are going to perform
            // repeated conversion of YUV_420_888 to Bitmap.
            in.copyFrom(yuvByteArray);
            script.setInput(in);
            script.forEach(out);

            Bitmap bitmap = Bitmap.createBitmap(
                    image.getWidth(), image.getHeight(), Bitmap.Config.ARGB_8888);
            out.copyTo(bitmap);
            return bitmap;
        }
    */
    public static YuvImage toYuvImage(Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            throw new IllegalArgumentException("Invalid image format");
        }

        int width = image.getWidth();
        int height = image.getHeight();

        // Order of U/V channel guaranteed, read more:
        // https://developer.android.com/reference/android/graphics/ImageFormat#YUV_420_888
        Image.Plane yPlane = image.getPlanes()[0];
        Image.Plane uPlane = image.getPlanes()[1];
        Image.Plane vPlane = image.getPlanes()[2];

        ByteBuffer yBuffer = yPlane.getBuffer();
        ByteBuffer uBuffer = uPlane.getBuffer();
        ByteBuffer vBuffer = vPlane.getBuffer();

        // Full size Y channel and quarter size U+V channels.
        int numPixels = (int) (width * height * 1.5f);
        byte[] nv21 = new byte[numPixels];
        int index = 0;

        // Copy Y channel.
        int yRowStride = yPlane.getRowStride();
        int yPixelStride = yPlane.getPixelStride();
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                nv21[index++] = yBuffer.get(y * yRowStride + x * yPixelStride);
            }
        }

        // Copy VU data; NV21 format is expected to have YYYYVU packaging.
        // The U/V planes are guaranteed to have the same row stride and pixel stride.
        int uvRowStride = uPlane.getRowStride();
        int uvPixelStride = uPlane.getPixelStride();
        int uvWidth = width / 2;
        int uvHeight = height / 2;

        for (int y = 0; y < uvHeight; ++y) {
            for (int x = 0; x < uvWidth; ++x) {
                int bufferIndex = (y * uvRowStride) + (x * uvPixelStride);
                // V channel.
                nv21[index++] = vBuffer.get(bufferIndex);
                // U channel.
                nv21[index++] = uBuffer.get(bufferIndex);
            }
        }
        return new YuvImage(
                nv21, ImageFormat.NV21, width, height, /* strides= */ null);
    }

}
