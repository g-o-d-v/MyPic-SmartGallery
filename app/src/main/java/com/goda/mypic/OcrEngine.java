package com.goda.mypic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OcrEngine {
    private static final String TAG = "OcrEngine";
    private static OcrEngine instance;
    private static final Object OCR_LOCK = new Object();

    // OCR 输入的较长边。长图会先按接近方形的区域切块，因此 1280 不会再把文字横向压得很小。
    private static final int OCR_MAX_SIDE = 1280;
    // 普通图片解码时的像素预算，避免超大照片直接占用过多 Java heap。
    private static final long NORMAL_IMAGE_PIXEL_BUDGET = 8_000_000L;
    // 长图区域解码后尽量保持的最小宽度。文字 OCR 的清晰度主要取决于横向像素数。
    private static final int LONG_IMAGE_MIN_DECODED_WIDTH = 900;
    private static final int LONG_IMAGE_MAX_DECODED_WIDTH = 1600;

    private volatile long nativePointer = 0;
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
            synchronized (OcrEngine.class) {
                if (instance == null) instance = new OcrEngine();
            }
        }
        return instance;
    }

    public synchronized void initEngine(Context context) {
        if (nativePointer != 0) return;

        String detModel = copyAssetToCache(context, "det.nb");
        String recModel = copyAssetToCache(context, "rec.nb");
        String clsModel = copyAssetToCache(context, "cls.nb");
        String dictPath = copyAssetToCache(context, "ppocr_keys_v1.txt");

        if (detModel == null || recModel == null || clsModel == null || dictPath == null) return;

        loadDictionary(dictPath);

        try {
            nativePointer = init(detModel, recModel, clsModel, 0, 4, "LITE_POWER_HIGH");
        } catch (Exception e) {
            Log.e(TAG, "初始化 JNI 失败: ", e);
        }
    }

    private void loadDictionary(String path) {
        wordDict.clear();
        wordDict.add("blank");
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) wordDict.add(line);
            wordDict.add(" ");
        } catch (Exception ignored) {}
    }

    /**
     * OCR 入口。
     *
     * v3 长图策略：
     * 1. 先只读取尺寸，不把上万像素高的长截图整体解码/缩小；
     * 2. 长图用 BitmapRegionDecoder 直接从原图按区域解码，每块保持约 900~1600px 的有效宽度；
     * 3. 每块接近方形，避免 native 层按 maxSizeLen 缩放时把横向文字压成低分辨率；
     * 4. 相邻块保留重叠，但按文字框中心点划分唯一“归属区”，从根源去掉重复段落。
     */
    public String extractTextFromImage(Context context, Uri imageUri) {
        if (nativePointer == 0) initEngine(context);
        if (nativePointer == 0) return null;

        ImageBounds bounds = readImageBounds(context, imageUri);
        if (bounds.isValid() && bounds.height > bounds.width * 2.5f) {
            String longImageText = extractTextFromLongImage(context, imageUri);
            if (longImageText != null) return longImageText;
            // 某些特殊格式不支持 RegionDecoder 时继续走普通解码兜底。
        }

        Bitmap bitmap = null;
        Bitmap enhanced = null;
        try {
            bitmap = getBitmapFromUriSafely(context, imageUri);
            if (bitmap == null) return null;

            float scale = calculateEnhanceScale(bitmap.getWidth());
            enhanced = scale > 1.01f ? getEnhancedBitmap(bitmap, scale) : bitmap;

            List<RecognizedBox> boxes = doActualOcrScanBoxes(enhanced, OCR_MAX_SIDE);
            return buildTextFromBoxes(boxes);
        } catch (Exception e) {
            Log.e(TAG, "OCR 解析异常", e);
            return null;
        } finally {
            if (enhanced != null && enhanced != bitmap && !enhanced.isRecycled()) enhanced.recycle();
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    /**
     * 对竖向长截图做原图区域解码。只同时持有一个切片，长达几万像素也不会整体进内存。
     */
    private String extractTextFromLongImage(Context context, Uri imageUri) {
        ParcelFileDescriptor pfd = null;
        BitmapRegionDecoder decoder = null;
        try {
            pfd = context.getContentResolver().openFileDescriptor(imageUri, "r");
            if (pfd == null) return null;

            decoder = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
            if (decoder == null) return null;

            final int srcWidth = decoder.getWidth();
            final int srcHeight = decoder.getHeight();
            if (srcWidth <= 0 || srcHeight <= 0) return null;

            final int sampleSize = calculateLongImageSampleSize(srcWidth);
            final int decodedWidth = Math.max(1, srcWidth / sampleSize);

            // 接近方形的切片能最大程度保住横向文字分辨率。
            final int decodedChunkHeight = Math.max(720, Math.round(decodedWidth * 1.08f));
            final int decodedOverlap = Math.max(96,
                    Math.min(180, Math.round(decodedChunkHeight * 0.12f)));
            final int sourceChunkHeight = Math.max(sampleSize, decodedChunkHeight * sampleSize);
            final int sourceOverlap = Math.max(sampleSize, decodedOverlap * sampleSize);
            final int sourceStride = Math.max(sampleSize, sourceChunkHeight - sourceOverlap);

            List<RecognizedBox> allBoxes = new ArrayList<>();

            for (int sourceTop = 0; sourceTop < srcHeight; sourceTop += sourceStride) {
                int sourceBottom = Math.min(srcHeight, sourceTop + sourceChunkHeight);
                boolean isFirst = sourceTop == 0;
                boolean isLast = sourceBottom >= srcHeight;

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;

                Bitmap chunk = null;
                Bitmap enhanced = null;
                try {
                    chunk = decoder.decodeRegion(new Rect(0, sourceTop, srcWidth, sourceBottom), options);
                    if (chunk == null) continue;

                    float enhanceScale = calculateEnhanceScale(chunk.getWidth());
                    enhanced = enhanceScale > 1.01f ? getEnhancedBitmap(chunk, enhanceScale) : chunk;

                    List<RecognizedBox> chunkBoxes = doActualOcrScanBoxes(enhanced, OCR_MAX_SIDE);
                    if (chunkBoxes.isEmpty()) {
                        if (isLast) break;
                        continue;
                    }

                    // 两块的重叠区以“中线”分家：上一块负责前半，下一块负责后半。
                    // 这样既不会重复，也让靠近切片边缘、可能被裁到的文字优先交给另一块识别。
                    float ownedTop = isFirst ? 0f : sourceTop + sourceOverlap * 0.5f;
                    float ownedBottom = isLast ? srcHeight : sourceBottom - sourceOverlap * 0.5f;

                    float localToSource = sampleSize / enhanceScale;
                    for (RecognizedBox box : chunkBoxes) {
                        float globalCenterY = sourceTop + box.centerY * localToSource;
                        if (globalCenterY < ownedTop || globalCenterY >= ownedBottom) continue;

                        allBoxes.add(new RecognizedBox(
                                box.text,
                                box.minX * localToSource,
                                sourceTop + box.minY * localToSource,
                                box.maxX * localToSource,
                                sourceTop + box.maxY * localToSource,
                                box.score));
                    }
                } finally {
                    if (enhanced != null && enhanced != chunk && !enhanced.isRecycled()) enhanced.recycle();
                    if (chunk != null && !chunk.isRecycled()) chunk.recycle();
                }

                if (isLast) break;
            }

            return buildTextFromBoxes(allBoxes);
        } catch (Exception e) {
            Log.w(TAG, "长图区域 OCR 失败，将回退普通解码", e);
            return null;
        } finally {
            if (decoder != null) decoder.recycle();
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private List<RecognizedBox> doActualOcrScanBoxes(Bitmap bmp, int maxSide) {
        float[] resultArray;
        synchronized (OCR_LOCK) {
            resultArray = forward(nativePointer, bmp, maxSide, 1, 0, 1);
        }
        if (resultArray == null || resultArray.length == 0) return new ArrayList<>();
        return parseFloatArrayToBoxes(resultArray);
    }

    /**
     * 小图才做适量放大。原始宽度已经足够时不做“先放大再被 native 缩回去”的无意义操作。
     */
    private float calculateEnhanceScale(int width) {
        if (width <= 0) return 1f;
        if (width >= 960) return 1f;
        return Math.min(2.5f, Math.max(1f, 960f / width));
    }

    private Bitmap getEnhancedBitmap(Bitmap src, float scale) {
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private ImageBounds readImageBounds(Context context, Uri uri) {
        InputStream input = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            return new ImageBounds(options.outWidth, options.outHeight);
        } catch (Exception e) {
            return new ImageBounds(0, 0);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 长图只按“宽度”决定采样率，而不是按总高度。
     * 例如 1080x12000 的截图保持 1080px 宽，不再变成约 180~300px 宽。
     */
    private int calculateLongImageSampleSize(int sourceWidth) {
        int sample = 1;
        while (sourceWidth / sample > LONG_IMAGE_MAX_DECODED_WIDTH) {
            int next = sample * 2;
            if (sourceWidth / next < LONG_IMAGE_MIN_DECODED_WIDTH) break;
            sample = next;
        }
        return Math.max(1, sample);
    }

    /**
     * 普通图片按总像素预算降采样，但尽量让短边保持 >= 900px，避免文字照片被过度压缩。
     */
    private Bitmap getBitmapFromUriSafely(Context context, Uri uri) {
        InputStream input = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            if (input != null) input.close();
            input = null;

            if (options.outWidth <= 0 || options.outHeight <= 0) return null;

            int sample = 1;
            while (true) {
                long currentPixels = ((long) options.outWidth / sample) * ((long) options.outHeight / sample);
                if (currentPixels <= NORMAL_IMAGE_PIXEL_BUDGET) break;

                int next = sample * 2;
                int nextShortSide = Math.min(options.outWidth / next, options.outHeight / next);
                if (nextShortSide < 900) break;
                sample = next;
            }

            options.inSampleSize = Math.max(1, sample);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;

            input = context.getContentResolver().openInputStream(uri);
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception e) {
            Log.w(TAG, "图片解码失败", e);
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Paddle 原生结果先解析成带坐标的文字框。真正的阅读顺序由 buildTextFromBoxes 统一处理。
     */
    private List<RecognizedBox> parseFloatArrayToBoxes(float[] floatArr) {
        List<RecognizedBox> boxes = new ArrayList<>();
        int i = 0;

        while (i + 3 <= floatArr.length) {
            int pointNum = Math.max(0, (int) floatArr[i++]);
            int wordNum = Math.max(0, (int) floatArr[i++]);
            float score = floatArr[i++];

            int pointValueCount = pointNum * 2;
            if (i + pointValueCount + wordNum + 2 > floatArr.length) {
                Log.w(TAG, "OCR 返回数组长度异常，已停止解析");
                break;
            }

            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;

            for (int p = 0; p < pointNum; p++) {
                float x = floatArr[i++];
                float y = floatArr[i++];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }

            StringBuilder text = new StringBuilder();
            for (int w = 0; w < wordNum; w++) {
                int index = (int) floatArr[i++];
                if (index >= 0 && index < wordDict.size()) text.append(wordDict.get(index));
            }

            // cls label + cls score
            i += 2;

            String value = text.toString().trim();
            if (!value.isEmpty()) {
                if (pointNum == 0) {
                    minX = 0f;
                    minY = boxes.size();
                    maxX = 1f;
                    maxY = minY + 1f;
                }
                boxes.add(new RecognizedBox(value, minX, minY, maxX, maxY, score));
            }
        }
        return boxes;
    }

    /**
     * 视觉阅读顺序：整体从上到下，同一行从左到右。
     */
    private String buildTextFromBoxes(List<RecognizedBox> sourceBoxes) {
        if (sourceBoxes == null || sourceBoxes.isEmpty()) return "";

        List<RecognizedBox> boxes = new ArrayList<>(sourceBoxes);
        boxes.sort(Comparator
                .comparingDouble((RecognizedBox b) -> b.centerY)
                .thenComparingDouble(b -> b.minX));

        List<List<RecognizedBox>> lines = new ArrayList<>();
        for (RecognizedBox box : boxes) {
            List<RecognizedBox> bestLine = null;
            float bestDistance = Float.MAX_VALUE;

            for (List<RecognizedBox> line : lines) {
                float lineCenter = averageCenterY(line);
                float lineHeight = averageHeight(line);
                float tolerance = Math.max(6f, Math.max(lineHeight, box.height) * 0.55f);
                float distance = Math.abs(box.centerY - lineCenter);
                if (distance <= tolerance && distance < bestDistance) {
                    bestLine = line;
                    bestDistance = distance;
                }
            }

            if (bestLine == null) {
                bestLine = new ArrayList<>();
                lines.add(bestLine);
            }
            bestLine.add(box);
        }

        lines.sort(Comparator.comparingDouble(this::averageTop));
        StringBuilder fullText = new StringBuilder();
        for (List<RecognizedBox> line : lines) {
            line.sort(Comparator.comparingDouble(b -> b.minX));
            for (RecognizedBox box : line) fullText.append(box.text);
            fullText.append('\n');
        }
        return fullText.toString().trim();
    }

    private float averageCenterY(List<RecognizedBox> line) {
        if (line.isEmpty()) return 0f;
        float sum = 0f;
        for (RecognizedBox box : line) sum += box.centerY;
        return sum / line.size();
    }

    private float averageHeight(List<RecognizedBox> line) {
        if (line.isEmpty()) return 1f;
        float sum = 0f;
        for (RecognizedBox box : line) sum += box.height;
        return Math.max(1f, sum / line.size());
    }

    private float averageTop(List<RecognizedBox> line) {
        if (line.isEmpty()) return 0f;
        float sum = 0f;
        for (RecognizedBox box : line) sum += box.minY;
        return sum / line.size();
    }

    private static class ImageBounds {
        final int width;
        final int height;

        ImageBounds(int width, int height) {
            this.width = width;
            this.height = height;
        }

        boolean isValid() {
            return width > 0 && height > 0;
        }
    }

    private static class RecognizedBox {
        final String text;
        final float minX;
        final float minY;
        final float maxX;
        final float maxY;
        final float centerY;
        final float height;
        final float score;

        RecognizedBox(String text, float minX, float minY, float maxX, float maxY, float score) {
            this.text = text;
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.centerY = (minY + maxY) * 0.5f;
            this.height = Math.max(1f, maxY - minY);
            this.score = score;
        }
    }

    private String copyAssetToCache(Context context, String assetFileName) {
        String outputFileName = assetFileName.substring(assetFileName.lastIndexOf("/") + 1);
        File outFile = new File(context.getFilesDir(), outputFileName);
        if (outFile.exists() && outFile.length() > 1024) return outFile.getAbsolutePath();
        try (InputStream is = context.getAssets().open(assetFileName);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1024 * 8];
            int length;
            while ((length = is.read(buffer)) > 0) fos.write(buffer, 0, length);
            fos.flush();
            return outFile.getAbsolutePath();
        } catch (Exception e) {
            if (outFile.exists()) outFile.delete();
            return null;
        }
    }
}
