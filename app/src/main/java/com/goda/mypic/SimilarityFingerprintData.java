package com.goda.mypic;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * 相似图片扫描使用的持久化轻量指纹。
 *
 * 只要 Uri / 修改时间 / 文件大小 / 尺寸没有变化，下次扫描就不必重新解码原图。
 */
@Entity(tableName = "similarity_fingerprint_table")
public class SimilarityFingerprintData {
    @PrimaryKey
    @NonNull
    public String imageUri;

    public long dateModified;
    public long fileSize;
    public int width;
    public int height;

    public long dHash64;
    public long pHash64;
    public long edgeHash64;

    // 32x32 灰度图、可选的 32x32 边缘强度、4x4x4 RGB 直方图（均按无符号 byte 使用）。
    // v5 新写入记录不再持久化 edge32；它可由 luma32 快速重建，用于减少大图库首次缓存落盘量。
    public byte[] luma32;
    public byte[] edge32;
    public byte[] colorHist64;

    public SimilarityFingerprintData(@NonNull String imageUri,
                                     long dateModified,
                                     long fileSize,
                                     int width,
                                     int height,
                                     long dHash64,
                                     long pHash64,
                                     long edgeHash64,
                                     byte[] luma32,
                                     byte[] edge32,
                                     byte[] colorHist64) {
        this.imageUri = imageUri;
        this.dateModified = dateModified;
        this.fileSize = fileSize;
        this.width = width;
        this.height = height;
        this.dHash64 = dHash64;
        this.pHash64 = pHash64;
        this.edgeHash64 = edgeHash64;
        this.luma32 = luma32;
        this.edge32 = edge32;
        this.colorHist64 = colorHist64;
    }
}
