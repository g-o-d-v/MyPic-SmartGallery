package com.goda.mypic;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {ImageOcrData.class, SimilarityFingerprintData.class}, version = 2, exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {
    public abstract OcrDao ocrDao();

    private static volatile AppDatabase INSTANCE;
    private static final String DB_NAME = "mypic_ocr_database";

    /**
     * v2 只新增相似图片指纹缓存表，不触碰原有 OCR 表和数据。
     */
    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `similarity_fingerprint_table` (" +
                    "`imageUri` TEXT NOT NULL, " +
                    "`dateModified` INTEGER NOT NULL, " +
                    "`fileSize` INTEGER NOT NULL, " +
                    "`width` INTEGER NOT NULL, " +
                    "`height` INTEGER NOT NULL, " +
                    "`dHash64` INTEGER NOT NULL, " +
                    "`pHash64` INTEGER NOT NULL, " +
                    "`edgeHash64` INTEGER NOT NULL, " +
                    "`luma32` BLOB, " +
                    "`edge32` BLOB, " +
                    "`colorHist64` BLOB, " +
                    "PRIMARY KEY(`imageUri`))");
        }
    };

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(context.getApplicationContext(),
                                    AppDatabase.class, DB_NAME)
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
