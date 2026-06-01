package com.goda.mypic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.ParcelFileDescriptor;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

public class SimilarityEngine {

    public interface ScanCallback {
        void onProgress(int current, int total);
        void onComplete(List<SimilarGroup> result, long timeTakenMs);
    }

    private static class Fingerprint {
        MediaItem item;
        long[] hash256;
        int avgR, avgG, avgB;
        float aspectRatio; // 🚨 新增：记录图片的真实长宽比例
        boolean isGrouped = false;
    }

    public static void startScan(Context context, List<MediaItem> images, ExecutorService executor, ScanCallback callback) {
        // 🚨 致命 Bug 修复：必须把所有繁重的计算任务扔进传进来的后台线程池里！
        // 绝不能占用主线程（UI 线程），否则立刻触发 ANR 卡死！
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();

            // 1. 并发提取高精度指纹
            List<Fingerprint> validPrints = java.util.Collections.synchronizedList(new ArrayList<>());
            AtomicInteger progress = new AtomicInteger(0);
            int total = images.size();

            // 使用并行流进一步压榨多核性能
            images.parallelStream().forEach(item -> {
                Fingerprint fp = generateHighPrecisionFingerprint(context, item);
                if (fp != null) {
                    fp.item = item;
                    validPrints.add(fp);
                }
                int cur = progress.incrementAndGet();
                // 降低回调频率，防止高频通讯导致 UI 线程卡顿
                if (cur % 5 == 0 || cur == total) {
                    callback.onProgress(cur, total);
                }
            });

            // 2. O(N²) 两两精准聚类
            List<SimilarGroup> groups = new ArrayList<>();
            Fingerprint[] arr = validPrints.toArray(new Fingerprint[0]);
            int n = arr.length;

            for (int i = 0; i < n; i++) {
                if (arr[i].isGrouped) continue;

                SimilarGroup currentGroup = null;
                float totalSim = 0;
                int matchCount = 0;

                for (int j = i + 1; j < n; j++) {
                    if (arr[j].isGrouped) continue;

                    float similarity = calculateSimilarity(arr[i], arr[j]);

                    // 严苛阈值：90% 以上
                    if (similarity >= 90.0f) {
                        if (currentGroup == null) {
                            currentGroup = new SimilarGroup();
                            if (currentGroup.similarItems == null) {
                                currentGroup.similarItems = new ArrayList<>();
                            }
                            currentGroup.similarItems.add(arr[i].item);
                            arr[i].isGrouped = true;
                        }
                        currentGroup.similarItems.add(arr[j].item);
                        arr[j].isGrouped = true;

                        totalSim += similarity;
                        matchCount++;
                    }
                }

                if (currentGroup != null) {
                    currentGroup.averageSimilarity = totalSim / matchCount;
                    groups.add(currentGroup);
                }
            }

            // 按照群组大小和相似度降序排列
            groups.sort((g1, g2) -> {
                if (g1.similarItems.size() != g2.similarItems.size()) {
                    return Integer.compare(g2.similarItems.size(), g1.similarItems.size());
                }
                return Float.compare((float) g2.averageSimilarity, (float) g1.averageSimilarity);
            });

            long timeTaken = System.currentTimeMillis() - startTime;
            // 运算完毕，把结果传回给 MainActivity
            callback.onComplete(groups, timeTaken);
        });
    }

    private static Fingerprint generateHighPrecisionFingerprint(Context context, MediaItem item) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(item.uri, "r")) {
            if (pfd == null) return null;
            FileDescriptor fd = pfd.getFileDescriptor();

            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(fd, null, opt);

            // 🚨 新增：计算并记录最原始的物理长宽比
            float aspectRatio = 1.0f;
            if (opt.outHeight > 0) {
                aspectRatio = (float) opt.outWidth / (float) opt.outHeight;
            }

            opt.inSampleSize = calculateInSampleSize(opt, 68, 64);
            opt.inJustDecodeBounds = false;
            opt.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp = BitmapFactory.decodeFileDescriptor(fd, null, opt);
            if (bmp == null) return null;

            Bitmap scaled = Bitmap.createScaledBitmap(bmp, 17, 16, true);
            bmp.recycle();

            int width = scaled.getWidth();
            int height = scaled.getHeight();
            int[] pixels = new int[width * height];
            scaled.getPixels(pixels, 0, width, 0, 0, width, height);
            scaled.recycle();

            Fingerprint fp = new Fingerprint();
            fp.aspectRatio = aspectRatio; // 🚨 将长宽比存入指纹
            fp.hash256 = new long[4];

            long rSum = 0, gSum = 0, bSum = 0;
            int bitIndex = 0;

            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width - 1; x++) {
                    int pixelLeft = pixels[y * width + x];
                    int pixelRight = pixels[y * width + (x + 1)];

                    int grayLeft = (Color.red(pixelLeft) * 30 + Color.green(pixelLeft) * 59 + Color.blue(pixelLeft) * 11) / 100;
                    int grayRight = (Color.red(pixelRight) * 30 + Color.green(pixelRight) * 59 + Color.blue(pixelRight) * 11) / 100;

                    if (grayLeft > grayRight) {
                        int arrayIdx = bitIndex / 64;
                        int bitOffset = bitIndex % 64;
                        fp.hash256[arrayIdx] |= (1L << bitOffset);
                    }
                    bitIndex++;
                }
            }

            for (int p : pixels) {
                rSum += Color.red(p);
                gSum += Color.green(p);
                bSum += Color.blue(p);
            }
            int totalPixels = width * height;
            fp.avgR = (int) (rSum / totalPixels);
            fp.avgG = (int) (gSum / totalPixels);
            fp.avgB = (int) (bSum / totalPixels);

            return fp;
        } catch (Exception e) {
            return null;
        }
    }

    private static float calculateSimilarity(Fingerprint f1, Fingerprint f2) {
        // 🚨 1. 长宽比绝对防火墙：比例相差超过 10% 绝对不是同一张冗余图，直接秒杀！(极速提升)
        if (Math.abs(f1.aspectRatio - f2.aspectRatio) > 0.1f) {
            return 0f;
        }

        // 2. 色彩防火墙：应对微信轻微的变色压缩，将容忍度从 50 放宽到 60
        double rDiff = f1.avgR - f2.avgR;
        double gDiff = f1.avgG - f2.avgG;
        double bDiff = f1.avgB - f2.avgB;
        double colorDist = Math.sqrt(rDiff*rDiff + gDiff*gDiff + bDiff*bDiff);

        if (colorDist > 60) return 0f;

        // 3. 结构相似度：汉明距离
        int hammingDistance = 0;
        for (int i = 0; i < 4; i++) {
            hammingDistance += Long.bitCount(f1.hash256[i] ^ f2.hash256[i]);
        }

        float structuralSimilarity = (256 - hammingDistance) / 256.0f * 100.0f;
        float colorPenalty = (float) (colorDist / 60.0 * 5.0);
        return Math.max(0, structuralSimilarity - colorPenalty);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}