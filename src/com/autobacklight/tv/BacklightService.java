package com.autobacklight.tv;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Measures how bright the room is with the TV camera and maps that to the TV's backlight setting
 * (Settings.Global "picture_backlight", 0-100). No image is stored.
 *
 * Two modes: with the hand-raise spotlight on, the camera streams continuously (LiveCamera) and
 * both features share it; with only brightness on, the camera is opened briefly every few seconds.
 */
public class BacklightService extends Service implements LiveCamera.Listener {
    static final String TAG = "AdaptiveBrightness";
    static final String SETTING = "picture_backlight";

    private static final String MODE_MANUAL = "manual";
    private static final String MODE_AUTO = "auto";
    private static final long CAPTURE_TIMEOUT_MS = 8000;

    // Shared with MainActivity for display (same process).
    static volatile boolean running;
    static volatile String status = "Stopped";
    static volatile String modeLabel = "-";
    static volatile float lastLogLight = Float.NaN;
    static volatile int lastLuma = -1;
    static volatile int lastTarget = -1;
    static volatile String gestureStatus = "Off";

    private static final long IDLE_DETECT_MS = 250;      // ~4 checks/s while waiting for a raised hand
    private static final long SPOTLIGHT_DETECT_MS = 125; // ~8/s while following someone

    private final Handler main = new Handler(Looper.getMainLooper());
    private LiveCamera live;
    private SpotlightController spotlight;
    private PoseDetector detector;
    private boolean detectorFailed;
    private boolean liveMode;
    private boolean screenOn = true;
    private long lastDetect, lastLiveBrightness, fpsWindowStart;
    private int fpsFrames;
    private float detectFps;
    private final int[] liveLumas = new int[15];
    private int liveLumaCount;
    private long lastLumaSample;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOn = Intent.ACTION_SCREEN_ON.equals(i.getAction());
            configure();
        }
    };

    private HandlerThread thread;
    private Handler handler;
    private CameraManager cameraManager;
    private SharedPreferences prefs;

    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Capture capture;

    private float smoothed = Float.NaN;
    private int probeAttempts;

    // Manual-exposure state, auto-ranged between samples.
    private long expMin = 100_000L, expMax = 33_000_000L, exposureNs;
    private int isoMin = 100, isoMax = 800, iso;

    /** One measurement in progress. A probe has two phases with different manual exposures. */
    private static final class Capture {
        String mode;          // MODE_MANUAL, MODE_AUTO, or null when probing
        int phase;
        int frames;
        long phaseStart = System.currentTimeMillis();
        final int[] recent = new int[5]; // last few luma values, for a median
        boolean finished;
        long exp; int iso;    // manual settings of the current phase
        int lumaA = -1, lumaB = -1;
        long expA, expB; int isoA, isoB;
        Long resultExp; Integer resultIso; Integer aeState;
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, BacklightService.class));
    }

    /** Re-reads which features are on; starts the service if needed, and it stops itself if nothing is on. */
    static void reconfigure(Context c) {
        c.startForegroundService(new Intent(c, BacklightService.class).setAction("reconfigure"));
    }

    static boolean anythingEnabled(SharedPreferences p) {
        return p.getBoolean(Prefs.ENABLED, false) || p.getBoolean(Prefs.GESTURES, false);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = Prefs.get(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("autobacklight", "Adaptive Brightness",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "autobacklight")
                .setContentTitle("Adaptive Brightness is running")
                .setContentText("Room-light brightness and hand-raise spotlight")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
            } else {
                startForeground(1, n);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Cannot start camera foreground service", e);
            status = "Cannot start: " + e.getMessage();
            stopSelf();
            return;
        }
        thread = new HandlerThread("Adaptive Brightness");
        thread.start();
        handler = new Handler(thread.getLooper());
        cameraManager = getSystemService(CameraManager.class);
        live = new LiveCamera(this, this);
        spotlight = new SpotlightController(this, live);
        screenOn = getSystemService(PowerManager.class).isInteractive();
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
        running = true;
        status = "Starting";
        configure();
    }

    /** Main thread. Chooses continuous (gestures) or periodic (brightness only) camera use. */
    private void configure() {
        if (!running) return;
        if (!anythingEnabled(prefs)) {
            stopSelf();
            return;
        }
        boolean gestures = prefs.getBoolean(Prefs.GESTURES, false);
        boolean brightness = prefs.getBoolean(Prefs.ENABLED, false);
        boolean wantLive = gestures && screenOn;

        if (wantLive && !liveMode) {
            liveMode = true;
            handler.post(() -> {           // stop periodic sampling, release the camera for the live stream
                handler.removeCallbacksAndMessages(null);
                if (capture != null) capture.finished = true;
                closeCamera();
                live.start();
            });
            gestureStatus = "Starting camera";
        } else if (!wantLive && liveMode) {
            liveMode = false;
            spotlight.hide();
            live.stop();
            gestureStatus = gestures ? "Paused (TV in standby)" : "Off";
        } else if (!gestures) {
            gestureStatus = "Off";
        }
        if (!liveMode) {
            handler.post(() -> {
                handler.removeCallbacksAndMessages(null);
                if (capture != null) capture.finished = true;
                closeCamera();
                if (brightness && screenOn) handler.postDelayed(this::sample, 1500);
                else status = brightness ? "Paused (TV in standby)" : "Brightness off";
            });
        } else if (!brightness) {
            status = "Brightness off";
        }
    }

    // ---------------------------------------------------------------- live camera (gestures on)

    @Override
    public void onCameraStatus(String s) {
        gestureStatus = s;
        if (s.startsWith("Paused") || s.startsWith("Camera error") || s.startsWith("Camera unavailable")) {
            main.post(spotlight::hide);
        }
    }

    @Override
    public void onFrame(Image img, Long exposureNs, Integer iso) {
        long now = SystemClock.uptimeMillis();
        if (prefs.getBoolean(Prefs.ENABLED, false) && now - lastLumaSample >= 500) {
            lastLumaSample = now;
            liveLumas[liveLumaCount++ % liveLumas.length] = meanLuma(img);
            if (now - lastLiveBrightness >= intervalMs()) {
                lastLiveBrightness = now;
                applyLiveBrightness(exposureNs, iso);
            }
        }

        if (!prefs.getBoolean(Prefs.GESTURES, false)) return;
        long minGap = SpotlightController.active ? SPOTLIGHT_DETECT_MS : IDLE_DETECT_MS;
        if (now - lastDetect < minGap) return;
        lastDetect = now;
        if (detector == null) {
            if (detectorFailed) return;
            try {
                detector = new PoseDetector(this);
            } catch (Throwable t) {
                detectorFailed = true;
                gestureStatus = "Body-tracking model failed to load: " + t.getMessage();
                Log.e(TAG, "model load failed", t);
                return;
            }
        }
        final List<PoseDetector.Person> people = detector.detect(img);
        final float aspect = (float) img.getWidth() / img.getHeight();
        main.post(() -> {
            if (liveMode) spotlight.onDetections(people, aspect);
        });

        fpsFrames++;
        if (now - fpsWindowStart >= 5000) {
            detectFps = fpsFrames * 1000f / Math.max(1, now - fpsWindowStart);
            fpsWindowStart = now;
            fpsFrames = 0;
        }
        gestureStatus = SpotlightController.active
                ? String.format(Locale.US, "Spotlight on (%.1f checks/s)", detectFps)
                : String.format(Locale.US, "Watching for a raised hand: %d %s in view (%.1f checks/s)",
                people.size(), people.size() == 1 ? "person" : "people", detectFps);
    }

    private void applyLiveBrightness(Long exposureNs, Integer iso) {
        int n = Math.min(liveLumaCount, liveLumas.length);
        if (n == 0) return;
        int luma = median(liveLumas, n);
        liveLumaCount = 0;
        lastLuma = luma;
        double light;
        if (exposureNs != null && iso != null && exposureNs > 0 && iso > 0) {
            light = Math.max(luma, 0.5) / ((exposureNs / 1e9) * (iso / 100.0));
            modeLabel = String.format(Locale.US, "Live camera (%.2f ms, ISO %d)", exposureNs / 1e6, iso);
        } else {
            light = Math.max(luma, 0.5);
            modeLabel = "Live camera (shared with spotlight)";
        }
        applyLight((float) Math.log10(light));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "reconfigure".equals(intent.getAction())) {
            configure();
        }
        if (handler != null && !liveMode && intent != null && intent.getBooleanExtra("resample", false)) {
            handler.post(() -> {
                handler.removeCallbacksAndMessages(null);
                if (capture != null) capture.finished = true;
                closeCamera();
                probeAttempts = 0;
                sample();
            });
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        running = false;
        status = "Stopped";
        gestureStatus = "Off";
        if (wasRunning) {
            unregisterReceiver(screenReceiver);
            spotlight.hide();
            live.release();
            final PoseDetector d = detector;
            detector = null;
            if (d != null) main.postDelayed(d::close, 2000); // after the analysis thread has stopped
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler.post(this::closeCamera);
            thread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- sampling

    private void scheduleNext(long delayMs) {
        if (running && !liveMode) handler.postDelayed(this::sample, delayMs);
    }

    private long intervalMs() {
        return Math.max(2, prefs.getInt(Prefs.INTERVAL, Prefs.DEFAULT_INTERVAL)) * 1000L;
    }

    private void sample() {
        if (!running || liveMode || !screenOn) return;
        if (!prefs.getBoolean(Prefs.ENABLED, false)) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            status = "Camera permission missing";
            scheduleNext(intervalMs());
            return;
        }
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length == 0) {
                status = "No camera found (is it plugged in?)";
                scheduleNext(intervalMs());
                return;
            }
            String id = ids[0];
            CameraCharacteristics ch = cameraManager.getCameraCharacteristics(id);
            readRanges(ch);

            Capture c = new Capture();
            c.mode = prefs.getString(Prefs.MEASURE_MODE, null);
            capture = c;
            if (MODE_MANUAL.equals(c.mode) || c.mode == null) setPhaseExposure(c);

            Size size = pickSize(ch);
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> onFrame(c, r), handler);
            handler.postDelayed(() -> {
                if (!c.finished) fail(c, "Camera did not answer (timeout)");
            }, CAPTURE_TIMEOUT_MS);

            cameraManager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    camera = d;
                    if (c.finished) { closeCamera(); return; }
                    try {
                        d.createCaptureSession(Collections.singletonList(reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession s) {
                                        session = s;
                                        if (c.finished) { closeCamera(); return; }
                                        try {
                                            repeat(c);
                                        } catch (Exception e) {
                                            fail(c, "Capture failed: " + e.getMessage());
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession s) {
                                        fail(c, "Camera session failed");
                                    }
                                }, handler);
                    } catch (Exception e) {
                        fail(c, "Camera session error: " + e.getMessage());
                    }
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    camera = d;
                    fail(c, "Camera disconnected");
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    camera = d;
                    fail(c, error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                            ? "Camera busy (used by another app)" : "Camera error " + error);
                }
            }, handler);
        } catch (Exception e) {
            Log.w(TAG, "sample failed", e);
            fail(capture, "Camera unavailable: " + e.getMessage());
        }
    }

    private void readRanges(CameraCharacteristics ch) {
        Range<Long> er = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> sr = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (er != null) { expMin = Math.max(1, er.getLower()); expMax = Math.max(expMin, er.getUpper()); }
        if (sr != null) { isoMin = Math.max(1, sr.getLower()); isoMax = Math.max(isoMin, sr.getUpper()); }
        if (exposureNs == 0) { exposureNs = expMax; iso = isoMin; }
        exposureNs = clamp(exposureNs, expMin, expMax);
        iso = (int) clamp(iso, isoMin, isoMax);
    }

    /** Exposure for the current phase: probe phase 0 = low gain, phase 1 = high gain; manual = auto-ranged. */
    private void setPhaseExposure(Capture c) {
        if (c.mode == null) {
            if (c.phase == 0) {
                c.exp = Math.max(expMin, expMax / 8);
                c.iso = isoMin;
            } else {
                c.exp = expMax;
                c.iso = (int) Math.min(isoMax, isoMin * 4L);
            }
        } else {
            c.exp = exposureNs;
            c.iso = iso;
        }
    }

    private void repeat(Capture c) throws CameraAccessException {
        CaptureRequest.Builder b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(reader.getSurface());
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (MODE_AUTO.equals(c.mode)) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, c.exp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, c.iso);
        }
        session.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                c.resultExp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                c.resultIso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                c.aeState = res.get(CaptureResult.CONTROL_AE_STATE);
            }
        }, handler);
    }

    private void onFrame(Capture c, ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        int luma;
        try {
            luma = meanLuma(img);
        } finally {
            img.close();
        }
        if (c.finished) return;
        c.recent[c.frames % c.recent.length] = luma;
        c.frames++;

        // USB cameras run their own exposure control and need a moment after opening or after a
        // settings change; wait for that, then use the median of the last few frames.
        long elapsed = System.currentTimeMillis() - c.phaseStart;
        boolean settled;
        if (MODE_AUTO.equals(c.mode)) {
            boolean converged = c.aeState != null && (c.aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    || c.aeState == CaptureResult.CONTROL_AE_STATE_LOCKED);
            settled = (elapsed >= 2500 && c.frames >= 10) || (c.frames >= 10 && converged);
        } else {
            settled = elapsed >= 1200 && c.frames >= 6;
        }
        if (!settled) return;
        luma = median(c.recent, Math.min(c.frames, c.recent.length));

        if (c.mode == null && c.phase == 0) {
            c.lumaA = luma; c.expA = c.exp; c.isoA = c.iso;
            c.phase = 1;
            c.frames = 0;
            c.phaseStart = System.currentTimeMillis();
            setPhaseExposure(c);
            try {
                repeat(c);
            } catch (Exception e) {
                fail(c, "Capture failed: " + e.getMessage());
            }
            return;
        }
        if (c.mode == null) {
            c.lumaB = luma; c.expB = c.exp; c.isoB = c.iso;
        }
        c.finished = true;
        closeCamera();
        finishCapture(c, luma);
    }

    private void finishCapture(Capture c, int luma) {
        lastLuma = luma;
        if (c.mode == null) {
            finishProbe(c);
            return;
        }

        double light;
        if (MODE_MANUAL.equals(c.mode)) {
            // Clipped frames don't tell us the real level: re-range exposure and measure again soon.
            boolean clipped = (luma > 245 && gain() > (double) expMin * isoMin) || (luma < 3 && gain() < (double) expMax * isoMax);
            rangeExposure(luma);
            if (clipped) {
                status = "Adjusting camera exposure";
                scheduleNext(1000);
                return;
            }
            light = Math.max(luma, 0.5) / ((c.exp / 1e9) * (c.iso / 100.0));
            modeLabel = String.format(Locale.US, "Manual exposure (%.2f ms, ISO %d)", c.exp / 1e6, c.iso);
        } else if (c.resultExp != null && c.resultIso != null && c.resultExp > 0 && c.resultIso > 0) {
            light = Math.max(luma, 0.5) / ((c.resultExp / 1e9) * (c.resultIso / 100.0));
            modeLabel = String.format(Locale.US, "Auto exposure (%.2f ms, ISO %d)", c.resultExp / 1e6, c.resultIso);
        } else {
            light = Math.max(luma, 0.5);
            modeLabel = "Auto exposure (no exposure data)";
        }
        applyLight((float) Math.log10(light));
        scheduleNext(intervalMs());
    }

    /** Decide whether the camera obeys manual exposure by comparing a low-gain and a high-gain frame. */
    private void finishProbe(Capture c) {
        probeAttempts++;
        double gainRatio = ((double) c.expB * c.isoB) / ((double) c.expA * c.isoA);
        Log.i(TAG, "probe lumaA=" + c.lumaA + " lumaB=" + c.lumaB + " gainRatio=" + gainRatio);
        boolean works = c.lumaB >= c.lumaA * 1.6 && c.lumaB - c.lumaA >= 8;
        if (works) {
            prefs.edit().putString(Prefs.MEASURE_MODE, MODE_MANUAL).apply();
            exposureNs = c.expB;
            iso = c.isoB;
            status = "Camera supports manual exposure";
        } else if (c.lumaB < 8 && probeAttempts < 3) {
            status = "Room too dark to test the camera, retrying";
            scheduleNext(intervalMs());
            return;
        } else {
            prefs.edit().putString(Prefs.MEASURE_MODE, MODE_AUTO).apply();
            status = "Camera uses automatic exposure";
        }
        scheduleNext(500);
    }

    private double gain() {
        return (double) exposureNs * iso;
    }

    /** Keep the camera's mid-tones near 110/255: move exposure time first, then ISO. */
    private void rangeExposure(int luma) {
        double g = gain();
        if (luma > 200) g /= luma > 245 ? 4 : 2;
        else if (luma < 40) g *= luma < 5 ? 8 : 2.5;
        else return;
        g = Math.max((double) expMin * isoMin, Math.min((double) expMax * isoMax, g));
        long e = (long) Math.min(expMax, Math.max(expMin, g / isoMin));
        int s = (int) Math.min(isoMax, Math.max(isoMin, Math.ceil(g / e)));
        exposureNs = e;
        iso = s;
    }

    // ---------------------------------------------------------------- brightness

    private void applyLight(float logL) {
        lastLogLight = logL;

        float autoMin = prefs.getFloat(Prefs.AUTO_MIN, logL);
        float autoMax = prefs.getFloat(Prefs.AUTO_MAX, logL);
        if (logL < autoMin || logL > autoMax || !prefs.contains(Prefs.AUTO_MIN)) {
            autoMin = Math.min(autoMin, logL);
            autoMax = Math.max(autoMax, logL);
            prefs.edit().putFloat(Prefs.AUTO_MIN, autoMin).putFloat(Prefs.AUTO_MAX, autoMax).apply();
        }
        float dark = prefs.getFloat(Prefs.CAL_DARK, autoMin);
        float bright = prefs.getFloat(Prefs.CAL_BRIGHT, autoMax);
        if (bright - dark < 1f) { // need at least a 10x light range to map onto
            float mid = (dark + bright) / 2f;
            dark = mid - 0.5f;
            bright = mid + 0.5f;
        }

        int minBl = prefs.getInt(Prefs.MIN_BL, Prefs.DEFAULT_MIN_BL);
        int maxBl = prefs.getInt(Prefs.MAX_BL, Prefs.DEFAULT_MAX_BL);
        float t = Math.max(0f, Math.min(1f, (logL - dark) / (bright - dark)));
        float target = minBl + t * (maxBl - minBl);
        smoothed = Float.isNaN(smoothed) ? target : smoothed + 0.4f * (target - smoothed);
        int value = Math.round(smoothed);
        lastTarget = value;

        int current = Settings.Global.getInt(getContentResolver(), SETTING, -1);
        boolean atEnd = (value == minBl || value == maxBl) && value != current;
        if (current < 0 || Math.abs(value - current) >= 2 || atEnd) {
            try {
                Settings.Global.putInt(getContentResolver(), SETTING, value);
                status = "Brightness set to " + value;
            } catch (SecurityException e) {
                status = "Missing WRITE_SECURE_SETTINGS (grant it with adb)";
                return;
            }
        } else {
            status = "Brightness steady at " + current;
        }
        Log.i(TAG, String.format(Locale.US, "luma=%d log=%.2f range=[%.2f..%.2f] target=%d current=%d",
                lastLuma, logL, dark, bright, value, current));
    }

    // ---------------------------------------------------------------- helpers

    private void fail(Capture c, String message) {
        if (c != null) {
            if (c.finished) return;
            c.finished = true;
        }
        Log.w(TAG, message);
        status = message;
        closeCamera();
        scheduleNext(intervalMs());
    }

    private void closeCamera() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (camera != null) camera.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null;
        camera = null;
        reader = null;
    }

    private static Size pickSize(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map != null ? map.getOutputSizes(ImageFormat.YUV_420_888) : null;
        if (sizes == null || sizes.length == 0) return new Size(320, 240);
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() < 160) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() < (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private static int meanLuma(Image img) {
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer buf = y.getBuffer();
        int rowStride = y.getRowStride();
        int pixelStride = y.getPixelStride();
        int w = img.getWidth(), h = img.getHeight();
        long sum = 0;
        int n = 0;
        for (int row = 0; row < h; row += 4) {
            for (int col = 0; col < w; col += 4) {
                int idx = row * rowStride + col * pixelStride;
                if (idx >= buf.limit()) continue;
                sum += buf.get(idx) & 0xff;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    private static int median(int[] values, int n) {
        int[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }

    private static long clamp(long v, long lo, long hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
