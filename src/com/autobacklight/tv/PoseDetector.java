package com.autobacklight.tv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.Image;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/** MoveNet MultiPose Lightning: finds up to 6 people and 17 body keypoints each. */
final class PoseDetector implements AutoCloseable {
    static final int NOSE = 0, L_SHOULDER = 5, R_SHOULDER = 6, L_ELBOW = 7, R_ELBOW = 8, L_WRIST = 9, R_WRIST = 10;
    private static final int IN_W = 256;           // model input width; height follows the frame aspect
    private static final float MIN_PERSON_SCORE = 0.25f;
    private static final float MIN_POINT_SCORE = 0.3f;

    /** Coordinates are normalized to the camera frame (0..1). */
    static final class Person {
        final float[] x = new float[17], y = new float[17], s = new float[17];
        float left, top, right, bottom, score;
    }

    private final Interpreter interpreter;
    private DataType inputType;
    private ByteBuffer input;
    private float[][][] output;
    private int inH, contentH, frameW, frameH;

    PoseDetector(Context context) throws IOException {
        MappedByteBuffer model;
        try (AssetFileDescriptor fd = context.getAssets().openFd("movenet_multipose.tflite");
             FileInputStream in = new FileInputStream(fd.getFileDescriptor())) {
            model = in.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(2); // leave CPU for whatever the TV is playing
        interpreter = new Interpreter(model, options);
    }

    /** The model takes any size that is a multiple of 32; keep the frame's aspect ratio and pad the bottom. */
    private void prepare(int w, int h) {
        if (w == frameW && h == frameH) return;
        frameW = w;
        frameH = h;
        contentH = Math.max(1, Math.round(IN_W * (float) h / w));
        inH = (contentH + 31) / 32 * 32;
        interpreter.resizeInput(0, new int[]{1, inH, IN_W, 3});
        interpreter.allocateTensors();
        inputType = interpreter.getInputTensor(0).dataType();
        int bytes = inputType == DataType.UINT8 ? 1 : 4;
        input = ByteBuffer.allocateDirect(IN_W * inH * 3 * bytes).order(ByteOrder.nativeOrder());
        int[] shape = interpreter.getOutputTensor(0).shape(); // [1, people, 56]
        output = new float[1][shape[1]][shape[2]];
    }

    List<Person> detect(Image img) {
        prepare(img.getWidth(), img.getHeight());
        fillInput(img);
        interpreter.run(input, output);

        List<Person> people = new ArrayList<>();
        float yScale = (float) inH / contentH; // undo the bottom padding
        for (float[] row : output[0]) {
            if (row[55] < MIN_PERSON_SCORE) continue;
            Person p = new Person();
            for (int k = 0; k < 17; k++) {
                p.y[k] = row[k * 3] * yScale;
                p.x[k] = row[k * 3 + 1];
                p.s[k] = row[k * 3 + 2];
            }
            p.top = row[51] * yScale;
            p.left = row[52];
            p.bottom = row[53] * yScale;
            p.right = row[54];
            p.score = row[55];
            people.add(p);
        }
        return people;
    }

    /** Nearest-neighbour downscale of the YUV frame straight into the model's RGB input. */
    private void fillInput(Image img) {
        Image.Plane[] planes = img.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer(), uBuf = planes[1].getBuffer(), vBuf = planes[2].getBuffer();
        int yRow = planes[0].getRowStride(), yPix = planes[0].getPixelStride();
        int uvRow = planes[1].getRowStride(), uvPix = planes[1].getPixelStride();
        int w = img.getWidth(), h = img.getHeight();

        input.rewind();
        for (int oy = 0; oy < inH; oy++) {
            int sy = oy * h / contentH;
            for (int ox = 0; ox < IN_W; ox++) {
                int r = 0, g = 0, b = 0;
                if (oy < contentH) {
                    int sx = ox * w / IN_W;
                    int yv = yBuf.get(sy * yRow + sx * yPix) & 0xff;
                    int uvi = (sy / 2) * uvRow + (sx / 2) * uvPix;
                    int u = (uBuf.get(uvi) & 0xff) - 128;
                    int v = (vBuf.get(uvi) & 0xff) - 128;
                    r = clamp(yv + (int) (1.402f * v));
                    g = clamp(yv - (int) (0.344f * u + 0.714f * v));
                    b = clamp(yv + (int) (1.772f * u));
                }
                put(r);
                put(g);
                put(b);
            }
        }
        input.rewind();
    }

    private void put(int v) {
        if (inputType == DataType.UINT8) input.put((byte) v);
        else if (inputType == DataType.FLOAT32) input.putFloat(v);
        else input.putInt(v);
    }

    /**
     * A hand counts as raised when a wrist is clearly above the head and its elbow is at least at
     * shoulder height (so scratching your head or resting a hand on it does not count).
     * aspect = frame width / height, to measure distances in the same units both ways.
     */
    static boolean handRaised(Person p, float aspect) {
        if (p.s[L_SHOULDER] < MIN_POINT_SCORE || p.s[R_SHOULDER] < MIN_POINT_SCORE) return false;
        float shoulderWidth = Math.max(0.02f, Math.abs(p.x[L_SHOULDER] - p.x[R_SHOULDER]) * aspect);
        float headY = p.s[NOSE] >= MIN_POINT_SCORE ? p.y[NOSE]
                : Math.min(p.y[L_SHOULDER], p.y[R_SHOULDER]) - 0.6f * shoulderWidth;
        return armUp(p, L_WRIST, L_ELBOW, L_SHOULDER, headY, shoulderWidth)
                || armUp(p, R_WRIST, R_ELBOW, R_SHOULDER, headY, shoulderWidth);
    }

    private static boolean armUp(Person p, int wrist, int elbow, int shoulder, float headY, float sw) {
        if (p.s[wrist] < MIN_POINT_SCORE) return false;
        boolean wristHigh = p.y[wrist] < headY - 0.25f * sw;
        boolean elbowUp = p.s[elbow] < MIN_POINT_SCORE || p.y[elbow] < p.y[shoulder] + 0.1f * sw;
        return wristHigh && elbowUp;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    @Override
    public void close() {
        interpreter.close();
    }
}
