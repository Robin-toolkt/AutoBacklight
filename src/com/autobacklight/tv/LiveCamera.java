package com.autobacklight.tv;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the camera streaming continuously: a 720p YUV stream for analysis, plus an optional
 * preview stream (for the spotlight window). Steps aside when another app takes the camera and
 * comes back when it is free again.
 */
final class LiveCamera {
    interface Listener {
        /** Called on the analysis thread; the image is closed after this returns. */
        void onFrame(Image image, Long exposureNs, Integer iso);

        void onCameraStatus(String status);
    }

    private static final long RETRY_MS = 10_000;

    private final CameraManager manager;
    private final Listener listener;
    private final HandlerThread cameraThread = new HandlerThread("LiveCamera");
    private final HandlerThread analysisThread = new HandlerThread("LiveAnalysis");
    private final Handler handler;
    private final Handler analysis;
    private final Runnable openRunnable = this::open;

    private boolean wanted;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size analysisSize = new Size(1280, 720);
    private Size previewSize = new Size(1920, 1080);
    private Range<Integer> fpsRange;
    private SurfaceTexture previewTexture;
    private Surface previewSurface;
    private Runnable pendingDone;
    private volatile Long lastExposure;
    private volatile Integer lastIso;

    private final CameraManager.AvailabilityCallback availability = new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (wanted && device == null) {
                handler.removeCallbacks(openRunnable);
                handler.postDelayed(openRunnable, 1000);
            }
        }
    };

    LiveCamera(Context context, Listener listener) {
        this.manager = context.getSystemService(CameraManager.class);
        this.listener = listener;
        cameraThread.start();
        analysisThread.start();
        handler = new Handler(cameraThread.getLooper());
        analysis = new Handler(analysisThread.getLooper());
        manager.registerAvailabilityCallback(availability, handler);
    }

    void start() {
        handler.post(() -> {
            wanted = true;
            open();
        });
    }

    void stop() {
        handler.post(() -> {
            wanted = false;
            handler.removeCallbacks(openRunnable);
            closeAll();
        });
    }

    void release() {
        stop();
        handler.post(() -> {
            manager.unregisterAvailabilityCallback(availability);
            cameraThread.quitSafely();
            analysisThread.quitSafely();
        });
    }

    /**
     * Adds (texture != null) or removes the preview stream. {@code done} runs on the camera thread
     * once the camera has stopped using the previous preview surface.
     */
    void setPreview(SurfaceTexture texture, Runnable done) {
        handler.post(() -> {
            Surface old = previewSurface;
            previewTexture = texture;
            previewSurface = null;
            if (texture != null) {
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                previewSurface = new Surface(texture);
            }
            pendingDone = () -> {
                if (old != null) old.release();
                if (done != null) done.run();
            };
            if (device != null) startSession();
            else runPending();
        });
    }

    private void open() {
        if (!wanted || device != null) return;
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                listener.onCameraStatus("No camera found");
                retry();
                return;
            }
            String id = ids[0];
            chooseConfig(manager.getCameraCharacteristics(id));
            if (previewTexture != null) {
                previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            }
            reader = ImageReader.newInstance(analysisSize.getWidth(), analysisSize.getHeight(), ImageFormat.YUV_420_888, 3);
            reader.setOnImageAvailableListener(r -> {
                Image img;
                try {
                    img = r.acquireLatestImage();
                } catch (RuntimeException e) {
                    return; // reader closed under us
                }
                if (img == null) return;
                try {
                    listener.onFrame(img, lastExposure, lastIso);
                } catch (Throwable t) {
                    Log.w(BacklightService.TAG, "frame handling failed", t);
                } finally {
                    img.close();
                }
            }, analysis);

            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    device = d;
                    if (!wanted) {
                        closeAll();
                        return;
                    }
                    listener.onCameraStatus("Camera on");
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    device = d;
                    closeAll();
                    listener.onCameraStatus("Paused: another app is using the camera");
                    retry();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    device = d;
                    closeAll();
                    listener.onCameraStatus(error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE
                            ? "Paused: another app is using the camera" : "Camera error " + error);
                    retry();
                }
            }, handler);
        } catch (Exception e) {
            closeAll();
            listener.onCameraStatus("Camera unavailable: " + e.getMessage());
            retry();
        }
    }

    private void retry() {
        if (!wanted) return;
        handler.removeCallbacks(openRunnable);
        handler.postDelayed(openRunnable, RETRY_MS);
    }

    private void chooseConfig(CameraCharacteristics ch) {
        StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] yuv = map.getOutputSizes(ImageFormat.YUV_420_888);
            Size[] tex = map.getOutputSizes(SurfaceTexture.class);
            analysisSize = pick(yuv, 1280, 720, analysisSize);
            previewSize = pick(tex, 1920, 1080, analysisSize);
        }
        Range<Integer>[] ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        fpsRange = null;
        if (ranges != null) {
            for (Range<Integer> r : ranges) {
                if (r.getUpper() > 15) continue;
                if (fpsRange == null || r.getUpper() > fpsRange.getUpper()
                        || (r.getUpper().equals(fpsRange.getUpper()) && r.getLower() < fpsRange.getLower())) {
                    fpsRange = r;
                }
            }
        }
    }

    private static Size pick(Size[] sizes, int w, int h, Size fallback) {
        if (sizes == null) return fallback;
        for (Size s : sizes) if (s.getWidth() == w && s.getHeight() == h) return s;
        return fallback;
    }

    private void startSession() {
        if (device == null) return;
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
        final List<Surface> outputs = new ArrayList<>();
        outputs.add(reader.getSurface());
        final Surface preview = previewSurface;
        if (preview != null) outputs.add(preview);
        try {
            device.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) {
                        s.close();
                        runPending();
                        return;
                    }
                    session = s;
                    try {
                        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        for (Surface o : outputs) b.addTarget(o);
                        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                        s.setRepeatingRequest(b.build(), new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession cs, CaptureRequest r, TotalCaptureResult res) {
                                lastExposure = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                                lastIso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                            }
                        }, handler);
                    } catch (Exception e) {
                        listener.onCameraStatus("Camera stream failed: " + e.getMessage());
                    }
                    runPending();
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    // Some USB cameras can't scale to two sizes at once: retry with the preview at the analysis size.
                    if (preview != null && preview == previewSurface && !previewSize.equals(analysisSize)) {
                        Log.w(BacklightService.TAG, "two-size session failed, retrying preview at " + analysisSize);
                        previewSize = analysisSize;
                        previewTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        startSession();
                        return;
                    }
                    listener.onCameraStatus("Camera could not start streaming");
                    runPending();
                }
            }, handler);
        } catch (Exception e) {
            listener.onCameraStatus("Camera session error: " + e.getMessage());
            runPending();
        }
    }

    private void runPending() {
        Runnable r = pendingDone;
        pendingDone = null;
        if (r != null) r.run();
    }

    private void closeAll() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        session = null;
        device = null;
        reader = null;
        runPending();
    }
}
