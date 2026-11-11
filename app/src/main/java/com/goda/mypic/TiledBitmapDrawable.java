package com.goda.mypic;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 BitmapRegionDecoder 的分块高清图片 Drawable，用于在不修改 PhotoView 交互逻辑的前提下：
 * 1. 支持超大分辨率 / 超大体积图片（分块解码，单块 <= 1280px，彻底规避 GL_MAX_TEXTURE_SIZE 纯黑与 OOM）；
 * 2. 保证长图按原始宽度（sampleSize = 1）分块显示，完全不糊；
 * 3. 首屏仅解码小尺寸预览底图 + 可见区域切片，保证滑动速率与低内存占用。
 */
public class TiledBitmapDrawable extends Drawable {

    private static final String TAG = "TiledBitmapDrawable";

    static final int TILE_DECODED_SIZE = 1280;
    private static final int BASE_MAX_SIDE = 2048;
    private static final int TILE_CACHE_KB = Math.min(48 * 1024,
            Math.max(16 * 1024, (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L)));
    private static final Bitmap.Config PREFERRED_CONFIG =
            Runtime.getRuntime().maxMemory() >= 192L * 1024L * 1024L
                    ? Bitmap.Config.ARGB_8888
                    : Bitmap.Config.RGB_565;

    private static final ExecutorService TILE_EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    public static class OpenResult {
        public enum Kind {
            TILED,
            USE_GLIDE,
            FAILED
        }

        public final Kind kind;
        @Nullable public final TiledBitmapDrawable drawable;
        public final int orientedWidth;
        public final int orientedHeight;
        public final boolean animated;

        private OpenResult(Kind kind, @Nullable TiledBitmapDrawable drawable,
                           int orientedWidth, int orientedHeight, boolean animated) {
            this.kind = kind;
            this.drawable = drawable;
            this.orientedWidth = orientedWidth;
            this.orientedHeight = orientedHeight;
            this.animated = animated;
        }

        public static OpenResult tiled(@NonNull TiledBitmapDrawable drawable) {
            return new OpenResult(Kind.TILED, drawable,
                    drawable.orientedWidth, drawable.orientedHeight, false);
        }

        public static OpenResult useGlide(int orientedWidth, int orientedHeight, boolean animated) {
            return new OpenResult(Kind.USE_GLIDE, null, orientedWidth, orientedHeight, animated);
        }

        public static OpenResult failed() {
            return new OpenResult(Kind.FAILED, null, 0, 0, false);
        }
    }

    private static final class TileKey {
        final int sampleSize;
        final int col;
        final int row;

