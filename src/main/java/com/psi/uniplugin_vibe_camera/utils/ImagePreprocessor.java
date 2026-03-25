package com.psi.uniplugin_vibe_camera.utils;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 图像预处理工具类
 * 用于优化OCR识别效果
 *
 * 功能：
 * 1. 对比度增强 - 提高文字与背景的区分度
 * 2. 锐化处理 - 增加文字边缘清晰度
 * 3. 智能压缩 - 减少上传时间
 * 4. 二值化处理 - 黑白化，适合文字识别
 *
 * @author Your Name
 */
public class ImagePreprocessor {

    private static final String TAG = "ImagePreprocessor";

    /**
     * 综合预处理（推荐）
     * 适合白色布料上的黑色文字识别
     *
     * @param imagePath 原始图片路径
     * @param outputPath 输出图片路径
     * @return 是否成功
     */
    public static boolean preprocessForOCR(String imagePath, String outputPath) {
        try {
            // 1. 加载原图
            Bitmap original = BitmapFactory.decodeFile(imagePath);
            if (original == null) {
                Log.e(TAG, "Failed to load image: " + imagePath);
                return false;
            }

            // 2. 蓝色增强
            Bitmap blueEnhanced = enhanceBlueInk(original);

            // 3. 增强对比度（针对反光场景）
            Bitmap enhanced = enhanceContrast(blueEnhanced, 1.3f);
            original.recycle();

            // 4. 轻微锐化（提高清晰度）
            Bitmap sharpened = sharpen(enhanced, 0.5f);
            enhanced.recycle();

            // 5. 智能压缩（保持质量）
            boolean success = compressAndSave(sharpened, outputPath, 90);
            sharpened.recycle();

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Preprocessing failed: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 增强对比度
     *
     * @param bitmap 原图
     * @param contrast 对比度系数 (1.0 = 原始, >1.0 = 增强, <1.0 = 降低)
     * @return 处理后的图片
     */
    public static Bitmap enhanceContrast(Bitmap bitmap, float contrast) {
        try {
            ColorMatrix cm = new ColorMatrix();
            float translate = (1.0f - contrast) / 2.0f * 255.0f;

            cm.set(new float[] {
                    contrast, 0, 0, 0, translate,
                    0, contrast, 0, 0, translate,
                    0, 0, contrast, 0, translate,
                    0, 0, 0, 1, 0
            });

            return applyColorMatrix(bitmap, cm);

        } catch (Exception e) {
            Log.e(TAG, "Contrast enhancement failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 针对蓝色喷印字的专用增强
     * 强化蓝色像素，压暗非蓝色区域，提升OCR识别率
     */
    public static Bitmap enhanceBlueInk(Bitmap bitmap) {
        int width  = bitmap.getWidth();
        int height = bitmap.getHeight();

        // 一次性读出所有像素到 int[]，比逐个 getPixel 快 10-20 倍
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int i = 0; i < pixels.length; i++) {
            int pixel = pixels[i];
            int r = (pixel >> 16) & 0xff;
            int g = (pixel >> 8)  & 0xff;
            int b =  pixel        & 0xff;

            if (b > r + 25 && b > g + 25) {
                pixels[i] = 0xFF0000CC;
            } else if (r > 200 && g > 200 && b > 200) {
                pixels[i] = 0xFFFFFFFF;
            }
        }

        // 一次性写回，不创建新 Bitmap，直接在 copy 上改
        Bitmap result = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        result.setPixels(pixels, 0, width, 0, 0, width, height);
        return result;
    }

    /**
     * 锐化处理
     *
     * @param bitmap 原图
     * @param intensity 锐化强度 (0.0 ~ 1.0)
     * @return 处理后的图片
     */
    public static Bitmap sharpen(Bitmap bitmap, float intensity) {
        try {
            // 锐化矩阵
            float sharpValue = -intensity;
            float centerValue = 1 + 4 * intensity;

            ColorMatrix sharpenMatrix = new ColorMatrix(new float[] {
                    0, sharpValue, 0, 0, 0,
                    sharpValue, centerValue, sharpValue, 0, 0,
                    0, sharpValue, 0, 0, 0,
                    0, 0, 0, 1, 0
            });

            return applyColorMatrix(bitmap, sharpenMatrix);

        } catch (Exception e) {
            Log.e(TAG, "Sharpening failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 二值化处理（黑白化）
     * 适合高对比度文字识别
     *
     * @param bitmap 原图
     * @param threshold 阈值 (0-255, 推荐128)
     * @return 处理后的图片
     */
    public static Bitmap binarize(Bitmap bitmap, int threshold) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            Bitmap binaryBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = bitmap.getPixel(x, y);

                    // 计算灰度值
                    int r = (pixel >> 16) & 0xff;
                    int g = (pixel >> 8) & 0xff;
                    int b = pixel & 0xff;
                    int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);

                    // 二值化
                    int newPixel = gray > threshold ? 0xFFFFFFFF : 0xFF000000;
                    binaryBitmap.setPixel(x, y, newPixel);
                }
            }

            return binaryBitmap;

        } catch (Exception e) {
            Log.e(TAG, "Binarization failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 转为灰度图
     *
     * @param bitmap 原图
     * @return 灰度图
     */
    public static Bitmap toGrayscale(Bitmap bitmap) {
        try {
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0); // 饱和度为0，即灰度

            return applyColorMatrix(bitmap, colorMatrix);

        } catch (Exception e) {
            Log.e(TAG, "Grayscale conversion failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 应用颜色矩阵
     */
    private static Bitmap applyColorMatrix(Bitmap bitmap, ColorMatrix colorMatrix) {
        Bitmap result = Bitmap.createBitmap(
                bitmap.getWidth(),
                bitmap.getHeight(),
                Bitmap.Config.ARGB_8888
        );

        Canvas canvas = new Canvas(result);
        Paint paint = new Paint();
        paint.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        canvas.drawBitmap(bitmap, 0, 0, paint);

        return result;
    }

    /**
     * 智能压缩并保存
     *
     * @param bitmap 图片
     * @param outputPath 输出路径
     * @param quality 质量 (0-100)
     * @return 是否成功
     */
    public static boolean compressAndSave(Bitmap bitmap, String outputPath, int quality) {
        FileOutputStream fos = null;
        try {
            File outputFile = new File(outputPath);
            File parentDir = outputFile.getParentFile();

            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            fos = new FileOutputStream(outputFile);

            // 压缩并保存
            boolean success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.flush();

            Log.d(TAG, "Image saved: " + outputPath + ", size: " + outputFile.length());

            return success;

        } catch (Exception e) {
            Log.e(TAG, "Save failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (fos != null) {
                    fos.close();
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * 智能缩放（保持宽高比）
     *
     * @param bitmap 原图
     * @param maxSize 最大边长
     * @return 缩放后的图片
     */
    public static Bitmap smartResize(Bitmap bitmap, int maxSize) {
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();

            // 如果图片已经小于最大尺寸，不缩放
            if (width <= maxSize && height <= maxSize) {
                return bitmap;
            }

            // 计算缩放比例
            float scale = Math.min(
                    (float) maxSize / width,
                    (float) maxSize / height
            );

            int newWidth = (int) (width * scale);
            int newHeight = (int) (height * scale);

            Log.d(TAG, "Resizing from " + width + "x" + height +
                    " to " + newWidth + "x" + newHeight);

            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);

        } catch (Exception e) {
            Log.e(TAG, "Resize failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 完整的图片优化流程（用于上传前）
     *
     * @param inputPath 输入图片路径
     * @param outputPath 输出图片路径
     * @param maxSize 最大尺寸
     * @param quality 压缩质量
     * @return 处理后的文件大小（字节），失败返回-1
     */
    public static long optimizeForUpload(String inputPath, String outputPath,
                                         int maxSize, int quality) {
        Bitmap bitmap = null;
        Bitmap resized = null;
        Bitmap enhanced = null;

        try {
            // 1. 加载原图
            bitmap = BitmapFactory.decodeFile(inputPath);
            if (bitmap == null) {
                return -1;
            }

            long originalSize = new File(inputPath).length();
            Log.d(TAG, "Original size: " + originalSize + " bytes");

            // 2. 缩放（如果需要）
            resized = smartResize(bitmap, maxSize);
            if (resized != bitmap) {
                bitmap.recycle();
            }

            // 3. 增强对比度
            enhanced = enhanceContrast(resized, 1.2f);
            if (enhanced != resized) {
                resized.recycle();
            }

            // 4. 保存
            boolean success = compressAndSave(enhanced, outputPath, quality);
            enhanced.recycle();

            if (success) {
                long newSize = new File(outputPath).length();
                Log.d(TAG, "Optimized size: " + newSize + " bytes, " +
                        "ratio: " + (newSize * 100 / originalSize) + "%");
                return newSize;
            } else {
                return -1;
            }

        } catch (Exception e) {
            Log.e(TAG, "Optimization failed: " + e.getMessage(), e);
            return -1;
        } finally {
            // 确保释放资源
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
            if (resized != null && !resized.isRecycled()) {
                resized.recycle();
            }
            if (enhanced != null && !enhanced.isRecycled()) {
                enhanced.recycle();
            }
        }
    }

    /**
     * 自动二值化（使用Otsu算法自动确定阈值）
     *
     * @param bitmap 原图
     * @return 二值化后的图片
     */
    public static Bitmap autoBinarize(Bitmap bitmap) {
        try {
            // 先转灰度
            Bitmap grayscale = toGrayscale(bitmap);

            // 计算最佳阈值（简化的Otsu算法）
            int threshold = calculateOtsuThreshold(grayscale);

            Log.d(TAG, "Auto threshold: " + threshold);

            // 二值化
            Bitmap result = binarize(grayscale, threshold);

            if (result != grayscale) {
                grayscale.recycle();
            }

            return result;

        } catch (Exception e) {
            Log.e(TAG, "Auto binarize failed: " + e.getMessage());
            return bitmap;
        }
    }

    /**
     * 计算Otsu阈值
     */
    private static int calculateOtsuThreshold(Bitmap bitmap) {
        // 构建灰度直方图
        int[] histogram = new int[256];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int totalPixels = width * height;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = bitmap.getPixel(x, y);
                int gray = pixel & 0xff; // 已经是灰度图，直接取值
                histogram[gray]++;
            }
        }

        // Otsu算法
        float sum = 0;
        for (int i = 0; i < 256; i++) {
            sum += i * histogram[i];
        }

        float sumB = 0;
        int wB = 0;
        int wF = 0;
        float maxVariance = 0;
        int threshold = 0;

        for (int i = 0; i < 256; i++) {
            wB += histogram[i];
            if (wB == 0) continue;

            wF = totalPixels - wB;
            if (wF == 0) break;

            sumB += i * histogram[i];

            float mB = sumB / wB;
            float mF = (sum - sumB) / wF;

            float variance = wB * wF * (mB - mF) * (mB - mF);

            if (variance > maxVariance) {
                maxVariance = variance;
                threshold = i;
            }
        }

        return threshold;
    }
}