package com.goda.mypic;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface OcrDao {
    // 1. 插入或更新一条 OCR 数据
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertOcrData(ImageOcrData data);

    // 2. 获取某张图的上次扫描时间 (用于增量扫描)
    @Query("SELECT dateModified FROM image_ocr_table WHERE imageUri = :uri LIMIT 1")
    Long getModifiedDate(String uri);

    // 3. 🚨 极速秒搜：用 SQL 模糊匹配关键字
    @Query("SELECT imageUri FROM image_ocr_table WHERE extractedText LIKE '%' || :keyword || '%'")
    List<String> searchImagesByKeyword(String keyword);

    // 4. 清理被用户删除的图片数据
    @Query("DELETE FROM image_ocr_table WHERE imageUri = :uri")
    void deleteByUri(String uri);

    // 5. 精准捞出真正有文字的图片 URI (排除空字符串和 NULL)
    @Query("SELECT imageUri FROM image_ocr_table WHERE length(extractedText) > 0")
    List<String> getUrisWithText();

    // ================= 以下为新增的高速检索接口 =================

    // 6. 🚨 极速秒查所有动图
    @Query("SELECT imageUri FROM image_ocr_table WHERE isGif = 1")
    List<String> getAllGifUris();

    // 7. 🚨 极速秒查所有无字图
    @Query("SELECT imageUri FROM image_ocr_table WHERE isNoText = 1")
    List<String> getNoTextUris();
}