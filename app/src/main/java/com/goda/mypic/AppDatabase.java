package com.goda.mypic;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {ImageOcrData.class}, version = 1, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract OcrDao ocrDao();

    private static volatile AppDatabase INSTANCE;
    private static final String DB_NAME = "mypic_ocr_database";

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    // 最纯粹的初始化：不检索、不备份、不转移，只在沙盒里呆着
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                            AppDatabase.class, DB_NAME).build();
                }
            }
        }
        return INSTANCE;
    }

    // 之前所有的 backupDatabaseToPublic、saveToMediaStore 等方法已全部安全删除
}