        TileKey(int sampleSize, int col, int row) {
            this.sampleSize = sampleSize;
            this.col = col;
            this.row = row;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TileKey)) return false;
            TileKey other = (TileKey) o;
            return sampleSize == other.sampleSize && col == other.col && row == other.row;
        }

        @Override
        public int hashCode() {
            return Objects.hash(sampleSize, col, row);
        }
    }

    private final int rawWidth;
    private final int rawHeight;
    private final int orientedWidth;
    private final int orientedHeight;
    private final int baseSampleSize;
    @Nullable private final Matrix orientationMatrix;
    @Nullable private final Matrix inverseOrientationMatrix;

    private final Object decoderLock = new Object();
    @Nullable private ParcelFileDescriptor pfd;
    @Nullable private BitmapRegionDecoder decoder;

    @Nullable private volatile Bitmap baseBitmap;
    private final Rect fullRawRect;
    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    private final LruCache<TileKey, Bitmap> tileCache = new LruCache<TileKey, Bitmap>(TILE_CACHE_KB) {
        @Override
        protected int sizeOf(@NonNull TileKey key, @NonNull Bitmap value) {
            return Math.max(1, value.getAllocationByteCount() / 1024);
        }
    };
    private final Set<TileKey> pendingTiles = ConcurrentHashMap.newKeySet();
    private final Set<TileKey> failedTiles = ConcurrentHashMap.newKeySet();

    private volatile boolean closed = false;
    private WeakReference<ImageView> imageViewRef = new WeakReference<>(null);

    // 复用临时对象，避免 draw() 产生 GC 抖动
    private final Rect tmpClipOriented = new Rect();
    private final RectF tmpRectF = new RectF();
    private final Rect tmpVisibleRaw = new Rect();
    private final Rect tmpTileRaw = new Rect();
    private final Rect tmpSrcRect = new Rect();
    private final float[] tmpMatrixValues = new float[9];

    private volatile int latestDesiredSample = 1;
    private volatile int visLeft, visTop, visRight, visBottom;

    private TiledBitmapDrawable(int rawWidth,
                                int rawHeight,
                                int orientedWidth,
                                int orientedHeight,
                                int baseSampleSize,
                                @Nullable Matrix orientationMatrix,
                                @Nullable Matrix inverseOrientationMatrix,
                                @Nullable ParcelFileDescriptor pfd,
                                @NonNull BitmapRegionDecoder decoder,
                                @NonNull Bitmap baseBitmap) {
        this.rawWidth = rawWidth;
        this.rawHeight = rawHeight;
        this.orientedWidth = orientedWidth;
        this.orientedHeight = orientedHeight;
        this.baseSampleSize = baseSampleSize;
        this.orientationMatrix = orientationMatrix;
        this.inverseOrientationMatrix = inverseOrientationMatrix;
        this.pfd = pfd;
        this.decoder = decoder;
        this.baseBitmap = baseBitmap;
        this.fullRawRect = new Rect(0, 0, rawWidth, rawHeight);
    }

    public void attachImageView(@Nullable ImageView imageView) {
        this.imageViewRef = new WeakReference<>(imageView);
    }

    @Override
    public int getIntrinsicWidth() {
        return orientedWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return orientedHeight;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Bitmap base = baseBitmap;
        if (closed || base == null || base.isRecycled()) return;

        if (!canvas.getClipBounds(tmpClipOriented)) return;
        mapOrientedToRaw(tmpClipOriented, tmpVisibleRaw);
        if (!tmpVisibleRaw.intersect(0, 0, rawWidth, rawHeight)) return;

        float displayScale = computeDisplayScale(canvas, tmpClipOriented);
        int desiredSample = pickSampleSize(displayScale, baseSampleSize);

        latestDesiredSample = desiredSample;
        visLeft = tmpVisibleRaw.left;
        visTop = tmpVisibleRaw.top;
        visRight = tmpVisibleRaw.right;
        visBottom = tmpVisibleRaw.bottom;

        canvas.save();
        if (orientationMatrix != null) {
            canvas.concat(orientationMatrix);
        }

        if (desiredSample >= baseSampleSize) {
            canvas.drawBitmap(base, null, fullRawRect, tilePaint);
            canvas.restore();
            return;
        }

        int tileSpan = TILE_DECODED_SIZE * desiredSample;
        int startCol = Math.max(0, tmpVisibleRaw.left / tileSpan);
        int endCol = Math.min((rawWidth - 1) / tileSpan, Math.max(0, tmpVisibleRaw.right - 1) / tileSpan);
        int startRow = Math.max(0, tmpVisibleRaw.top / tileSpan);
        int endRow = Math.min((rawHeight - 1) / tileSpan, Math.max(0, tmpVisibleRaw.bottom - 1) / tileSpan);

        int baseW = base.getWidth();
        int baseH = base.getHeight();
        float baseScaleX = (float) baseW / rawWidth;
        float baseScaleY = (float) baseH / rawHeight;

        for (int row = startRow; row <= endRow; row++) {
            int top = row * tileSpan;
            int bottom = Math.min(rawHeight, top + tileSpan);
            for (int col = startCol; col <= endCol; col++) {
                int left = col * tileSpan;
                int right = Math.min(rawWidth, left + tileSpan);
                tmpTileRaw.set(left, top, right, bottom);

                TileKey key = new TileKey(desiredSample, col, row);
                Bitmap tileBmp = tileCache.get(key);
                if (tileBmp != null && !tileBmp.isRecycled()) {
                    canvas.drawBitmap(tileBmp, null, tmpTileRaw, tilePaint);
                    continue;
                }

                // 优先尝试用缓存中的上一级低清切片垫底，其次使用底图局部裁剪，避免任何空白或透明重绘叠色
                if (!drawCoarserTileFallback(canvas, desiredSample, col, row, tmpTileRaw)) {
                    int srcR = Math.max(1, Math.min(baseW, (int) Math.ceil(right * baseScaleX)));
                    int srcB = Math.max(1, Math.min(baseH, (int) Math.ceil(bottom * baseScaleY)));
                    int srcL = Math.max(0, Math.min(srcR - 1, (int) Math.floor(left * baseScaleX)));
                    int srcT = Math.max(0, Math.min(srcB - 1, (int) Math.floor(top * baseScaleY)));
                    tmpSrcRect.set(srcL, srcT, srcR, srcB);
                    canvas.drawBitmap(base, tmpSrcRect, tmpTileRaw, tilePaint);
                }

                scheduleTileDecode(key);
            }
        }

        // 为长图或大图预取上下/左右相邻切片，确保滑动顺滑
        prefetchAdjacentTiles(desiredSample, startCol, endCol, startRow, endRow, tileSpan);

        canvas.restore();
    }

    private boolean drawCoarserTileFallback(Canvas canvas, int desiredSample, int col, int row, Rect targetRaw) {
        int coarserSample = desiredSample * 2;
        if (coarserSample >= baseSampleSize) return false;

        int cCol = col / 2;
        int cRow = row / 2;
        TileKey coarserKey = new TileKey(coarserSample, cCol, cRow);
        Bitmap coarserBmp = tileCache.get(coarserKey);
        if (coarserBmp == null || coarserBmp.isRecycled()) return false;

        int cSpan = TILE_DECODED_SIZE * coarserSample;
        int cLeft = cCol * cSpan;
        int cTop = cRow * cSpan;
        int cRight = Math.min(rawWidth, cLeft + cSpan);
        int cBottom = Math.min(rawHeight, cTop + cSpan);
        int cWidth = Math.max(1, cRight - cLeft);
        int cHeight = Math.max(1, cBottom - cTop);

        int bmpW = coarserBmp.getWidth();
        int bmpH = coarserBmp.getHeight();
        tmpSrcRect.set(
                Math.max(0, (int) Math.floor((targetRaw.left - cLeft) * (double) bmpW / cWidth)),
                Math.max(0, (int) Math.floor((targetRaw.top - cTop) * (double) bmpH / cHeight)),
                Math.min(bmpW, Math.max(1, (int) Math.ceil((targetRaw.right - cLeft) * (double) bmpW / cWidth))),
                Math.min(bmpH, Math.max(1, (int) Math.ceil((targetRaw.bottom - cTop) * (double) bmpH / cHeight)))
        );
        if (tmpSrcRect.right <= tmpSrcRect.left || tmpSrcRect.bottom <= tmpSrcRect.top) {
            return false;
        }
        canvas.drawBitmap(coarserBmp, tmpSrcRect, targetRaw, tilePaint);
        return true;
    }

    private void prefetchAdjacentTiles(int sampleSize, int startCol, int endCol,
                                       int startRow, int endRow, int tileSpan) {
        int maxCol = (rawWidth - 1) / tileSpan;
        int maxRow = (rawHeight - 1) / tileSpan;
        if (endRow + 1 <= maxRow) {
            for (int col = startCol; col <= endCol; col++) {
                scheduleTileDecode(new TileKey(sampleSize, col, endRow + 1));
            }
        }
        if (startRow - 1 >= 0) {
            for (int col = startCol; col <= endCol; col++) {
                scheduleTileDecode(new TileKey(sampleSize, col, startRow - 1));
            }
        }
        if (endCol + 1 <= maxCol) {
            for (int row = startRow; row <= endRow; row++) {
                scheduleTileDecode(new TileKey(sampleSize, endCol + 1, row));
            }
        }
    }

    private void scheduleTileDecode(TileKey key) {
        if (closed || failedTiles.contains(key)) return;
        if (tileCache.get(key) != null) return;
        if (!pendingTiles.add(key)) return;

        TILE_EXECUTOR.execute(() -> {
            try {
                if (closed || !isTileRelevant(key)) return;

                Bitmap decoded = decodeSingleTile(key);
                if (decoded != null) {
                    if (closed) return;
                    tileCache.put(key, decoded);
                    MAIN_HANDLER.post(() -> {
                        if (!closed) invalidateSelf();
                    });
                } else {
                    failedTiles.add(key);
                }
            } catch (Throwable t) {
                failedTiles.add(key);
                Log.w(TAG, "切片解码失败: " + key.sampleSize + " (" + key.col + "," + key.row + ")", t);
            } finally {
                pendingTiles.remove(key);
            }
        });
    }

    private boolean isTileRelevant(TileKey key) {
        if (key.sampleSize != latestDesiredSample) return false;
        int tileSpan = TILE_DECODED_SIZE * key.sampleSize;
        int left = key.col * tileSpan;
        int top = key.row * tileSpan;
        int right = Math.min(rawWidth, left + tileSpan);
        int bottom = Math.min(rawHeight, top + tileSpan);

        // 允许周围 1 个 tileSpan 的预取范围，快速滑动时丢弃早已划过的陈旧任务
        return right >= visLeft - tileSpan && left <= visRight + tileSpan
                && bottom >= visTop - tileSpan && top <= visBottom + tileSpan;
    }

    @Nullable
    private Bitmap decodeSingleTile(TileKey key) {
        int tileSpan = TILE_DECODED_SIZE * key.sampleSize;
        int left = key.col * tileSpan;
        int top = key.row * tileSpan;
        int right = Math.min(rawWidth, left + tileSpan);
        int bottom = Math.min(rawHeight, top + tileSpan);
        if (left >= right || top >= bottom) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = key.sampleSize;
        opts.inPreferredConfig = PREFERRED_CONFIG;

        synchronized (decoderLock) {
            if (closed || decoder == null || decoder.isRecycled()) return null;
            return decoder.decodeRegion(new Rect(left, top, right, bottom), opts);
        }
    }

    private float computeDisplayScale(Canvas canvas, Rect clipOriented) {
        ImageView iv = imageViewRef.get();
        if (iv != null) {
            iv.getImageMatrix().getValues(tmpMatrixValues);
            float scale = (float) Math.hypot(tmpMatrixValues[Matrix.MSCALE_X], tmpMatrixValues[Matrix.MSKEW_Y]);
            if (scale > 0.0001f) {
                return scale;
            }
        }
        int cw = canvas.getWidth();
        if (cw > 0 && clipOriented.width() > 0) {
            return (float) cw / clipOriented.width();
        }
        return 1.0f;
    }

    private void mapOrientedToRaw(Rect oriented, Rect outRaw) {
        if (inverseOrientationMatrix == null) {
            outRaw.set(oriented);
            return;
        }
        tmpRectF.set(oriented);
        inverseOrientationMatrix.mapRect(tmpRectF);
        tmpRectF.roundOut(outRaw);
    }

    @Override
    public void setAlpha(int alpha) {
        tilePaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        tilePaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    public void trimTiles() {
        tileCache.evictAll();
        pendingTiles.clear();
    }

    public void close() {
        if (closed) return;
        closed = true;
        baseBitmap = null;
        tileCache.evictAll();
        pendingTiles.clear();
        failedTiles.clear();

        final BitmapRegionDecoder dec = decoder;
        final ParcelFileDescriptor fd = pfd;
        decoder = null;
        pfd = null;
        if (dec != null || fd != null) {
            TILE_EXECUTOR.execute(() -> {
                synchronized (decoderLock) {
                    if (dec != null && !dec.isRecycled()) {
                        try { dec.recycle(); } catch (Exception ignored) {}
                    }
                    if (fd != null) {
                        try { fd.close(); } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    // ================= 静态工具方法（便于复用与测试） =================

    static int pickSampleSize(float displayScale, int maxSampleSize) {
        int clampedMax = Math.max(1, maxSampleSize);
        int sample = 1;
        while (sample * 2 <= clampedMax && (displayScale * sample * 2) <= 1.0f) {
            sample *= 2;
        }
        return sample;
    }

    static int calculateBaseSampleSize(int width, int height, int maxSide) {
        int sample = 1;
        int longest = Math.max(width, height);
        int shortest = Math.max(1, Math.min(width, height));
        while (longest / sample > maxSide && shortest / (sample * 2) >= 16) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private static boolean isAnimatedImage(ContentResolver resolver, Uri uri) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return false;
            byte[] header = new byte[24];
            int total = 0;
            while (total < header.length) {
                int read = is.read(header, total, header.length - total);
                if (read <= 0) break;
                total += read;
            }
            if (total >= 3 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F') {
                return true;
            }
            // WebP 动图在 VP8X chunk 的第 20 字节包含 ANIM flag (0x02)
            if (total >= 21
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P'
                    && header[12] == 'V' && header[13] == 'P' && header[14] == '8' && header[15] == 'X') {
                return (header[20] & 0x02) != 0;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static int readExifOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return ExifInterface.ORIENTATION_NORMAL;
            ExifInterface exif = new ExifInterface(is);
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    @Nullable
    private static Matrix buildOrientationMatrix(int orientation, int rawW, int rawH) {
        Matrix m = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                m.setScale(-1f, 1f);
                m.postTranslate(rawW, 0f);
                return m;
            case ExifInterface.ORIENTATION_ROTATE_180:
                m.setRotate(180f);
                m.postTranslate(rawW, rawH);
                return m;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                m.setScale(1f, -1f);
                m.postTranslate(0f, rawH);
                return m;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                m.setScale(-1f, 1f);
                m.postRotate(270f);
                return m;
            case ExifInterface.ORIENTATION_ROTATE_90:
                m.setRotate(90f);
                m.postTranslate(rawH, 0f);
                return m;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                m.setScale(-1f, 1f);
                m.postRotate(90f);
                m.postTranslate(rawH, rawW);
                return m;
            case ExifInterface.ORIENTATION_ROTATE_270:
                m.setRotate(270f);
                m.postTranslate(0f, rawW);
                return m;
            default:
                return null;
        }
    }

    private static boolean swapsDimensions(int orientation) {
        return orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE
                || orientation == ExifInterface.ORIENTATION_ROTATE_270;
    }

    /**
     * 在后台线程探测图片格式、真实尺寸与方向：
     * - 若适合 Glide 直出（动图、或未超出纹理/像素预算的小图），返回 USE_GLIDE；
     * - 否则打开 BitmapRegionDecoder 与底图（若为长图顺便预解码首屏顶部切片），返回 TILED。
     */
    public static OpenResult open(@NonNull ContentResolver resolver,
                                  @NonNull Uri uri,
                                  int safeMaxTex,
                                  long safeSinglePixels,
                                  long fileSize,
                                  long safeSingleFileBytes,
                                  @NonNull AtomicBoolean cancelled) {
        if (cancelled.get()) return OpenResult.failed();

        if (isAnimatedImage(resolver, uri)) {
            return OpenResult.useGlide(0, 0, true);
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return OpenResult.failed();
            BitmapFactory.decodeStream(is, null, bounds);
        } catch (Exception e) {
            return OpenResult.failed();
        }

        int rawW = bounds.outWidth;
        int rawH = bounds.outHeight;
        if (rawW <= 0 || rawH <= 0) {
            return OpenResult.useGlide(0, 0, false);
        }

        if (cancelled.get()) return OpenResult.failed();

        int orientation = readExifOrientation(resolver, uri);
        boolean swapped = swapsDimensions(orientation);
        int orientedW = swapped ? rawH : rawW;
        int orientedH = swapped ? rawW : rawH;

        long totalPixels = (long) orientedW * orientedH;
        boolean needsTiling = orientedW > safeMaxTex
                || orientedH > safeMaxTex
                || totalPixels > safeSinglePixels
                || fileSize > safeSingleFileBytes;

        if (!needsTiling) {
            return OpenResult.useGlide(orientedW, orientedH, false);
        }

        if (cancelled.get()) return OpenResult.failed();

        ParcelFileDescriptor pfd = null;
        BitmapRegionDecoder decoder = null;
        try {
            try {
                pfd = resolver.openFileDescriptor(uri, "r");
                if (pfd != null) {
                    decoder = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
                }
            } catch (Exception ignored) {
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored2) {}
                    pfd = null;
                }
            }

            if (decoder == null) {
                try (InputStream is = resolver.openInputStream(uri)) {
                    if (is != null) {
                        decoder = BitmapRegionDecoder.newInstance(is, false);
                    }
                } catch (IOException ignored) {}
            }

            if (decoder == null) {
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                return OpenResult.useGlide(orientedW, orientedH, false);
            }

            int baseCap = Math.min(BASE_MAX_SIDE, safeMaxTex);
            int baseSample = calculateBaseSampleSize(rawW, rawH, baseCap);
            Bitmap base = decodeBaseBitmap(resolver, uri, decoder, rawW, rawH, baseSample, safeMaxTex);
            if (base == null || cancelled.get()) {
                if (base != null && !base.isRecycled()) base.recycle();
                try { decoder.recycle(); } catch (Exception ignored) {}
                if (pfd != null) {
                    try { pfd.close(); } catch (Exception ignored) {}
                }
                return cancelled.get()
                        ? OpenResult.failed()
                        : OpenResult.useGlide(orientedW, orientedH, false);
            }

            Matrix orientMatrix = buildOrientationMatrix(orientation, rawW, rawH);
            Matrix inverseMatrix = null;
            if (orientMatrix != null) {
                inverseMatrix = new Matrix();
                if (!orientMatrix.invert(inverseMatrix)) {
                    inverseMatrix = null;
                }
            }

            TiledBitmapDrawable drawable = new TiledBitmapDrawable(
                    rawW, rawH, orientedW, orientedH, baseSample,
                    orientMatrix, inverseMatrix, pfd, decoder, base
            );

            // 长图：首帧即可精确显示，提前解码顶部 1~2 个 sample=1 切片，零闪烁直达原生清晰度
            if (orientedH > orientedW * 1.5f && baseSample > 1) {
                drawable.preDecodeTopLongImageTiles(cancelled);
            }

            if (cancelled.get()) {
                drawable.close();
                return OpenResult.failed();
            }

            return OpenResult.tiled(drawable);
        } catch (Throwable t) {
            Log.w(TAG, "初始化分块解码失败，回退安全 Glide", t);
            if (decoder != null) {
                try { decoder.recycle(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
            return OpenResult.useGlide(orientedW, orientedH, false);
        }
    }

    @Nullable
    private static Bitmap decodeBaseBitmap(ContentResolver resolver,
                                           Uri uri,
                                           BitmapRegionDecoder decoder,
                                           int rawW,
                                           int rawH,
                                           int baseSample,
                                           int safeMaxTex) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = baseSample;
        opts.inPreferredConfig = PREFERRED_CONFIG;

        Bitmap base = null;
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is != null) {
                base = BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Throwable ignored) {}

        if (base == null) {
            try {
                base = decoder.decodeRegion(new Rect(0, 0, rawW, rawH), opts);
            } catch (Throwable ignored) {}
        }

        if (base != null && (base.getWidth() > safeMaxTex || base.getHeight() > safeMaxTex)) {
            int cap = Math.min(BASE_MAX_SIDE, safeMaxTex);
            float scale = Math.min((float) cap / base.getWidth(),
                    (float) cap / base.getHeight());
            int w = Math.max(1, Math.round(base.getWidth() * scale));
            int h = Math.max(1, Math.round(base.getHeight() * scale));
            Bitmap shrunk = Bitmap.createScaledBitmap(base, w, h, true);
            if (shrunk != base) {
                base.recycle();
                base = shrunk;
            }
        }
        return base;
    }

    private void preDecodeTopLongImageTiles(AtomicBoolean cancelled) {
        int sample = pickSampleSize(Math.min(1.0f, 1440.0f / Math.max(1, orientedWidth)), baseSampleSize);
        if (sample >= baseSampleSize) return;

        int tileSpan = TILE_DECODED_SIZE * sample;
        Rect orientedTop = new Rect(0, 0, orientedWidth, Math.min(orientedHeight, orientedWidth * 2));
        Rect rawTop = new Rect();
        mapOrientedToRaw(orientedTop, rawTop);
        if (!rawTop.intersect(0, 0, rawWidth, rawHeight)) return;

        int startCol = Math.max(0, rawTop.left / tileSpan);
        int endCol = Math.min((rawWidth - 1) / tileSpan, Math.max(0, rawTop.right - 1) / tileSpan);
        int startRow = Math.max(0, rawTop.top / tileSpan);
        int endRow = Math.min((rawHeight - 1) / tileSpan, Math.max(0, rawTop.bottom - 1) / tileSpan);

        int decodedCount = 0;
        for (int row = startRow; row <= endRow && decodedCount < 4; row++) {
            for (int col = startCol; col <= endCol && decodedCount < 4; col++) {
                if (cancelled.get() || closed) return;
                TileKey key = new TileKey(sample, col, row);
                Bitmap bmp = decodeSingleTile(key);
                if (bmp != null) {
                    tileCache.put(key, bmp);
                    decodedCount++;
                }
            }
        }
    }
}
