package com.goda.mypic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.nless.pdf_search_engine.index.PdfPageIndex;
import com.nless.pdf_search_engine.index.PdfPageIndexFactory;
import com.nless.pdf_search_engine.index.PdfTextToken;
import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;
import com.nless.pdf_search_engine.paddle.PaddleOcrEngine;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 图库图片到开源库 {@link PaddleOcrEngine} / {@link PdfPageIndexFactory} 的解码适配层。
 * 负责大图降采样、EXIF 方向校正、竖向长图分块解码，并结合 {@link OcrParagraphFormatter} 完成段落检测与文本重建。
 */
public final class ImageOcrHelper {

    private static final String TAG = "ImageOcrHelper";
    private static final long NORMAL_IMAGE_PIXEL_BUDGET = 8_000_000L;
    private static final int LONG_IMAGE_MIN_DECODED_WIDTH = 900;
    private static final int LONG_IMAGE_MAX_DECODED_WIDTH = 1600;
    private static final int TARGET_MIN_WIDTH = 960;
    private static final float MULTI_COLUMN_MIN_GAP_RATIO = 0.16f;

    private ImageOcrHelper() {
    }

    /** 释放开源库底层持有的 native OCR 资源。 */
    public static void release() {
        PaddleOcrEngine.getInstance().release();
    }

    /** 仅在没有进行中的 OCR 任务时释放 native 资源，避免打断推理。 */
    public static void releaseIfIdle() {
        PaddleOcrEngine.getInstance().releaseIfIdle();
    }

    /**
     * 提取图片中的文字，返回按阅读顺序与排版空格重建后的文本。
     * 无法解码或识别失败时返回 {@code null}。
     */
    public static String extractText(Context context, Uri imageUri) {
        if (context == null || imageUri == null) return null;
        Context appContext = context.getApplicationContext();

        ImageMeta meta = readImageMeta(appContext, imageUri);
        if (meta.isUpright() && meta.hasValidSize() && meta.rawHeight > meta.rawWidth * 2.5f) {
            String longText = extractLongImageText(appContext, imageUri);
            if (longText != null) return longText;
        }

        return extractStandardImageText(appContext, imageUri, meta);
    }

