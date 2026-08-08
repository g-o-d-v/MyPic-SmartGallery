package com.goda.mypic;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Size;
import android.util.Log;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 相似/冗余图片扫描器 v6。
 *
 * 设计目标：
 * 1. 第一阶段只负责读取/生成图片指纹；第二阶段使用 LSH 分桶召回候选，避免
 *    64 bit BK-tree 在 22~24 大半径下退化成“几乎遍历整棵树”。
 * 2. 精判同时看 dHash / pHash / 边缘哈希、亮度结构、边缘结构和颜色分布。
 * 3. 指纹持久化缓存分页读取，结果完成后再后台落盘，避免 CursorWindow 与精判阶段资源争用。
 * 4. 聚类使用“高质量图片为锚点”的星型分组，所有成员都必须直接与锚点达到阈值，
 *    避免 A≈B、B≈C、但 A 与 C 已明显不同的链式误合并。
 */
public class SimilarityEngine {

    public interface ScanCallback {
        void onProgress(String stage, int current, int total);
        void onComplete(List<SimilarGroup> result, long timeTakenMs);
    }

    private static final int FEATURE_SIZE = 32;
    private static final int FEATURE_PIXELS = FEATURE_SIZE * FEATURE_SIZE;
    private static final int THUMBNAIL_SIZE = 128;
    private static final int CACHE_PAGE_SIZE = 256;
    private static final int PARALLEL_CANDIDATE_THRESHOLD = 48;
    private static final int MAX_PRECISION_THREADS = 3;
    private static final String TAG = "SimilarityEngine";

    // LSH 只负责“召回候选”。每个 64 bit 哈希抽取 12 个 8-bit band，
    // 包含 8 个不重叠 band + 4 个半字节错位 band，提高轻微裁剪/压缩后的召回率。
    private static final int[] LSH_BAND_OFFSETS = {
            0, 8, 16, 24, 32, 40, 48, 56,
            4, 20, 36, 52
    };
    private static final int LSH_BANDS_PER_HASH = LSH_BAND_OFFSETS.length;
    private static final int LSH_TOTAL_BANDS = LSH_BANDS_PER_HASH * 3;

    // 最终展示阈值仍保持 90%，但 90 分来自多维结构而不是单一 dHash。
    private static final float MATCH_THRESHOLD = 90.0f;
    private static final float MAX_ASPECT_RELATIVE_DIFF = 0.08f;

    private static final double[][] DCT_COS = buildDctCosTable();

    private static class Fingerprint {
        MediaItem item;
        int width;
        int height;
        long fileSize;
        float aspectRatio;
        long dHash64;
        long pHash64;
        long edgeHash64;
        byte[] luma32;
        byte[] edge32;
        byte[] colorHist64;

        // 精判阶段的预计算统计量。把 Pearson / edge cosine 所需的固定项提前算一次，
        // 避免每一对候选都反复扫描同一张 32x32 特征图来求均值/平方和。
        double lumaSum;
        double lumaCorrelationDenom;
        double edgeNorm;
        float lumaMean;

        boolean isGrouped;
    }

    /** 避免 Integer 装箱的极小 int 列表，用于 LSH 桶。 */
    private static class IntBucket {
        int[] values = new int[8];
        int size;

        void add(int value) {
            if (size >= values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = value;
        }
    }

    /**
     * 多表 LSH：
     * 36 个 band 表（3 种哈希 × 12 个 8-bit band），每张图片只进入固定 36 个小桶。
     * 查询时只访问与锚点共享至少一个 band 的图片，再用完整汉明距离做二次门控。
     *
     * 7572 张时，随机桶平均只有约 30 个元素；相比 BK-tree 大半径查询，候选规模稳定得多。
     */
    private static class LshIndex {
        final IntBucket[][] buckets = new IntBucket[LSH_TOTAL_BANDS][256];

        void add(Fingerprint fp, int index) {
            addHash(fp.dHash64, 0, index);
            addHash(fp.pHash64, LSH_BANDS_PER_HASH, index);
            addHash(fp.edgeHash64, LSH_BANDS_PER_HASH * 2, index);
        }

        private void addHash(long hash, int slotBase, int index) {
            for (int band = 0; band < LSH_BANDS_PER_HASH; band++) {
                int value = (int) ((hash >>> LSH_BAND_OFFSETS[band]) & 0xffL);
                int slot = slotBase + band;
                IntBucket bucket = buckets[slot][value];
                if (bucket == null) {
                    bucket = new IntBucket();
                    buckets[slot][value] = bucket;
                }
                bucket.add(index);
            }
        }

        /**
         * 返回被至少一个 band 命中的候选。
         * votes / touched 由调用方复用，避免每个锚点创建 HashMap<Integer,Integer>。
         */
        int collect(Fingerprint fp, int anchorIndex, byte[] votes, int[] touched) {
            int touchedCount = 0;
            touchedCount = collectHash(fp.dHash64, 0, anchorIndex, votes, touched, touchedCount);
            touchedCount = collectHash(fp.pHash64, LSH_BANDS_PER_HASH, anchorIndex, votes, touched, touchedCount);
            touchedCount = collectHash(fp.edgeHash64, LSH_BANDS_PER_HASH * 2, anchorIndex, votes, touched, touchedCount);
            return touchedCount;
        }

        private int collectHash(long hash,
                                int slotBase,
                                int anchorIndex,
                                byte[] votes,
                                int[] touched,
                                int touchedCount) {
            for (int band = 0; band < LSH_BANDS_PER_HASH; band++) {
                int value = (int) ((hash >>> LSH_BAND_OFFSETS[band]) & 0xffL);
                IntBucket bucket = buckets[slotBase + band][value];
                if (bucket == null) continue;

                for (int k = 0; k < bucket.size; k++) {
                    int j = bucket.values[k];
                    if (j <= anchorIndex) continue;
                    if (votes[j] == 0) touched[touchedCount++] = j;
                    if (votes[j] < Byte.MAX_VALUE) votes[j]++;
                }
            }
            return touchedCount;
        }
    }

