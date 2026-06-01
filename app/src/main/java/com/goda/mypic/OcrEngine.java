package com.goda.mypic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class OcrEngine {
    private static final String TAG = "OcrEngine";
    private static OcrEngine instance;
    private static final Object OCR_LOCK = new Object(); // 防止并发把 CPU 跑冒烟

    private long nativePointer = 0;
    private List<String> wordDict = new ArrayList<>();

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("paddle_light_api_shared");
            System.loadLibrary("opencv_java4");
            System.loadLibrary("Native");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "C++ 库加载失败", e);
        }
    }

    public native long init(String detModelPath, String recModelPath, String clsModelPath, int useOpencl, int threadNum, String cpuMode);
    public native float[] forward(long pointer, Bitmap image, int maxSizeLen, int runDet, int runCls, int runRec);
    public native void release(long pointer);

    private OcrEngine() {}

    public static OcrEngine getInstance() {
        if (instance == null) {
            instance = new OcrEngine();
        }
        return instance;
    }

    public void initEngine(Context context) {
        if (nativePointer != 0) return;

        // 🚨 请确保这里的名字与你 assets 里的一模一样！
        String detModel = copyAssetToCache(context, "det.nb");
        String recModel = copyAssetToCache(context, "rec.nb");
        String clsModel = copyAssetToCache(context, "cls.nb");
        String dictPath = copyAssetToCache(context, "ppocr_keys_v1.txt");

        if (detModel == null || recModel == null || clsModel == null || dictPath == null) return;

        loadDictionary(dictPath);

        try {
            // 使用 4 线程初始化
            nativePointer = init(detModel, recModel, clsModel, 0, 4, "LITE_POWER_HIGH");
        } catch (Exception e) {
            Log.e(TAG, "初始化 JNI 失败: ", e);
        }
    }

    private void loadDictionary(String path) {
        wordDict.clear(); wordDict.add("blank");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line; while ((line = br.readLine()) != null) wordDict.add(line);
            wordDict.add(" ");
        } catch (Exception ignored) {}
    }

    /**
     * 【增强版 OCR 扫描引擎】支持长图切片与加粗文字物理放大
     */
    public String extractTextFromImage(Context context, Uri imageUri) {
        if (nativePointer == 0) initEngine(context);
        if (nativePointer == 0) return null;

        Bitmap bitmap = null;
        try {
            // 🚨 修复 1：不再进行毁灭性的 640x640 压缩，保留足够清晰度用于放大
            bitmap = getBitmapFromUriSafely(context, imageUri);
            if (bitmap == null) return null;

            StringBuilder resultText = new StringBuilder();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            boolean textFound = false;

            // 🚨 修复 2：长图判断阈值 (高宽比大于 2.5 倍视为长图)
            if (height > width * 2.5f) {
                // === 切片扫描流 ===
                int chunkHeight = width * 2; // 每次截取两倍宽度的长度作为一块
                int overlap = 150; // 重叠区域，防止把文字拦腰斩断

                for (int y = 0; y < height; y += (chunkHeight - overlap)) {
                    int remainHeight = height - y;
                    int currentHeight = Math.min(chunkHeight, remainHeight);

                    // 1. 切出当前块
                    Bitmap chunk = Bitmap.createBitmap(bitmap, 0, y, width, currentHeight);

                    // 2. 核心物理外挂：把这一块插值放大 1.5 倍，硬生生扯开加粗文字的笔画粘连！
                    Bitmap enhancedChunk = getEnhancedBitmap(chunk, 1.5f);

                    // 3. 执行识别
                    String text = doActualOcrScan(enhancedChunk);
                    if (text != null && !text.isEmpty()) {
                        resultText.append(text).append("\n");
                        textFound = true;
                    }

                    // 4. 及时回收切片内存，防止 OOM
                    enhancedChunk.recycle();
                    if (chunk != bitmap) chunk.recycle();

                    if (y + currentHeight >= height) break;
                }
            } else {
                // === 普通图片放大扫描 ===
                // 普通图也放大 1.5 倍，专门治疗加粗字体瞎眼症
                Bitmap enhancedBmp = getEnhancedBitmap(bitmap, 1.5f);

                String text = doActualOcrScan(enhancedBmp);
                if (text != null && !text.isEmpty()) {
                    resultText.append(text);
                    textFound = true;
                }
                enhancedBmp.recycle();
            }

            if (textFound) {
                return resultText.toString().trim();
            } else {
                return ""; // 引擎跑了但没字，返回空串标记，防止重复扫描
            }

        } catch (Exception e) {
            Log.e(TAG, "OCR 解析异常", e);
            return null;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /**
     * 将 Bitmap 丢入底层 C++ 引擎进行识别
     */
    private String doActualOcrScan(Bitmap bmp) {
        float[] resultArray;
        synchronized (OCR_LOCK) {
            // 🚨 修复 3：将 maxSizeLen 从 640 提升到了 960
            // 如果还用 640，我们在 Java 层的放大就白做了，底层会再次把它压缩成马赛克！
            resultArray = forward(nativePointer, bmp, 960, 1, 0, 1);
        }

        if (resultArray != null && resultArray.length > 0) {
            return parseFloatArrayToText(resultArray);
        }
        return null;
    }

    /**
     * 辅助方法：插值放大 Bitmap，扯开粘连的笔画
     */
    private Bitmap getEnhancedBitmap(Bitmap src, float scale) {
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        // 最后一个参数 true 表示开启双线性过滤平滑插值
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    /**
     * 辅助方法：安全加载图片，防止原始 4K 大图直接撑爆内存
     */
    private Bitmap getBitmapFromUriSafely(Context context, Uri uri) {
        try {
            InputStream input = context.getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            if (input != null) input.close();

            options.inSampleSize = 1;
            // 控制在 2000 像素左右，既保证清晰度又防止 OOM
            int maxDim = Math.max(options.outWidth, options.outHeight);
            if (maxDim > 2048) {
                options.inSampleSize = maxDim / 2048;
                // 确保 SampleSize 是 2 的整数次幂，提升解码效率
                if (options.inSampleSize < 2) options.inSampleSize = 2;
                else if (options.inSampleSize < 4) options.inSampleSize = 4;
            }

            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            input = context.getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
            if (input != null) input.close();

            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private String parseFloatArrayToText(float[] floatArr) {
        StringBuilder fullText = new StringBuilder();
        int i = 0;
        while (i < floatArr.length) {
            int pointNum = (int) floatArr[i++];
            int wordNum = (int) floatArr[i++];
            float score = floatArr[i++];
            i += pointNum * 2;
            for (int w = 0; w < wordNum; w++) {
                int index = (int) floatArr[i++];
                if (index >= 0 && index < wordDict.size()) fullText.append(wordDict.get(index));
            }
            i += 2; // 跳过 cls 结果
        }
        return fullText.toString();
    }

    private String copyAssetToCache(Context context, String assetFileName) {
        String outputFileName = assetFileName.substring(assetFileName.lastIndexOf("/") + 1);
        File outFile = new File(context.getFilesDir(), outputFileName);
        if (outFile.exists() && outFile.length() > 1024) return outFile.getAbsolutePath();
        try (InputStream is = context.getAssets().open(assetFileName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1024 * 8]; int length;
            while ((length = is.read(buffer)) > 0) fos.write(buffer, 0, length);
            fos.flush(); return outFile.getAbsolutePath();
        } catch (Exception e) {
            if (outFile.exists()) outFile.delete(); return null;
        }
    }
}