    private static String extractStandardImageText(
            Context appContext,
            Uri imageUri,
            ImageMeta meta
    ) {
        Bitmap decoded = null;
        Bitmap oriented = null;
        Bitmap input = null;
        try {
            decoded = decodeNormalBitmapSafely(appContext, imageUri, meta);
            if (decoded == null) return null;

            oriented = applyExifOrientation(decoded, meta.exifOrientation);
            float scale = calculateEnhanceScale(oriented.getWidth());
            input = scale > 1.01f ? scaleBitmap(oriented, scale) : oriented;

            OcrPageResult result = PaddleOcrEngine.getInstance().recognizePage(appContext, input);
            return formatPageResult(result, true);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "普通图片 OCR 失败: " + imageUri, e);
            return null;
        } finally {
            recycleIfDistinct(input, oriented, decoded);
            recycleIfDistinct(oriented, decoded, null);
            recycleIfNecessary(decoded);
        }
    }

    /**
     * 对未旋转的竖向长截图做原图分块解码，再将所有切片的 {@link OcrTextBlock}
     * 映射回整图坐标并交给 {@link PdfPageIndexFactory#fromOcr} 统一恢复阅读顺序与空格。
     */
    private static String extractLongImageText(Context appContext, Uri imageUri) {
        ParcelFileDescriptor pfd = null;
        BitmapRegionDecoder decoder = null;
        try {
            pfd = appContext.getContentResolver().openFileDescriptor(imageUri, "r");
            if (pfd == null) return null;

            decoder = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
            if (decoder == null) return null;

            int srcWidth = decoder.getWidth();
            int srcHeight = decoder.getHeight();
            if (srcWidth <= 0 || srcHeight <= 0) return null;

            int sampleSize = calculateLongImageSampleSize(srcWidth);
            int decodedWidth = Math.max(1, srcWidth / sampleSize);
            int decodedChunkHeight = Math.max(720, Math.round(decodedWidth * 1.08f));
            int decodedOverlap = Math.max(96, Math.min(180, Math.round(decodedChunkHeight * 0.12f)));
            int sourceChunkHeight = Math.max(sampleSize, decodedChunkHeight * sampleSize);
            int sourceOverlap = Math.max(sampleSize, decodedOverlap * sampleSize);
            int sourceStride = Math.max(sampleSize, sourceChunkHeight - sourceOverlap);

            List<OcrTextBlock> allBlocks = new ArrayList<>();
            boolean anyChunkRecognized = false;

            for (int sourceTop = 0; sourceTop < srcHeight; sourceTop += sourceStride) {
                int sourceBottom = Math.min(srcHeight, sourceTop + sourceChunkHeight);
                int regionHeight = Math.max(1, sourceBottom - sourceTop);
                boolean isFirst = sourceTop == 0;
                boolean isLast = sourceBottom >= srcHeight;

                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;

                Bitmap chunk = null;
                Bitmap input = null;
                try {
                    chunk = decoder.decodeRegion(new Rect(0, sourceTop, srcWidth, sourceBottom), options);
                    if (chunk == null) {
                        if (isLast) break;
                        continue;
                    }

                    float enhanceScale = calculateEnhanceScale(chunk.getWidth());
                    input = enhanceScale > 1.01f ? scaleBitmap(chunk, enhanceScale) : chunk;

                    OcrPageResult chunkResult = PaddleOcrEngine.getInstance().recognizePage(appContext, input);
                    if (chunkResult != null) {
                        anyChunkRecognized = true;
                        float ownedTop = isFirst ? 0f : sourceTop + sourceOverlap * 0.5f;
                        float ownedBottom = isLast ? srcHeight : sourceBottom - sourceOverlap * 0.5f;
                        appendMappedChunkBlocks(
                                allBlocks,
                                chunkResult,
                                srcWidth,
                                sourceTop,
                                regionHeight,
                                ownedTop,
                                ownedBottom
                        );
                    }
                } finally {
                    recycleIfDistinct(input, chunk, null);
                    recycleIfNecessary(chunk);
                }

                if (isLast) break;
            }

            if (!anyChunkRecognized) return null;

            // 长截图通常为单一时间线信息流/网页，保留严格自上而下阅读顺序，避免双栏误拆聊天左右气泡。
            OcrPageResult merged = new OcrPageResult(srcWidth, srcHeight, allBlocks);
            return formatPageResult(merged, false);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "长图分块 OCR 失败，将回退普通解码: " + imageUri, e);
            return null;
        } finally {
            if (decoder != null) decoder.recycle();
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void appendMappedChunkBlocks(
            List<OcrTextBlock> target,
            OcrPageResult chunkResult,
            int sourceWidth,
            int sourceTop,
            int regionHeight,
            float ownedTop,
            float ownedBottom
    ) {
        if (chunkResult.blocks == null || chunkResult.blocks.isEmpty()) return;
        if (chunkResult.bitmapWidth <= 0 || chunkResult.bitmapHeight <= 0) return;

        float scaleX = sourceWidth / (float) chunkResult.bitmapWidth;
        float scaleY = regionHeight / (float) chunkResult.bitmapHeight;

        for (OcrTextBlock block : chunkResult.blocks) {
            if (block == null || block.rectInBitmap == null || block.text == null) continue;
            float globalCenterY = sourceTop + block.rectInBitmap.centerY() * scaleY;
            if (globalCenterY < ownedTop || globalCenterY >= ownedBottom) continue;

            RectF globalRect = new RectF(
                    block.rectInBitmap.left * scaleX,
                    sourceTop + block.rectInBitmap.top * scaleY,
                    block.rectInBitmap.right * scaleX,
                    sourceTop + block.rectInBitmap.bottom * scaleY
            );
            float[] globalQuad = mapQuadToSource(block, scaleX, scaleY, sourceTop);
            target.add(new OcrTextBlock(
                    block.text,
                    globalRect,
                    block.score,
                    globalQuad,
                    block.tokenBoxesInLine,
                    block.tokenUtf16Starts,
                    block.tokenUtf16Ends
            ));
        }
    }

    private static float[] mapQuadToSource(
            OcrTextBlock block,
            float scaleX,
            float scaleY,
            int sourceTop
    ) {
        if (!block.hasValidQuad()) return null;
        float[] src = block.quadInBitmap;
        float[] mapped = new float[8];
        for (int p = 0; p < 4; p++) {
            mapped[p * 2] = src[p * 2] * scaleX;
            mapped[p * 2 + 1] = sourceTop + src[p * 2 + 1] * scaleY;
        }
        return mapped;
    }

    private static String formatPageResult(OcrPageResult result, boolean detectMultiColumnLayout) {
        if (result == null) return null;
        PdfPageIndex pageIndex = PdfPageIndexFactory.fromOcr(
                0,
                result,
                detectMultiColumnLayout,
                MULTI_COLUMN_MIN_GAP_RATIO
        );
        if (pageIndex != null) {
            List<OcrParagraphFormatter.TextLine> lines = extractVisualLines(pageIndex);
            if (!lines.isEmpty()) {
                String formatted = OcrParagraphFormatter.format(lines, pageIndex.pageWidth);
                if (!formatted.isEmpty()) return formatted;
            }
            return pageIndex.originalText != null ? pageIndex.originalText.trim() : "";
        }
        return result.fullText != null ? result.fullText.trim() : "";
    }

    private static List<OcrParagraphFormatter.TextLine> extractVisualLines(PdfPageIndex pageIndex) {
        List<OcrParagraphFormatter.TextLine> lines = new ArrayList<>();
        if (pageIndex.tokens.isEmpty()) return lines;

        StringBuilder lineText = new StringBuilder();
        List<OcrParagraphFormatter.TokenBox> lineTokens = new ArrayList<>();
        int currentLineIndex = -1;
        boolean pendingSpace = false;

        for (PdfTextToken token : pageIndex.tokens) {
            if (token == null) continue;
            if (token.isLineBreak()) {
                flushVisualLine(lines, lineText, lineTokens);
                pendingSpace = false;
                currentLineIndex = -1;
                continue;
            }
            if (currentLineIndex >= 0 && token.lineIndex != currentLineIndex) {
                flushVisualLine(lines, lineText, lineTokens);
                pendingSpace = false;
            }
            currentLineIndex = token.lineIndex;

            if (token.isWhitespace()) {
                if (lineText.length() > 0) {
                    lineText.append(token.text);
                    pendingSpace = true;
                }
                continue;
            }

            lineText.append(token.text);
            if (token.hasRect()) {
                RectF r = token.rectInPdfPoint;
                float top = pageIndex.pageHeight - r.bottom;
                float bottom = pageIndex.pageHeight - r.top;
                lineTokens.add(new OcrParagraphFormatter.TokenBox(
                        token.text, r.left, top, r.right, bottom, pendingSpace
                ));
            }
            pendingSpace = false;
        }
        flushVisualLine(lines, lineText, lineTokens);
        return lines;
    }

    private static void flushVisualLine(
            List<OcrParagraphFormatter.TextLine> target,
            StringBuilder lineText,
            List<OcrParagraphFormatter.TokenBox> lineTokens
    ) {
        if (lineText.length() > 0) {
            OcrParagraphFormatter.TextLine built =
                    OcrParagraphFormatter.TextLine.fromTokens(lineText.toString(), lineTokens);
            if (built != null) target.add(built);
        }
        lineText.setLength(0);
        lineTokens.clear();
    }

    private static ImageMeta readImageMeta(Context context, Uri uri) {
        int width = 0;
        int height = 0;
        int orientation = ExifInterface.ORIENTATION_NORMAL;

        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(is, null, options);
                width = options.outWidth;
                height = options.outHeight;
            }
        } catch (Exception ignored) {
        }

        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is != null) {
                ExifInterface exif = new ExifInterface(is);
                orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                );
            }
        } catch (Exception ignored) {
        }

        return new ImageMeta(width, height, orientation);
    }

    private static Bitmap decodeNormalBitmapSafely(Context context, Uri uri, ImageMeta meta) {
        if (meta == null || !meta.hasValidSize()) return null;

        int sample = 1;
        while (true) {
            long pixels = ((long) meta.rawWidth / sample) * ((long) meta.rawHeight / sample);
            if (pixels <= NORMAL_IMAGE_PIXEL_BUDGET) break;

            int next = sample * 2;
            int nextShortSide = Math.min(meta.rawWidth / next, meta.rawHeight / next);
            if (nextShortSide < LONG_IMAGE_MIN_DECODED_WIDTH) break;
            sample = next;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, sample);
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            return BitmapFactory.decodeStream(is, null, options);
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "图片解码失败: " + uri, e);
            return null;
        }
    }

    private static Bitmap applyExifOrientation(Bitmap src, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setScale(1f, -1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(270f);
                break;
            default:
                return src;
        }

        Bitmap transformed = Bitmap.createBitmap(
                src, 0, 0, src.getWidth(), src.getHeight(), matrix, true
        );
        return transformed != null ? transformed : src;
    }

    private static int calculateLongImageSampleSize(int sourceWidth) {
        int sample = 1;
        while (sourceWidth / sample > LONG_IMAGE_MAX_DECODED_WIDTH) {
            int next = sample * 2;
            if (sourceWidth / next < LONG_IMAGE_MIN_DECODED_WIDTH) break;
            sample = next;
        }
        return Math.max(1, sample);
    }

    private static float calculateEnhanceScale(int width) {
        if (width <= 0 || width >= TARGET_MIN_WIDTH) return 1f;
        return Math.min(2.5f, Math.max(1f, (float) TARGET_MIN_WIDTH / width));
    }

    private static Bitmap scaleBitmap(Bitmap src, float scale) {
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private static void recycleIfDistinct(Bitmap candidate, Bitmap ref1, Bitmap ref2) {
        if (candidate != null && candidate != ref1 && candidate != ref2 && !candidate.isRecycled()) {
            candidate.recycle();
        }
    }

    private static void recycleIfNecessary(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private static final class ImageMeta {
        final int rawWidth;
        final int rawHeight;
        final int exifOrientation;

        ImageMeta(int rawWidth, int rawHeight, int exifOrientation) {
            this.rawWidth = rawWidth;
            this.rawHeight = rawHeight;
            this.exifOrientation = exifOrientation;
        }

        boolean hasValidSize() {
            return rawWidth > 0 && rawHeight > 0;
        }

        boolean isUpright() {
            return exifOrientation == ExifInterface.ORIENTATION_NORMAL
                    || exifOrientation == ExifInterface.ORIENTATION_UNDEFINED
                    || exifOrientation == 0;
        }
    }
}
