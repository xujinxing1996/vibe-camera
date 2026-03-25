package com.psi.uniplugin_vibe_camera; // 保持你的包名一致

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraControl;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExposureState;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.alibaba.fastjson.JSONObject;
import com.google.common.util.concurrent.ListenableFuture;
import com.psi.uniplugin_vibe_camera.utils.ImagePreprocessor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.dcloud.feature.uniapp.UniSDKInstance;
import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.ui.action.AbsComponentData;
import io.dcloud.feature.uniapp.ui.component.AbsVContainer;
import io.dcloud.feature.uniapp.ui.component.UniComponent;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;

public class UniCameraView extends UniComponent<PreviewView> {

    private static final String TAG = "UniCameraView";
    private static final int PERMISSION_REQ_CODE = 2024; // 权限请求码

    private PreviewView mPreviewView;
    private ProcessCameraProvider cameraProvider;
    private ImageCapture imageCapture;
    private Camera mCamera; // 持有相机对象，用于后续控制缩放和曝光
    private ExecutorService cameraExecutor;
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private int lensFacing = CameraSelector.LENS_FACING_BACK;
    private final AtomicBoolean isCameraBound = new AtomicBoolean(false);

    public UniCameraView(UniSDKInstance instance, AbsVContainer parent, AbsComponentData basicComponentData) {
        super(instance, parent, basicComponentData);
        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected PreviewView initComponentHostView(Context context) {
        mPreviewView = new PreviewView(context);
        // FILL_CENTER: 充满全屏，适合 OCR 取景
        mPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        // 初始化时直接检查并申请权限
        checkAndRequestPermission();
        return mPreviewView;
    }

    /**
     * 使用 Android 标准 API 检查并申请权限
     */
    private void checkAndRequestPermission() {
        if (mUniSDKInstance.getContext() instanceof Activity) {
            Activity activity = (Activity) mUniSDKInstance.getContext();

            // 1. 检查权限
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                // 已有权限，直接启动相机
                mPreviewView.post(this::startCamera);
            } else {
                // 2. 没有权限，直接使用标准 ActivityCompat 申请
                // UniComponent 能够自动通过 mUniSDKInstance 监听回调结果
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA},
                        PERMISSION_REQ_CODE);
            }
        }
    }

    /**
     * 2. 监听权限申请结果
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 用户点击允许，启动相机
                startCamera();
            } else {
                // 用户点击拒绝
                fireEvent("onError", createErrorMap("Permission Denied: Camera access is required."));
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * 3. 启动相机 (核心逻辑优化)
     */
    private void startCamera() {
        Context context = mUniSDKInstance.getContext();

        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return; // 双重保险
        }

        if (isCameraBound.get()) return;

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(context);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // 屏幕比例计算
                int screenAspectRatio = aspectRatio(mPreviewView.getWidth(), mPreviewView.getHeight());

                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(new AspectRatioStrategy(screenAspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO))
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build();

                Preview preview = new Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build();
                preview.setSurfaceProvider(mPreviewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY) // 画质优先
                        .setJpegQuality(85)
                        .build();

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(lensFacing)
                        .build();

                cameraProvider.unbindAll();

                // 绑定并获取 Camera 对象
                mCamera = cameraProvider.bindToLifecycle(
                        (LifecycleOwner) context,
                        cameraSelector,
                        preview,
                        imageCapture);

                isCameraBound.set(true);

                // 【关键优化】针对白布反光：默认降低曝光补偿
                // 大多数手机 index 范围是 -2 到 +2，-2 表示最暗
                // 这样能让白色布料变灰，黑色编码对比度急剧提升
                setExposureInternal(-2);

            } catch (Exception e) {
                Log.e(TAG, "Use case binding failed", e);
                isCameraBound.set(false);
                fireEvent("onError", createErrorMap("Init Failed: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(context));
    }

    @UniJSMethod
    public void takePhotoWithCrop(JSONObject options, UniJSCallback callback) {
        if (imageCapture == null) {
            invokeCallback(callback, false, "相机未初始化", null);
            return;
        }

        final int sx         = options.getIntValue("sx");
        final int sy         = options.getIntValue("sy");
        final int sWidth     = options.getIntValue("sWidth");
        final int sHeight    = options.getIntValue("sHeight");
        final String uploadUrl  = options.getString("uploadUrl");
        final int screenWidth  = options.getIntValue("screenWidth");  // ← 新增
        final int screenHeight = options.getIntValue("screenHeight"); // ← 新增

        File cacheDir = mUniSDKInstance.getContext().getCacheDir();
        File rawFile  = new File(cacheDir, "raw_"  + System.currentTimeMillis() + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(rawFile).build();

        imageCapture.takePicture(
                outputOptions,
                cameraExecutor,  // ← 关键修改：换成 cameraExecutor，不占主线程
                new ImageCapture.OnImageSavedCallback() {

                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                        try {
                            // ── 读取 EXIF 旋转角度 ──────────────────────────────
                            int rotation = 0;
                            try {
                                androidx.exifinterface.media.ExifInterface exif =
                                        new androidx.exifinterface.media.ExifInterface(rawFile.getAbsolutePath());
                                int orientation = exif.getAttributeInt(
                                        androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                                        androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
                                switch (orientation) {
                                    case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:
                                        rotation = 90; break;
                                    case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180:
                                        rotation = 180; break;
                                    case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270:
                                        rotation = 270; break;
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "EXIF read failed, assume 0°", e);
                            }

                            // ── 降采样解码 ──────────────────────────────────────
                            BitmapFactory.Options opts = new BitmapFactory.Options();
                            opts.inPreferredConfig  = Bitmap.Config.RGB_565;
                            opts.inSampleSize       = calcInSampleSize(rawFile, 1200);
                            Bitmap raw = BitmapFactory.decodeFile(rawFile.getAbsolutePath(), opts);
                            rawFile.delete();

                            if (raw == null) {
                                invokeCallback(callback, false, "图片解码失败", null);
                                return;
                            }

                            // ── 旋转矫正，让图片方向与屏幕一致 ────────────────
                            Bitmap upright = raw;
                            if (rotation != 0) {
                                android.graphics.Matrix matrix = new android.graphics.Matrix();
                                matrix.postRotate(rotation);
                                upright = Bitmap.createBitmap(raw, 0, 0, raw.getWidth(), raw.getHeight(), matrix, false);
                                raw.recycle();
                            }

                            // ── 此时 upright 的宽高与屏幕预览方向一致 ──────────
                            // JS 传来的 sx/sy/sWidth/sHeight 按屏幕坐标算，
                            // 但图片分辨率比屏幕高，需要按采样比缩放坐标
                            int imgW = upright.getWidth();
                            int imgH = upright.getHeight();

                            float scaleX = (float) imgW / screenWidth;
                            float scaleY = scaleX; // 统一用宽度 scale

                            float visibleImgH = screenHeight * scaleX;          // 预览区实际对应的图片高度
                            float syOffset    = (imgH - visibleImgH) / 2.0f;   // 图片顶部被裁掉的像素数

                            float visibleImgW = screenWidth * scaleX;
                            float sxOffset    = (imgW - visibleImgW) / 2.0f;   // 通常为 0

                            int scaledSx = Math.round(sx     * scaleX + sxOffset);
                            int scaledSy = Math.round(sy     * scaleY + syOffset); // ← 关键：加上顶部偏移
                            int scaledW  = Math.round(sWidth * scaleX);
                            int scaledH  = Math.round(sHeight * scaleY);

                            Log.d(TAG, String.format(
                                    "visibleImgH=%.1f syOffset=%.1f sxOffset=%.1f",
                                    visibleImgH, syOffset, sxOffset
                            ));

                            int safeX = Math.max(0, Math.min(scaledSx, imgW - 1));
                            int safeY = Math.max(0, Math.min(scaledSy, imgH - 1));
                            int safeW = Math.min(scaledW, imgW - safeX);
                            int safeH = Math.min(scaledH, imgH - safeY);

                            if (safeW <= 0 || safeH <= 0) {
                                upright.recycle();
                                invokeCallback(callback, false,
                                        "裁剪区域无效: " + safeW + "x" + safeH
                                                + " 图片尺寸: " + imgW + "x" + imgH, null);
                                return;
                            }

                            Log.d(TAG, String.format(
                                    "img=%dx%d screen=%dx%d scale=%.4f,%.4f " +
                                            "input(sx=%d,sy=%d,sw=%d,sh=%d) " +
                                            "scaled(sx=%d,sy=%d,sw=%d,sh=%d) " +
                                            "safe(sx=%d,sy=%d,sw=%d,sh=%d)",
                                    imgW, imgH,
                                    screenWidth, screenHeight,
                                    scaleX, scaleY,
                                    sx, sy, sWidth, sHeight,
                                    scaledSx, scaledSy, scaledW, scaledH,
                                    safeX, safeY, safeW, safeH
                            ));

                            Bitmap cropped  = Bitmap.createBitmap(upright, safeX, safeY, safeW, safeH);
                            upright.recycle();

                            File procFile = new File(cacheDir, "proc_" + System.currentTimeMillis() + ".jpg");
                            boolean saved = ImagePreprocessor.compressAndSave(cropped, procFile.getAbsolutePath(), 80);
                            cropped.recycle();

                            if (!saved) {
                                invokeCallback(callback, false, "保存失败", null);
                                return;
                            }

                            uploadAndReturn(procFile, uploadUrl, callback);

                        } catch (OutOfMemoryError oom) {
                            rawFile.delete();
                            invokeCallback(callback, false, "内存不足，请重试", null);
                        } catch (Exception e) {
                            Log.e(TAG, "图像处理失败", e);
                            rawFile.delete();
                            invokeCallback(callback, false, "图像处理失败: " + e.getMessage(), null);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException e) {
                        rawFile.delete();
                        invokeCallback(callback, false, "拍照失败: " + e.getMessage(), null);
                    }
                }
        );
    }

    /**
     * 计算 inSampleSize，把图片最长边限制在 maxDimension 以内
     * 减少后续 Bitmap 操作的内存占用，避免 GC 卡顿
     */
    private int calcInSampleSize(File file, int maxDimension) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        int maxEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (maxEdge / (sample * 2) >= maxDimension) {
            sample *= 2;
        }
        return sample;
    }

    @UniJSMethod
    public void cropAndSave(JSONObject options, UniJSCallback callback) {
        if (callback == null) return;

        final String imagePath = options.getString("imagePath").replace("file://", "");
        final int sx      = options.getIntValue("sx");
        final int sy      = options.getIntValue("sy");
        final int sWidth  = options.getIntValue("sWidth");
        final int sHeight = options.getIntValue("sHeight");

        cameraExecutor.execute(() -> {
            File outFile = doCropAndPreprocess(imagePath, sx, sy, sWidth, sHeight);

            // 清理原始文件（如果是插件自己生成的临时文件）
            new File(imagePath).delete();

            if (outFile != null) {
                invokeCallback(callback, true, "ok", "file://" + outFile.getAbsolutePath());
            } else {
                invokeCallback(callback, false, "裁剪/预处理失败", null);
            }
        });
    }

    /**
     * JS 调用示例：
     *   this.$refs.vibeCamera.cropAndUpload({
     *       imagePath: 'file:///...',
     *       sx: 120, sy: 0, sWidth: 800, sHeight: 600,
     *       uploadUrl: 'upload_url'
     *   }, (res) => {
     *       if (res.status === 'success') {
     *           const ocrJson = res.data;  // OCR 接口返回的原始 JSON 字符串
     *       }
     *   });
     */
    @UniJSMethod
    public void cropAndUpload(JSONObject options, UniJSCallback callback) {
        if (callback == null) return;

        final String imagePath = options.getString("imagePath").replace("file://", "");
        final int sx       = options.getIntValue("sx");
        final int sy       = options.getIntValue("sy");
        final int sWidth   = options.getIntValue("sWidth");
        final int sHeight  = options.getIntValue("sHeight");
        final String uploadUrl = options.getString("uploadUrl");

        cameraExecutor.execute(() -> {
            // 清理原始文件
            new File(imagePath).delete();

            File procFile = doCropAndPreprocess(imagePath, sx, sy, sWidth, sHeight);
            if (procFile == null) {
                invokeCallback(callback, false, "裁剪/预处理失败", null);
                return;
            }
            // 复用已有上传逻辑
            uploadAndReturn(procFile, uploadUrl, callback);
        });
    }

    /**
     * JS 调用示例：
     *   this.$refs.vibeCamera.uploadMultiplePhotos({
     *       paths: ['file:///...proc_1.jpg', 'file:///...proc_2.jpg'],
     *       uploadUrl: 'upload_url'
     *   }, (res) => {
     *       if (res.status === 'success') {
     *           const ocrJson = res.data;  // 与单张相同的 JSON 格式
     *       }
     *   });
     *
     * 注：OCR 服务需支持一次 POST 携带多个 "file" 字段（与 local.html 行为一致）
     */
    @UniJSMethod
    public void uploadMultiplePhotos(JSONObject options, UniJSCallback callback) {
        if (callback == null) return;

        final com.alibaba.fastjson.JSONArray paths = options.getJSONArray("paths");
        final String uploadUrl = options.getString("uploadUrl");

        if (paths == null || paths.isEmpty()) {
            invokeCallback(callback, false, "paths 为空", null);
            return;
        }

        cameraExecutor.execute(() -> {
            try {
                MultipartBody.Builder builder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM);

                java.util.List<File> tempFiles = new java.util.ArrayList<>();

                for (int i = 0; i < paths.size(); i++) {
                    String p = paths.getString(i).replace("file://", "");
                    File f = new File(p);
                    if (f.exists()) {
                        builder.addFormDataPart(
                                "file",
                                f.getName(),
                                RequestBody.create(f, MediaType.parse("image/jpeg"))
                        );
                        tempFiles.add(f);
                    } else {
                        Log.w(TAG, "uploadMultiplePhotos: file not found: " + p);
                    }
                }

                if (tempFiles.isEmpty()) {
                    invokeCallback(callback, false, "没有可上传的文件", null);
                    return;
                }

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build();

                Request request = new Request.Builder()
                        .url(uploadUrl)
                        .post(builder.build())
                        .build();

                client.newCall(request).enqueue(new Callback() {
                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String result = response.body() != null ? response.body().string() : "[]";
                        // 上传完毕，清理所有临时文件
                        for (File f : tempFiles) f.delete();
                        invokeCallback(callback, true, "ok", result);
                    }

                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        for (File f : tempFiles) f.delete();
                        invokeCallback(callback, false, "上传失败: " + e.getMessage(), null);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "uploadMultiplePhotos failed", e);
                invokeCallback(callback, false, "上传异常: " + e.getMessage(), null);
            }
        });
    }

    /**
     * OkHttp 上传，结果通过 callback 返回给 JS
     */
    private void uploadAndReturn(File imageFile, String uploadUrl, UniJSCallback callback) {
        if (mUniSDKInstance == null || mUniSDKInstance.isDestroy()) {
            imageFile.delete();
            return;
        }

        // 先把文件读成 byte[]，彻底脱离文件句柄
        // 避免 OkHttp 在子线程持有 File 引用时 Activity 已销毁
        final byte[] imageBytes;
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(imageFile);
            imageBytes = new byte[(int) imageFile.length()];
            fis.read(imageBytes);
            fis.close();
            imageFile.delete(); // 读完立刻删，不再依赖文件
        } catch (IOException e) {
            imageFile.delete();
            safeInvokeCallback(callback, false, "文件读取失败: " + e.getMessage(), null);
            return;
        }

        try {
            OkHttpClient client = httpClient;

            // 用 byte[] 构建 RequestBody，不依赖 File 对象
            RequestBody fileBody = RequestBody.create(
                    MediaType.parse("image/jpeg"),
                    imageBytes
            );

            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "upload.jpg", fileBody)
                    .build();

            Request request = new Request.Builder()
                    .url(uploadUrl)
                    .post(body)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    try {
                        String result = response.body() != null
                                ? response.body().string() : "[]";
                        response.close();
                        safeInvokeCallback(callback, true, "ok", result);
                    } catch (Exception e) {
                        safeInvokeCallback(callback, false, "响应解析失败: " + e.getMessage(), null);
                    }
                }

                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    safeInvokeCallback(callback, false, "上传失败: " + e.getMessage(), null);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "uploadAndReturn exception", e);
            safeInvokeCallback(callback, false, "上传异常: " + e.getMessage(), null);
        }
    }

    private void safeInvokeCallback(UniJSCallback callback, boolean success,
                                    String msg, String data) {
        if (callback == null) return;
        if (mUniSDKInstance == null || mUniSDKInstance.isDestroy()) return;
        invokeCallback(callback, success, msg, data);
    }

    /**
     * 内部方法：裁剪 + 预处理，返回临时文件；失败返回 null
     */
    private File doCropAndPreprocess(String rawPath, int sx, int sy, int sWidth, int sHeight) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap raw = BitmapFactory.decodeFile(rawPath, opts);
            if (raw == null) return null;

            int safeX = Math.max(0, Math.min(sx, raw.getWidth()  - 1));
            int safeY = Math.max(0, Math.min(sy, raw.getHeight() - 1));
            int safeW = Math.min(sWidth,  raw.getWidth()  - safeX);
            int safeH = Math.min(sHeight, raw.getHeight() - safeY);

            Bitmap cropped  = Bitmap.createBitmap(raw, safeX, safeY, safeW, safeH);
            raw.recycle();

            Bitmap processed = ImagePreprocessor.enhanceBlueInk(cropped);    cropped.recycle();
            Bitmap enhanced  = ImagePreprocessor.enhanceContrast(processed, 1.3f); processed.recycle();
            Bitmap sharpened = ImagePreprocessor.sharpen(enhanced, 0.5f);    enhanced.recycle();

            File outFile = new File(
                    mUniSDKInstance.getContext().getCacheDir(),
                    "proc_" + System.currentTimeMillis() + ".jpg"
            );
            boolean saved = ImagePreprocessor.compressAndSave(sharpened, outFile.getAbsolutePath(), 80);
            sharpened.recycle();

            return saved ? outFile : null;

        } catch (Exception e) {
            Log.e(TAG, "doCropAndPreprocess failed: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 统一回调格式，供 callback 和 fireEvent 复用
     */
    private void invokeCallback(UniJSCallback callback, boolean success,
                                String msg, String data) {
        if (callback == null) return;
        Map<String, Object> res = new HashMap<>();
        res.put("status", success ? "success" : "error");
        res.put("msg",    msg);
        if (data != null) res.put("data", data);
        callback.invoke(res);
    }
    /**
     * 5. 新增功能：设置曝光补偿 (JS调用: setExposure({index: -1}))
     * 用于解决不同布料反光程度不同的问题
     */
    @UniJSMethod
    public void setExposure(JSONObject options) {
        if (mCamera == null) return;
        int index = options.getIntValue("index");
        setExposureInternal(index);
    }

    private void setExposureInternal(int index) {
        if (mCamera == null) return;
        CameraControl control = mCamera.getCameraControl();
        control.setExposureCompensationIndex(index);
    }

    /**
     * 6. 新增功能：手电筒开关 (JS调用: enableTorch({enable: true}))
     * 常亮补光比闪光灯更适合 OCR
     */
    @UniJSMethod
    public void enableTorch(JSONObject options) {
        if (mCamera == null) return;
        boolean enable = options.getBooleanValue("enable");
        if (mCamera.getCameraInfo().hasFlashUnit()) {
            mCamera.getCameraControl().enableTorch(enable);
        }
    }

    /**
     * 7. 新增功能：变焦控制 (JS调用: setZoom({ratio: 0.5}))
     * 0.0 (最小) - 1.0 (最大)
     */
    @UniJSMethod
    public void setZoom(JSONObject options) {
        if (mCamera == null) return;
        float ratio = options.getFloatValue("ratio"); // 0.0f - 1.0f
        mCamera.getCameraControl().setLinearZoom(ratio);
    }

    // --- 辅助方法 ---

    private int aspectRatio(int width, int height) {
        double previewRatio = (double) Math.max(width, height) / Math.min(width, height);
        if (Math.abs(previewRatio - 4.0 / 3.0) <= Math.abs(previewRatio - 16.0 / 9.0)) {
            return AspectRatio.RATIO_4_3;
        }
        return AspectRatio.RATIO_16_9;
    }

    private Map<String, Object> createErrorMap(String msg) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> detail = new HashMap<>();
        detail.put("msg", msg);
        params.put("detail", detail);
        return params;
    }

    private Map<String, Object> createResMap(String status, String msg, String path) {
        Map<String, Object> res = new HashMap<>();
        res.put("status", status);
        res.put("msg", msg);
        if (path != null) res.put("path", path);
        return res;
    }

    @Override
    public void onActivityDestroy() {
        super.onActivityDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        isCameraBound.set(false);
    }

    @Override
    public void onActivityResume() {
        super.onActivityResume();
        // 如果没有绑定成功，且现在有权限，尝试重连
        if (!isCameraBound.get()) {
            checkAndRequestPermission();
        }
    }
}