    public static void startScan(Context context,
                                 List<MediaItem> images,
                                 ExecutorService executor,
                                 ScanCallback callback) {
        executor.execute(() -> {
            long startTime = System.currentTimeMillis();
            if (images == null || images.size() < 2) {
                callback.onComplete(new ArrayList<>(), 0L);
                return;
            }

            final int total = images.size();
            final ContentResolver resolver = context.getContentResolver();
            final OcrDao dao = AppDatabase.getInstance(context).ocrDao();

            // 分页读取缓存。单条指纹含 32x32 BLOB，7572 张一次 SELECT * 会超过
            // Android 默认约 2 MB CursorWindow，日志中会出现 "Window is full"。
            // 256 条/页即使包含旧版 edge32 也远低于窗口上限。
            Map<String, SimilarityFingerprintData> cacheMap = new HashMap<>();
            try {
                int offset = 0;
                while (true) {
                    List<SimilarityFingerprintData> page =
                            dao.getSimilarityFingerprintPage(CACHE_PAGE_SIZE, offset);
                    if (page == null || page.isEmpty()) break;
                    for (SimilarityFingerprintData data : page) {
                        if (data != null && data.imageUri != null) cacheMap.put(data.imageUri, data);
                    }
                    if (page.size() < CACHE_PAGE_SIZE) break;
                    offset += page.size();
                }
            } catch (Exception e) {
                Log.w(TAG, "Fingerprint cache paging failed; continue without cache", e);
                cacheMap.clear();
            }

            List<Fingerprint> fingerprints = new ArrayList<>(total);
            List<SimilarityFingerprintData> newCacheRows = Collections.synchronizedList(new ArrayList<>());

            // 独立、受控的小线程池。旧版 parallelStream 会进入公共线程池，与 App 自己的线程池抢资源。
            int workerCount = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));
            ExecutorService workers = Executors.newFixedThreadPool(workerCount);
            CompletionService<Fingerprint> completion = new ExecutorCompletionService<>(workers);

            for (MediaItem item : images) {
                SimilarityFingerprintData cached = item != null && item.uri != null
                        ? cacheMap.get(item.uri.toString()) : null;
                completion.submit(() -> {
                    Fingerprint fp = fingerprintFromCache(item, cached);
                    if (fp != null) return fp;

                    fp = generateFingerprint(resolver, item);
                    if (fp != null) {
                        newCacheRows.add(toCacheRow(fp));
                    }
                    return fp;
                });
            }

            int progressStep = Math.max(1, total / 100); // 最多约 100 次 UI 回调，而不是旧版每 5 张一次。
            try {
                for (int i = 1; i <= total; i++) {
                    try {
                        Future<Fingerprint> future = completion.take();
                        Fingerprint fp = future.get();
                        if (fp != null) fingerprints.add(fp);
                    } catch (Exception ignored) {
                        // 单张坏图只跳过这一张，不中断剩余几千张的扫描。
                    }
                    if (i == total || i % progressStep == 0) callback.onProgress("读取/生成指纹", i, total);
                }
            } finally {
                workers.shutdownNow();
            }

            // v6 不再让缓存落盘与 CPU 最重的精判阶段并行抢资源。
            // 扫描结果先算完并返回；新增指纹在结果完成后再由 App 线程池后台持久化。
            final List<SimilarityFingerprintData> rowsToWrite =
                    newCacheRows.isEmpty() ? Collections.emptyList() : new ArrayList<>(newCacheRows);

            if (fingerprints.size() < 2) {
                scheduleCacheWrite(executor, dao, rowsToWrite);
                callback.onComplete(new ArrayList<>(), System.currentTimeMillis() - startTime);
                return;
            }

            // 先让像素数更高、文件信息更完整的图片成为锚点。
            // 同一组里保留第一张通常就是更适合保留的版本，也能降低低清图当锚点造成的误判。
            fingerprints.sort((a, b) -> {
                long areaA = (long) a.width * (long) a.height;
                long areaB = (long) b.width * (long) b.height;
                int cmp = Long.compare(areaB, areaA);
                if (cmp != 0) return cmp;
                cmp = Long.compare(b.fileSize, a.fileSize);
                if (cmp != 0) return cmp;
                return Long.compare(b.item.dateAdded, a.item.dateAdded);
            });

            Fingerprint[] arr = fingerprints.toArray(new Fingerprint[0]);

            callback.onProgress("建立候选索引", 0, arr.length);
            LshIndex lshIndex = new LshIndex();
            for (int i = 0; i < arr.length; i++) {
                lshIndex.add(arr[i], i);
            }
            callback.onProgress("建立候选索引", arr.length, arr.length);

            List<SimilarGroup> groups = new ArrayList<>();

            // 整个精判阶段复用原生数组，避免每个锚点创建 HashMap / Integer / MatchCandidate。
            byte[] candidateVotes = new byte[arr.length];
            int[] touched = new int[arr.length];
            int[] preciseCandidates = new int[arr.length];
            int[] preciseHashDistances = new int[arr.length];

            int precisionThreadCount = Math.max(1,
                    Math.min(MAX_PRECISION_THREADS, Math.max(1, Runtime.getRuntime().availableProcessors() - 1)));
            ExecutorService precisionWorkers = precisionThreadCount > 1
                    ? Executors.newFixedThreadPool(precisionThreadCount)
                    : null;

            int compareProgressStep = Math.max(1, arr.length / 100);
            long groupingStart = System.currentTimeMillis();
            long totalTouched = 0L;
            long totalPrecise = 0L;
            int parallelAnchors = 0;
            callback.onProgress("筛选候选并精确分组", 0, arr.length);

            try {
                for (int i = 0; i < arr.length; i++) {
                    Fingerprint anchor = arr[i];

                    if (!anchor.isGrouped) {
                        int touchedCount = lshIndex.collect(anchor, i, candidateVotes, touched);
                        totalTouched += touchedCount;

                        int preciseCount = 0;
                        for (int t = 0; t < touchedCount; t++) {
                            int j = touched[t];
                            candidateVotes[j] = 0; // 下一锚点复用同一 votes 数组。

                            Fingerprint candidate = arr[j];
                            if (candidate.isGrouped) continue;
                            if (!passesCheapMetadataFilter(anchor, candidate)) continue;

                            // 三个完整哈希的汉明距离只计算一次，并打包传给精判。
                            // v5 在候选门控和 calculateSimilarity 中重复 bitCount。
                            int packedDistances = packHashCandidateGate(anchor, candidate);
                            if (packedDistances < 0) continue;

                            preciseCandidates[preciseCount] = j;
                            preciseHashDistances[preciseCount] = packedDistances;
                            preciseCount++;
                        }

                        totalPrecise += preciseCount;
                        if (preciseCount > 0) {
                            MatchBatch matches;
                            if (precisionWorkers != null && preciseCount >= PARALLEL_CANDIDATE_THRESHOLD) {
                                parallelAnchors++;
                                matches = evaluateCandidatesParallel(
                                        precisionWorkers,
                                        precisionThreadCount,
                                        anchor,
                                        arr,
                                        preciseCandidates,
                                        preciseHashDistances,
                                        preciseCount
                                );
                            } else {
                                matches = evaluateCandidateRange(
                                        anchor,
                                        arr,
                                        preciseCandidates,
                                        preciseHashDistances,
                                        0,
                                        preciseCount
                                );
                            }

                            if (matches.size > 0) {
                                SimilarGroup group = new SimilarGroup();
                                group.similarItems.add(anchor.item);
                                anchor.isGrouped = true;

                                float totalSimilarity = 0f;
                                int matchCount = 0;
                                for (int m = 0; m < matches.size; m++) {
                                    int candidateIndex = matches.indices[m];
                                    Fingerprint match = arr[candidateIndex];
                                    if (match.isGrouped) continue;

                                    group.similarItems.add(match.item);
                                    match.isGrouped = true;
                                    totalSimilarity += matches.scores[m];
                                    matchCount++;
                                }

                                if (group.similarItems.size() >= 2) {
                                    group.averageSimilarity =
                                            matchCount == 0 ? 100.0 : totalSimilarity / matchCount;
                                    groups.add(group);
                                }
                            }
                        }
                    }

                    if (i == arr.length - 1 || (i + 1) % compareProgressStep == 0) {
                        callback.onProgress("筛选候选并精确分组", i + 1, arr.length);
                    }
                }
            } finally {
                if (precisionWorkers != null) precisionWorkers.shutdownNow();
            }

            groups.sort((g1, g2) -> {
                if (g1.similarItems.size() != g2.similarItems.size()) {
                    return Integer.compare(g2.similarItems.size(), g1.similarItems.size());
                }
                return Double.compare(g2.averageSimilarity, g1.averageSimilarity);
            });

            long groupingMs = System.currentTimeMillis() - groupingStart;
            Log.i(TAG, "Grouping done: images=" + arr.length
                    + ", lshTouched=" + totalTouched
                    + ", preciseCandidates=" + totalPrecise
                    + ", parallelAnchors=" + parallelAnchors
                    + ", groups=" + groups.size()
                    + ", ms=" + groupingMs);

            // 缓存落盘放到结果阶段之后，避免数据库写入与精判争抢 CPU / I/O。
            scheduleCacheWrite(executor, dao, rowsToWrite);
            callback.onComplete(groups, System.currentTimeMillis() - startTime);
        });
    }

    private static class MatchBatch {
        int[] indices;
        float[] scores;
        int size;

        MatchBatch(int capacity) {
            indices = new int[Math.max(1, capacity)];
            scores = new float[Math.max(1, capacity)];
        }

        void add(int index, float score) {
            if (size >= indices.length) {
                int newSize = indices.length * 2;
                indices = Arrays.copyOf(indices, newSize);
                scores = Arrays.copyOf(scores, newSize);
            }
            indices[size] = index;
            scores[size] = score;
            size++;
        }

        void append(MatchBatch other) {
            if (other == null || other.size == 0) return;
            int required = size + other.size;
            if (required > indices.length) {
                int newSize = Math.max(required, indices.length * 2);
                indices = Arrays.copyOf(indices, newSize);
                scores = Arrays.copyOf(scores, newSize);
            }
            System.arraycopy(other.indices, 0, indices, size, other.size);
            System.arraycopy(other.scores, 0, scores, size, other.size);
            size += other.size;
        }
    }

    private static MatchBatch evaluateCandidateRange(Fingerprint anchor,
                                                     Fingerprint[] arr,
                                                     int[] candidateIndices,
                                                     int[] packedHashDistances,
                                                     int start,
                                                     int end) {
        MatchBatch result = new MatchBatch(Math.max(4, end - start));
        for (int pos = start; pos < end; pos++) {
            int candidateIndex = candidateIndices[pos];
            Fingerprint candidate = arr[candidateIndex];
            if (candidate.isGrouped) continue;

            float similarity = calculateSimilarity(
                    anchor,
                    candidate,
                    packedHashDistances[pos]
            );
            if (similarity >= MATCH_THRESHOLD) {
                result.add(candidateIndex, similarity);
            }
        }
        return result;
    }

    private static MatchBatch evaluateCandidatesParallel(ExecutorService workers,
                                                         int workerCount,
                                                         Fingerprint anchor,
                                                         Fingerprint[] arr,
                                                         int[] candidateIndices,
                                                         int[] packedHashDistances,
                                                         int candidateCount) {
        int taskCount = Math.min(workerCount,
                Math.max(2, (candidateCount + PARALLEL_CANDIDATE_THRESHOLD - 1)
                        / PARALLEL_CANDIDATE_THRESHOLD));
        int chunk = (candidateCount + taskCount - 1) / taskCount;
        List<Future<MatchBatch>> futures = new ArrayList<>(taskCount);

        for (int task = 0; task < taskCount; task++) {
            final int start = task * chunk;
            final int end = Math.min(candidateCount, start + chunk);
            if (start >= end) break;
            futures.add(workers.submit(() -> evaluateCandidateRange(
                    anchor,
                    arr,
                    candidateIndices,
                    packedHashDistances,
                    start,
                    end
            )));
        }

        MatchBatch merged = new MatchBatch(Math.max(4, candidateCount / 4));
        for (int task = 0; task < futures.size(); task++) {
            Future<MatchBatch> future = futures.get(task);
            try {
                merged.append(future.get());
            } catch (Exception ignored) {
                // 精度优先：某个并行分片异常时，退回当前线程重算这一段，而不是漏掉候选。
                int start = task * chunk;
                int end = Math.min(candidateCount, start + chunk);
                merged.append(evaluateCandidateRange(
                        anchor,
                        arr,
                        candidateIndices,
                        packedHashDistances,
                        start,
                        end
                ));
            }
        }
        return merged;
    }

    /**
     * 返回三个完整哈希的汉明距离打包值；至少两个哈希进入宽松半径才继续。
     * bits 0..6=dHash, 7..13=pHash, 14..20=edgeHash。
     */
    private static int packHashCandidateGate(Fingerprint a, Fingerprint b) {
        int d = Long.bitCount(a.dHash64 ^ b.dHash64);
        int p = Long.bitCount(a.pHash64 ^ b.pHash64);
        int e = Long.bitCount(a.edgeHash64 ^ b.edgeHash64);

        int hits = 0;
        if (d <= 24) hits++;
        if (p <= 22) hits++;
        if (e <= 22) hits++;
        if (hits < 2) return -1;

        // 只根据三个哈希做一个“绝对乐观”的数学上界：
        // 假设 luma / edge / MAD / histogram 全部完美，并且还能拿到 +6 裁剪补偿。
        // 连这种理想情况都到不了 90 分，就绝无必要进入 32x32 数组精判。
        float dSim = 1f - d / 64f;
        float pSim = 1f - p / 63f;
        float edgeSim = 1f - e / 64f;
        float absoluteUpperBound = 100f * (
                0.18f * dSim
                        + 0.20f * pSim
                        + 0.12f * edgeSim
                        + 0.50f
        ) + 6.0f;
        if (absoluteUpperBound < MATCH_THRESHOLD) return -1;

        return d | (p << 7) | (e << 14);
    }

    private static boolean passesCheapMetadataFilter(Fingerprint a, Fingerprint b) {
        if (a.width <= 0 || a.height <= 0 || b.width <= 0 || b.height <= 0) return true;
        float maxRatio = Math.max(a.aspectRatio, b.aspectRatio);
        if (maxRatio <= 0f) return true;
        float relativeDiff = Math.abs(a.aspectRatio - b.aspectRatio) / maxRatio;
        return relativeDiff <= MAX_ASPECT_RELATIVE_DIFF;
    }

    private static Fingerprint fingerprintFromCache(MediaItem item, SimilarityFingerprintData data) {
        if (item == null || item.uri == null || data == null) return null;
        if (data.luma32 == null || data.luma32.length != FEATURE_PIXELS
                || data.colorHist64 == null || data.colorHist64.length != 64) return null;
        if (data.edge32 != null && data.edge32.length != FEATURE_PIXELS) return null;

        if (data.dateModified != item.dateModified) return null;
        if (item.fileSize > 0 && data.fileSize > 0 && data.fileSize != item.fileSize) return null;
        if (item.width > 0 && data.width > 0 && data.width != item.width) return null;
        if (item.height > 0 && data.height > 0 && data.height != item.height) return null;

        Fingerprint fp = new Fingerprint();
        fp.item = item;
        fp.width = data.width;
        fp.height = data.height;
        fp.fileSize = data.fileSize;
        fp.aspectRatio = fp.height > 0 ? (float) fp.width / fp.height : 1f;
        fp.dHash64 = data.dHash64;
        fp.pHash64 = data.pHash64;
        fp.edgeHash64 = data.edgeHash64;
        fp.luma32 = data.luma32;
        fp.edge32 = data.edge32 != null ? data.edge32 : buildEdgeMap(data.luma32);
        fp.colorHist64 = data.colorHist64;
        prepareFingerprintStats(fp);
        return fp;
    }

    private static void scheduleCacheWrite(ExecutorService executor,
                                           OcrDao dao,
                                           List<SimilarityFingerprintData> rows) {
        if (executor == null || dao == null || rows == null || rows.isEmpty()) return;
        final List<SimilarityFingerprintData> snapshot = new ArrayList<>(rows);
        executor.execute(() -> {
            long start = System.currentTimeMillis();
            try {
                // 分批事务避免一次把数千条 BLOB 绑定到同一个超长事务里。
                final int batchSize = 256;
                for (int from = 0; from < snapshot.size(); from += batchSize) {
                    int to = Math.min(snapshot.size(), from + batchSize);
                    dao.insertSimilarityFingerprints(
                            new ArrayList<>(snapshot.subList(from, to))
                    );
                }
                Log.i(TAG, "Fingerprint cache persisted: rows=" + snapshot.size()
                        + ", ms=" + (System.currentTimeMillis() - start));
            } catch (Exception e) {
                Log.w(TAG, "Fingerprint cache persist failed", e);
            }
        });
    }

    private static SimilarityFingerprintData toCacheRow(Fingerprint fp) {
        return new SimilarityFingerprintData(
                fp.item.uri.toString(),
                fp.item.dateModified,
                fp.fileSize,
                fp.width,
                fp.height,
                fp.dHash64,
                fp.pHash64,
                fp.edgeHash64,
                fp.luma32,
                null, // edge32 可由 luma32 瞬时重建，避免每张图额外写 1 KB BLOB。
                fp.colorHist64
        );
    }

    private static Fingerprint generateFingerprint(ContentResolver resolver, MediaItem item) {
        if (item == null || item.uri == null) return null;
        Bitmap thumb = null;
        Bitmap scaled = null;
        try {
            int width = item.width;
            int height = item.height;
            long fileSize = item.fileSize;

            if (width <= 0 || height <= 0) {
                BitmapFactory.Options bounds = readBounds(resolver, item);
                if (bounds != null) {
                    width = bounds.outWidth;
                    height = bounds.outHeight;
                }
            }
            if (width <= 0 || height <= 0) return null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    thumb = resolver.loadThumbnail(item.uri, new Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null);
                } catch (Exception ignored) {}
            }
            if (thumb == null) {
                thumb = decodeSampledThumbnail(resolver, item, width, height);
            }
            if (thumb == null) return null;

            scaled = Bitmap.createScaledBitmap(thumb, FEATURE_SIZE, FEATURE_SIZE, true);
            if (scaled != thumb) thumb.recycle();
            thumb = null;

            int[] pixels = new int[FEATURE_PIXELS];
            scaled.getPixels(pixels, 0, FEATURE_SIZE, 0, 0, FEATURE_SIZE, FEATURE_SIZE);

            byte[] luma = new byte[FEATURE_PIXELS];
            int[] histCount = new int[64];
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                int r = (p >> 16) & 0xff;
                int g = (p >> 8) & 0xff;
                int b = p & 0xff;
                int gray = (r * 77 + g * 150 + b * 29) >> 8;
                luma[i] = (byte) gray;
                int bin = ((r >> 6) << 4) | ((g >> 6) << 2) | (b >> 6);
                histCount[bin]++;
            }

            byte[] hist = new byte[64];
            for (int i = 0; i < 64; i++) {
                int normalized = Math.min(255, Math.round(histCount[i] * 255f / FEATURE_PIXELS));
                hist[i] = (byte) normalized;
            }

            byte[] edge = buildEdgeMap(luma);

            Fingerprint fp = new Fingerprint();
            fp.item = item;
            fp.width = width;
            fp.height = height;
            fp.fileSize = fileSize;
            fp.aspectRatio = (float) width / (float) height;
            fp.luma32 = luma;
            fp.edge32 = edge;
            fp.colorHist64 = hist;
            fp.dHash64 = calculateDHash(luma);
            fp.pHash64 = calculatePHash(luma);
            fp.edgeHash64 = calculateDHash(edge);
            prepareFingerprintStats(fp);
            return fp;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (scaled != null && !scaled.isRecycled()) scaled.recycle();
            if (thumb != null && !thumb.isRecycled()) thumb.recycle();
        }
    }

    private static BitmapFactory.Options readBounds(ContentResolver resolver, MediaItem item) {
        try (InputStream input = resolver.openInputStream(item.uri)) {
            if (input == null) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(input, null, options);
            return options;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Bitmap decodeSampledThumbnail(ContentResolver resolver, MediaItem item, int width, int height) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inSampleSize = calculateSampleSize(width, height, THUMBNAIL_SIZE, THUMBNAIL_SIZE);
        try (InputStream input = resolver.openInputStream(item.uri)) {
            if (input == null) return null;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int calculateSampleSize(int width, int height, int reqWidth, int reqHeight) {
        int sample = 1;
        while (width / (sample * 2) >= reqWidth && height / (sample * 2) >= reqHeight) {
            sample *= 2;
        }
        return Math.max(1, sample);
    }

    private static byte[] buildEdgeMap(byte[] luma) {
        byte[] edge = new byte[FEATURE_PIXELS];
        for (int y = 1; y < FEATURE_SIZE - 1; y++) {
            int row = y * FEATURE_SIZE;
            for (int x = 1; x < FEATURE_SIZE - 1; x++) {
                int left = unsigned(luma[row + x - 1]);
                int right = unsigned(luma[row + x + 1]);
                int top = unsigned(luma[row - FEATURE_SIZE + x]);
                int bottom = unsigned(luma[row + FEATURE_SIZE + x]);
                int magnitude = Math.min(255, (Math.abs(right - left) + Math.abs(bottom - top)) / 2);
                edge[row + x] = (byte) magnitude;
            }
        }
        return edge;
    }

    /** 从 32x32 特征图均匀采样成 9x8，再做横向差分。 */
    private static long calculateDHash(byte[] data) {
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < 8; y++) {
            int sy = Math.round(y * (FEATURE_SIZE - 1) / 7f);
            for (int x = 0; x < 8; x++) {
                int sx1 = Math.round(x * (FEATURE_SIZE - 1) / 8f);
                int sx2 = Math.round((x + 1) * (FEATURE_SIZE - 1) / 8f);
                int v1 = unsigned(data[sy * FEATURE_SIZE + sx1]);
                int v2 = unsigned(data[sy * FEATURE_SIZE + sx2]);
                if (v1 > v2) hash |= (1L << bit);
                bit++;
            }
        }
        return hash;
    }

    /**
     * 16x16 -> 8x8 低频 DCT pHash。去掉 DC 分量，实际使用 63 bit。
     * pHash 对轻度压缩、缩放、亮度变化比纯 dHash 稳定。
     */
    private static long calculatePHash(byte[] luma32) {
        double[][] small = new double[16][16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int baseY = y * 2;
                int baseX = x * 2;
                int sum = unsigned(luma32[baseY * FEATURE_SIZE + baseX])
                        + unsigned(luma32[baseY * FEATURE_SIZE + baseX + 1])
                        + unsigned(luma32[(baseY + 1) * FEATURE_SIZE + baseX])
                        + unsigned(luma32[(baseY + 1) * FEATURE_SIZE + baseX + 1]);
                small[y][x] = sum / 4.0;
            }
        }

        double[] coeff = new double[64];
        int idx = 0;
        for (int v = 0; v < 8; v++) {
            for (int u = 0; u < 8; u++) {
                double sum = 0.0;
                for (int y = 0; y < 16; y++) {
                    double cy = DCT_COS[v][y];
                    for (int x = 0; x < 16; x++) {
                        sum += small[y][x] * DCT_COS[u][x] * cy;
                    }
                }
                coeff[idx++] = sum;
            }
        }

        double[] forMedian = new double[63];
        System.arraycopy(coeff, 1, forMedian, 0, 63);
        Arrays.sort(forMedian);
        double median = forMedian[31];

        long hash = 0L;
        for (int i = 1; i < 64; i++) {
            if (coeff[i] > median) hash |= (1L << (i - 1));
        }
        return hash;
    }

    private static double[][] buildDctCosTable() {
        double[][] table = new double[8][16];
        for (int u = 0; u < 8; u++) {
            for (int x = 0; x < 16; x++) {
                table[u][x] = Math.cos(((2.0 * x + 1.0) * u * Math.PI) / 32.0);
            }
        }
        return table;
    }

    /**
     * 计算最终相似度。
     *
     * v6 的核心优化：
     * 1. d/p/edge 三个汉明距离由候选门控阶段只计算一次，通过 packedHashDistances 传入；
     * 2. luma correlation / edge cosine / brightness-adjusted MAD 在一次 32x32 遍历中融合计算；
     * 3. 先计算“不做平移”的基础分。如果已经 >=90，直接命中，不再做 25 个偏移搜索；
     * 4. 如果即使把平移后的结构指标都假设为 1.0 也不可能达到 90，也直接跳过配准；
     * 5. 只有真正处在 90% 阈值附近的候选才做 ±2px 配准。
     */
    private static float calculateSimilarity(Fingerprint a,
                                             Fingerprint b,
                                             int packedHashDistances) {
        float maxRatio = Math.max(a.aspectRatio, b.aspectRatio);
        float aspectDiff = maxRatio <= 0f
                ? 0f
                : Math.abs(a.aspectRatio - b.aspectRatio) / maxRatio;
        if (aspectDiff > MAX_ASPECT_RELATIVE_DIFF) return 0f;

        int dDist = packedHashDistances & 0x7f;
        int pDist = (packedHashDistances >>> 7) & 0x7f;
        int edgeDist = (packedHashDistances >>> 14) & 0x7f;

        float dSim = 1f - dDist / 64f;
        float pSim = 1f - pDist / 63f;
        float edgeHashSim = 1f - edgeDist / 64f;

        // 与 v5 保持一致的“明显无关”防火墙。
        if (dSim < 0.58f && pSim < 0.62f && edgeHashSim < 0.60f) return 0f;

        // 64-bin histogram 极便宜，先算它；马上用于“理论最高分”上界判断。
        float histSim = histogramIntersection(a.colorHist64, b.colorHist64);

        // 数学上界：假设平移后 luma/edge/MAD 全部达到 1.0。
        // 这一判断发生在 32x32 数组扫描之前，因此大量“哈希勉强接近、但色彩已经不可能
        // 达到 90 分”的候选连 1024 像素精判都无需进入。
        float possibleBonus =
                (histSim >= 0.90f && aspectDiff <= 0.07f) ? 6.0f : 0f;
        float optimisticScore = 100f * (
                0.18f * dSim
                        + 0.20f * pSim
                        + 0.12f * edgeHashSim
                        + 0.17f
                        + 0.18f
                        + 0.08f
                        + 0.07f * histSim
        );
        optimisticScore -= (aspectDiff / MAX_ASPECT_RELATIVE_DIFF) * 3.0f;
        optimisticScore += possibleBonus;
        if (optimisticScore < MATCH_THRESHOLD) {
            return 0f;
        }

        BaseMetrics base = calculateBaseMetrics(a, b);
        float baseScore = scoreFromMetrics(
                dSim, pSim, edgeHashSim,
                base.lumaCorrelation, base.edgeCosine, base.madSimilarity,
                histSim, aspectDiff
        );

        // 最常见的真正重复图在零位对齐时就已经超过阈值。
        // v5 仍会因为 luma<0.94 或 edge<0.90 去搜索 25 个平移位置，浪费大量 CPU。
        if (passesPrecisionFirewall(base.lumaCorrelation, base.edgeCosine, histSim, pSim)
                && baseScore >= MATCH_THRESHOLD) {
            return baseScore;
        }

        float lumaCorr = base.lumaCorrelation;
        float edgeCos = base.edgeCosine;
        float madSim = base.madSimilarity;

        // 与旧版一致：只有零位对齐不够高时才尝试轻微裁剪/位移。
        // 但现在前面已经排除了“已命中”和“理论不可能命中”的两大类。
        if (lumaCorr < 0.94f || edgeCos < 0.90f) {
            AlignmentMetrics aligned = findBestAlignment(a, b, base);
            if (aligned != null) {
                lumaCorr = Math.max(lumaCorr, aligned.lumaCorrelation);
                edgeCos = Math.max(edgeCos, aligned.edgeCosine);
                madSim = Math.max(madSim, aligned.madSimilarity);
            }
        }

        if (!passesPrecisionFirewall(lumaCorr, edgeCos, histSim, pSim)) return 0f;

        return scoreFromMetrics(
                dSim, pSim, edgeHashSim,
                lumaCorr, edgeCos, madSim,
                histSim, aspectDiff
        );
    }

    private static boolean passesPrecisionFirewall(float lumaCorr,
                                                   float edgeCos,
                                                   float histSim,
                                                   float pSim) {
        if (histSim < 0.45f && lumaCorr < 0.88f) return false;
        return !(edgeCos < 0.48f && pSim < 0.78f);
    }

    private static float scoreFromMetrics(float dSim,
                                          float pSim,
                                          float edgeHashSim,
                                          float lumaCorr,
                                          float edgeCos,
                                          float madSim,
                                          float histSim,
                                          float aspectDiff) {
        float score = 100f * (
                0.18f * dSim
                        + 0.20f * pSim
                        + 0.12f * edgeHashSim
                        + 0.17f * lumaCorr
                        + 0.18f * edgeCos
                        + 0.08f * madSim
                        + 0.07f * histSim
        );

        score -= (aspectDiff / MAX_ASPECT_RELATIVE_DIFF) * 3.0f;

        if (lumaCorr >= 0.86f
                && edgeCos >= 0.82f
                && histSim >= 0.90f
                && aspectDiff <= 0.07f) {
            score += 6.0f;
        }

        return Math.max(0f, Math.min(100f, score));
    }

    private static class BaseMetrics {
        float lumaCorrelation;
        float edgeCosine;
        float madSimilarity;
    }

    /**
     * 一次循环同时得到三项基础结构指标。
     * luma 的均值/方差与 edge norm 已经在每张 Fingerprint 上预计算。
     */
    private static BaseMetrics calculateBaseMetrics(Fingerprint a, Fingerprint b) {
        double lumaDot = 0.0;
        double edgeDot = 0.0;
        double mad = 0.0;
        double shift = a.lumaMean - b.lumaMean;

        byte[] lumaA = a.luma32;
        byte[] lumaB = b.luma32;
        byte[] edgeA = a.edge32;
        byte[] edgeB = b.edge32;

        for (int i = 0; i < FEATURE_PIXELS; i++) {
            int va = unsigned(lumaA[i]);
            int vb = unsigned(lumaB[i]);
            lumaDot += (double) va * vb;

            int ea = unsigned(edgeA[i]);
            int eb = unsigned(edgeB[i]);
            edgeDot += (double) ea * eb;

            double adjustedB = vb + shift;
            if (adjustedB < 0.0) adjustedB = 0.0;
            else if (adjustedB > 255.0) adjustedB = 255.0;
            mad += Math.abs(va - adjustedB);
        }

        BaseMetrics out = new BaseMetrics();

        if (a.lumaCorrelationDenom < 1e-6 && b.lumaCorrelationDenom < 1e-6) {
            out.lumaCorrelation = 1f;
        } else if (a.lumaCorrelationDenom < 1e-6 || b.lumaCorrelationDenom < 1e-6) {
            out.lumaCorrelation = 0f;
        } else {
            double numerator =
                    FEATURE_PIXELS * lumaDot - a.lumaSum * b.lumaSum;
            double corr = numerator
                    / (a.lumaCorrelationDenom * b.lumaCorrelationDenom);
            out.lumaCorrelation =
                    (float) Math.max(0.0, Math.min(1.0, corr));
        }

        if (a.edgeNorm < 1e-6 && b.edgeNorm < 1e-6) {
            out.edgeCosine = 1f;
        } else if (a.edgeNorm < 1e-6 || b.edgeNorm < 1e-6) {
            out.edgeCosine = 0f;
        } else {
            out.edgeCosine = (float) Math.max(
                    0.0,
                    Math.min(1.0, edgeDot / (a.edgeNorm * b.edgeNorm))
            );
        }

        mad /= FEATURE_PIXELS;
        out.madSimilarity =
                (float) Math.max(0.0, Math.min(1.0, 1.0 - mad / 80.0));
        return out;
    }

    private static void prepareFingerprintStats(Fingerprint fp) {
        if (fp == null || fp.luma32 == null || fp.edge32 == null) return;

        double lumaSum = 0.0;
        double lumaSq = 0.0;
        double edgeSq = 0.0;

        for (int i = 0; i < FEATURE_PIXELS; i++) {
            int l = unsigned(fp.luma32[i]);
            int e = unsigned(fp.edge32[i]);
            lumaSum += l;
            lumaSq += (double) l * l;
            edgeSq += (double) e * e;
        }

        fp.lumaSum = lumaSum;
        fp.lumaMean = (float) (lumaSum / FEATURE_PIXELS);

        double centered = FEATURE_PIXELS * lumaSq - lumaSum * lumaSum;
        fp.lumaCorrelationDenom = centered <= 1e-6 ? 0.0 : Math.sqrt(centered);
        fp.edgeNorm = edgeSq <= 1e-6 ? 0.0 : Math.sqrt(edgeSq);
    }

    private static class AlignmentMetrics {
        float lumaCorrelation;
        float edgeCosine;
        float madSimilarity;
    }

    /**
     * ±2 像素配准。v5 每个偏移会分别跑 Pearson(2遍)+edge(1遍)+MAD(2遍)，
     * 25 个偏移最多约 125 次区域扫描。
     *
     * v6 每个偏移只做一次统计扫描，按 luma correlation + edge cosine 选出最佳偏移，
     * 最后只对“最佳偏移”再扫描一次计算 brightness-adjusted MAD。
     */
    private static AlignmentMetrics findBestAlignment(Fingerprint a,
                                                      Fingerprint b,
                                                      BaseMetrics base) {
        float bestLuma = base.lumaCorrelation;
        float bestEdge = base.edgeCosine;
        float bestRank = 0.55f * bestLuma + 0.45f * bestEdge;
        int bestDx = 0;
        int bestDy = 0;
        double bestMeanShift = a.lumaMean - b.lumaMean;

        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                if (dx == 0 && dy == 0) continue;

                int ax0 = Math.max(0, dx);
                int bx0 = Math.max(0, -dx);
                int ay0 = Math.max(0, dy);
                int by0 = Math.max(0, -dy);
                int width = FEATURE_SIZE - Math.abs(dx);
                int height = FEATURE_SIZE - Math.abs(dy);
                if (width < FEATURE_SIZE - 2 || height < FEATURE_SIZE - 2) continue;

                int count = width * height;
                double sumA = 0.0;
                double sumB = 0.0;
                double sumAA = 0.0;
                double sumBB = 0.0;
                double sumAB = 0.0;
                double edgeAA = 0.0;
                double edgeBB = 0.0;
                double edgeAB = 0.0;

                for (int y = 0; y < height; y++) {
                    int aRow = (ay0 + y) * FEATURE_SIZE + ax0;
                    int bRow = (by0 + y) * FEATURE_SIZE + bx0;
                    for (int x = 0; x < width; x++) {
                        int va = unsigned(a.luma32[aRow + x]);
                        int vb = unsigned(b.luma32[bRow + x]);
                        sumA += va;
                        sumB += vb;
                        sumAA += (double) va * va;
                        sumBB += (double) vb * vb;
                        sumAB += (double) va * vb;

                        int ea = unsigned(a.edge32[aRow + x]);
                        int eb = unsigned(b.edge32[bRow + x]);
                        edgeAA += (double) ea * ea;
                        edgeBB += (double) eb * eb;
                        edgeAB += (double) ea * eb;
                    }
                }

                float luma;
                double varA = count * sumAA - sumA * sumA;
                double varB = count * sumBB - sumB * sumB;
                if (varA < 1e-6 && varB < 1e-6) {
                    luma = 1f;
                } else if (varA < 1e-6 || varB < 1e-6) {
                    luma = 0f;
                } else {
                    double numerator = count * sumAB - sumA * sumB;
                    luma = (float) Math.max(
                            0.0,
                            Math.min(1.0, numerator / Math.sqrt(varA * varB))
                    );
                }

                float edge;
                if (edgeAA < 1e-6 && edgeBB < 1e-6) {
                    edge = 1f;
                } else if (edgeAA < 1e-6 || edgeBB < 1e-6) {
                    edge = 0f;
                } else {
                    edge = (float) Math.max(
                            0.0,
                            Math.min(1.0, edgeAB / Math.sqrt(edgeAA * edgeBB))
                    );
                }

                float rank = 0.55f * luma + 0.45f * edge;
                if (rank > bestRank) {
                    bestRank = rank;
                    bestLuma = luma;
                    bestEdge = edge;
                    bestDx = dx;
                    bestDy = dy;
                    bestMeanShift = sumA / count - sumB / count;
                }
            }
        }

        if (bestDx == 0 && bestDy == 0) {
            return null;
        }

        int ax0 = Math.max(0, bestDx);
        int bx0 = Math.max(0, -bestDx);
        int ay0 = Math.max(0, bestDy);
        int by0 = Math.max(0, -bestDy);
        int width = FEATURE_SIZE - Math.abs(bestDx);
        int height = FEATURE_SIZE - Math.abs(bestDy);
        int count = width * height;

        double mad = 0.0;
        for (int y = 0; y < height; y++) {
            int aRow = (ay0 + y) * FEATURE_SIZE + ax0;
            int bRow = (by0 + y) * FEATURE_SIZE + bx0;
            for (int x = 0; x < width; x++) {
                int va = unsigned(a.luma32[aRow + x]);
                int vb = unsigned(b.luma32[bRow + x]);
                double adjustedB = vb + bestMeanShift;
                if (adjustedB < 0.0) adjustedB = 0.0;
                else if (adjustedB > 255.0) adjustedB = 255.0;
                mad += Math.abs(va - adjustedB);
            }
        }

        AlignmentMetrics out = new AlignmentMetrics();
        out.lumaCorrelation = bestLuma;
        out.edgeCosine = bestEdge;
        mad /= Math.max(1, count);
        out.madSimilarity =
                (float) Math.max(0.0, Math.min(1.0, 1.0 - mad / 80.0));
        return out;
    }

    private static float pearsonCorrelation(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double meanA = 0, meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += unsigned(a[i]);
            meanB += unsigned(b[i]);
        }
        meanA /= a.length;
        meanB /= b.length;

        double cov = 0, varA = 0, varB = 0;
        for (int i = 0; i < a.length; i++) {
            double da = unsigned(a[i]) - meanA;
            double db = unsigned(b[i]) - meanB;
            cov += da * db;
            varA += da * da;
            varB += db * db;
        }
        if (varA < 1e-6 && varB < 1e-6) return 1f;
        if (varA < 1e-6 || varB < 1e-6) return 0f;
        double corr = cov / Math.sqrt(varA * varB);
        return (float) Math.max(0.0, Math.min(1.0, corr));
    }

    private static float cosineSimilarity(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            double va = unsigned(a[i]);
            double vb = unsigned(b[i]);
            dot += va * vb;
            aa += va * va;
            bb += vb * vb;
        }
        if (aa < 1e-6 && bb < 1e-6) return 1f;
        if (aa < 1e-6 || bb < 1e-6) return 0f;
        return (float) Math.max(0.0, Math.min(1.0, dot / Math.sqrt(aa * bb)));
    }

    private static float brightnessAdjustedMadSimilarity(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        double meanA = 0, meanB = 0;
        for (int i = 0; i < a.length; i++) {
            meanA += unsigned(a[i]);
            meanB += unsigned(b[i]);
        }
        meanA /= a.length;
        meanB /= b.length;
        double shift = meanA - meanB;

        double mad = 0;
        for (int i = 0; i < a.length; i++) {
            double adjustedB = Math.max(0, Math.min(255, unsigned(b[i]) + shift));
            mad += Math.abs(unsigned(a[i]) - adjustedB);
        }
        mad /= a.length;
        return (float) Math.max(0.0, Math.min(1.0, 1.0 - mad / 80.0));
    }

    private static float histogramIntersection(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0f;
        int intersection = 0;
        int totalA = 0;
        int totalB = 0;
        for (int i = 0; i < a.length; i++) {
            int va = unsigned(a[i]);
            int vb = unsigned(b[i]);
            intersection += Math.min(va, vb);
            totalA += va;
            totalB += vb;
        }
        int denom = Math.max(totalA, totalB);
        if (denom == 0) return 1f;
        return Math.max(0f, Math.min(1f, intersection / (float) denom));
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
