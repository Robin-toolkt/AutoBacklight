package com.autobacklight.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks people between frames, notices a hand held up, and shows a corner window that zooms in
 * on that person and follows them. All methods run on the main thread.
 */
final class SpotlightController {
    private static final long RAISE_HOLD_MS = 800;       // hand must stay up this long to trigger
    private static final long RAISE_GAP_MS = 400;        // brief detection misses that don't reset the hold
    private static final long LOWERED_CLOSE_MS = 10_000; // close this long after the hand comes down
    private static final long LOST_CLOSE_MS = 3_000;     // close this long after the person leaves the frame
    private static final long TRACK_FORGET_MS = 1_500;
    private static final float MATCH_DISTANCE = 0.3f;    // in frame heights
    private static final boolean MIRROR = true;          // show the camera like a mirror

    static volatile boolean active;

    private static final class Track {
        final int id;
        final RectF box = new RectF();
        float cx, cy;
        long lastSeen, raisedStart = -1, lastRaised = -1;

        Track(int id) {
            this.id = id;
        }
    }

    private final Context context;
    private final LiveCamera camera;
    private final WindowManager windowManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Track> tracks = new ArrayList<>();
    private int nextId = 1;
    private Track target;
    private long shownAt;

    private View overlay;
    private TextureView texture;
    private final RectF targetCrop = new RectF(0, 0, 1, 1);
    private final RectF shownCrop = new RectF(0, 0, 1, 1);
    private final Matrix matrix = new Matrix();
    private boolean animating;

    private final Choreographer.FrameCallback animator = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating || texture == null) return;
            float k = 0.12f; // ease toward the target crop every display frame
            shownCrop.left += (targetCrop.left - shownCrop.left) * k;
            shownCrop.top += (targetCrop.top - shownCrop.top) * k;
            shownCrop.right += (targetCrop.right - shownCrop.right) * k;
            shownCrop.bottom += (targetCrop.bottom - shownCrop.bottom) * k;
            applyCrop();
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    SpotlightController(Context context, LiveCamera camera) {
        this.context = context;
        this.camera = camera;
        this.windowManager = context.getSystemService(WindowManager.class);
    }

    int peopleCount() {
        int n = 0;
        long now = SystemClock.uptimeMillis();
        for (Track t : tracks) if (now - t.lastSeen < 500) n++;
        return n;
    }

    void onDetections(List<PoseDetector.Person> people, float aspect) {
        long now = SystemClock.uptimeMillis();
        List<Track> unmatched = new ArrayList<>(tracks);
        for (PoseDetector.Person p : people) {
            RectF box = boxOf(p);
            float cx = box.centerX(), cy = box.centerY();
            Track best = null;
            float bestDist = MATCH_DISTANCE;
            for (Track t : unmatched) {
                float d = (float) Math.hypot((t.cx - cx) * aspect, t.cy - cy);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
            if (best == null) {
                best = new Track(nextId++);
                tracks.add(best);
            } else {
                unmatched.remove(best);
            }
            best.box.set(box);
            best.cx = cx;
            best.cy = cy;
            best.lastSeen = now;
            if (PoseDetector.handRaised(p, aspect)) {
                if (best.raisedStart < 0) best.raisedStart = now;
                best.lastRaised = now;
            } else if (best.raisedStart >= 0 && now - best.lastRaised > RAISE_GAP_MS) {
                best.raisedStart = -1;
            }
        }

        for (Track t : tracks) {
            if (t.raisedStart >= 0 && now - t.raisedStart >= RAISE_HOLD_MS && t != target) {
                target = t;
                shownAt = now;
                show();
                break;
            }
        }
        if (target != null) {
            if (now - target.lastSeen > LOST_CLOSE_MS
                    || now - Math.max(target.lastRaised, shownAt) > LOWERED_CLOSE_MS) {
                hide();
            } else {
                updateCrop(target.box);
            }
        }
        for (int i = tracks.size() - 1; i >= 0; i--) {
            Track t = tracks.get(i);
            if (t != target && now - t.lastSeen > TRACK_FORGET_MS) tracks.remove(i);
        }
    }

    /** Person's box, widened to include any keypoints outside it (e.g. a raised hand). */
    private static RectF boxOf(PoseDetector.Person p) {
        RectF r = new RectF(p.left, p.top, p.right, p.bottom);
        for (int k = 0; k < 17; k++) {
            if (p.s[k] < 0.3f) continue;
            r.union(p.x[k], p.y[k]);
        }
        r.intersect(0, 0, 1, 1);
        return r;
    }

    /** Crop with the frame's own aspect ratio, so width and height share one normalized size. */
    private void updateCrop(RectF box) {
        float size = Math.max(box.height() * 1.5f, box.width() * 1.4f);
        size = Math.max(0.35f, Math.min(1f, size));
        float left = clamp(box.centerX() - size / 2, 0, 1 - size);
        float top = clamp(box.centerY() - size / 2, 0, 1 - size);
        targetCrop.set(left, top, left + size, top + size);
    }

    private void applyCrop() {
        int vw = texture.getWidth(), vh = texture.getHeight();
        if (vw == 0 || vh == 0) return;
        float s = 1f / Math.max(0.05f, shownCrop.width());
        matrix.setScale(s, s);
        matrix.postTranslate(-shownCrop.left * vw * s, -shownCrop.top * vh * s);
        if (MIRROR) matrix.postScale(-1, 1, vw / 2f, vh / 2f);
        texture.setTransform(matrix);
    }

    private void show() {
        active = true;
        if (overlay != null) return; // already showing; it now follows the new person
        if (!Settings.canDrawOverlays(context)) {
            BacklightService.gestureStatus = "Cannot show spotlight: overlay permission missing";
            return;
        }
        int border = dp(3);
        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Color.rgb(0xFF, 0xC8, 0x3D));
        frame.setPadding(border, border, border, border);

        texture = new TextureView(context);
        frame.addView(texture, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(context);
        label.setText("✋ Spotlight");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setBackgroundColor(0x99000000);
        label.setPadding(dp(8), dp(4), dp(8), dp(4));
        frame.addView(label, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                camera.setPreview(st, null);
                shownCrop.set(0, 0, 1, 1); // start wide, then glide in on the person
                animating = true;
                Choreographer.getInstance().postFrameCallback(animator);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                return false; // released in hide(), after the camera stops drawing into it
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(480) + 2 * border, dp(270) + 2 * border,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.x = dp(40);
        lp.y = dp(40);
        try {
            windowManager.addView(frame, lp);
            overlay = frame;
        } catch (RuntimeException e) {
            BacklightService.gestureStatus = "Cannot show spotlight: " + e.getMessage();
            texture = null;
            active = false;
        }
    }

    void hide() {
        target = null;
        active = false;
        if (overlay == null) return;
        final View view = overlay;
        final TextureView tv = texture;
        overlay = null;
        texture = null;
        animating = false;
        final SurfaceTexture st = tv.getSurfaceTexture();
        camera.setPreview(null, () -> main.post(() -> {
            try {
                windowManager.removeView(view);
            } catch (RuntimeException ignored) {
            }
            if (st != null) st.release();
        }));
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
