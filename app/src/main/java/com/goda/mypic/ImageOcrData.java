package com.goda.mypic;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "image_ocr_table")
public class ImageOcrData {
    @PrimaryKey
    @NonNull
    public String imageUri;      // 图片的唯一标识 (Uri)

    public long dateModified;    // 最后修改时间 (用于判断图片是否被编辑过，避免重复扫描)

    public String extractedText; // 提取出的所有文字集合

    // 🚨 新增的两个核心高速检索字段
    public boolean isGif;        // 是否为动图 (含伪装 WebP)
    public boolean isNoText;     // 是否为无字纯图

    // 构造函数也同步更新
    public ImageOcrData(@NonNull String imageUri, long dateModified, String extractedText, boolean isGif, boolean isNoText) {
        this.imageUri = imageUri;
        this.dateModified = dateModified;
        this.extractedText = extractedText;
        this.isGif = isGif;
        this.isNoText = isNoText;
    }
}