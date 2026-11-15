package com.goda.mypic;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MediaScanner {

    private static final String TAG = "MediaScanner";

    // 屏蔽 Syncthing 等同步产生的垃圾与冲突文件
    public static final String BLACKLIST_DIR = ".stfolder";
    public static final String BLACKLIST_CONFLICT = ".sync-conflict-";

    public static List<MediaItem> scanAllMedia(Context context) {
        List<MediaItem> mediaList = new ArrayList<>();

        // 统一查询外部媒体库 (包含图片和视频)
        Uri collection = MediaStore.Files.getContentUri("external");

        // 投影列：包含 ID、路径、DATE_TAKEN(EXIF/视频拍摄时间)、DATE_MODIFIED(修改时间)、DATE_ADDED
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Images.Media.DATE_TAKEN,         // "datetaken" (毫秒，对应图片EXIF拍摄时间与视频moov atom拍摄时间)
                MediaStore.Files.FileColumns.DATE_MODIFIED, // "date_modified" (秒，文件最后修改时间)
                MediaStore.Files.FileColumns.DATE_ADDED,    // "date_added" (秒，添加时间)
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.SIZE
        };

        // 过滤条件：只查图片和视频，并由 MediaStore 底层过滤掉 .stfolder 目录及 .sync-conflict- 开头的冲突文件
        String selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                + " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")"
                + " AND (" + MediaStore.Files.FileColumns.DATA + " IS NULL OR ("
                + MediaStore.Files.FileColumns.DATA + " NOT LIKE ? AND "
                + MediaStore.Files.FileColumns.DATA + " NOT LIKE ?))";

        String[] selectionArgs = new String[]{
                "%" + BLACKLIST_DIR + "%",
                "%" + BLACKLIST_CONFLICT + "%"
        };

        // 排序规则：优先按 EXIF/拍摄时间 (DATE_TAKEN)，若无 (0 或 NULL) 则降级按修改时间 (DATE_MODIFIED * 1000) 倒序排列
        // 底层 SQLite 一次性完成混合排序，无 EXIF 的梗图和有 EXIF 的相机照片完美穿插排列
        String sortOrder = "CASE WHEN " + MediaStore.Images.Media.DATE_TAKEN + " > 0 THEN "
                + MediaStore.Images.Media.DATE_TAKEN + " ELSE "
                + MediaStore.Files.FileColumns.DATE_MODIFIED + " * 1000 END DESC, "
                + MediaStore.Files.FileColumns._ID + " DESC";

        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(collection, projection, selection, selectionArgs, sortOrder);
        } catch (Exception e) {
            Log.w(TAG, "带表达式的 sortOrder 查询失败，尝试降级排序查询", e);
            try {
                String fallbackSort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                        + MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC, "
                        + MediaStore.Files.FileColumns._ID + " DESC";
                cursor = context.getContentResolver().query(collection, projection, selection, selectionArgs, fallbackSort);
            } catch (Exception ex) {
                Log.e(TAG, "降级查询依然失败", ex);
            }
        }

        if (cursor != null) {
            try {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
                int dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
                int dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                int dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED);
                int mimeTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                int mediaTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                int widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH);
                int heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);

                while (cursor.moveToNext()) {
                    String path = cursor.getString(pathCol);

                    // Java 层二次黑名单过滤，保证 100% 屏蔽垃圾与冲突文件
                    if (isBlacklisted(path)) {
                        continue;
                    }

                    long id = cursor.getLong(idCol);
                    long rawDateTaken = (dateTakenCol >= 0 && !cursor.isNull(dateTakenCol)) ? cursor.getLong(dateTakenCol) : 0L;
                    long dateModified = cursor.getLong(dateModifiedCol);
                    long dateAdded = cursor.getLong(dateAddedCol);

                    // 优先 DATE_TAKEN (EXIF / 视频拍摄时间，毫秒)；
                    // 若为 null 或 <= 0，降级为 DATE_MODIFIED (修改时间，转毫秒)；
                    // 若修改时间亦异常，再降级为 DATE_ADDED
                    long effectiveTimeMs = rawDateTaken > 0 ? rawDateTaken : (dateModified > 0 ? dateModified * 1000L : dateAdded * 1000L);

                    String mimeType = cursor.getString(mimeTypeCol);
                    int mediaType = cursor.getInt(mediaTypeCol);
                    int width = cursor.isNull(widthCol) ? 0 : cursor.getInt(widthCol);
                    int height = cursor.isNull(heightCol) ? 0 : cursor.getInt(heightCol);
                    long fileSize = cursor.isNull(sizeCol) ? 0L : cursor.getLong(sizeCol);

                    // 组装真实的 Uri
                    Uri contentUri = ContentUris.withAppendedId(
                            mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ?
                                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);

                    MediaItem.MediaType type = mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ?
                            MediaItem.MediaType.IMAGE : MediaItem.MediaType.VIDEO;

                    mediaList.add(new MediaItem(contentUri, path, effectiveTimeMs, dateModified, mimeType, type,
                            width, height, fileSize));
                }
            } catch (Exception e) {
                Log.e(TAG, "解析游标数据失败", e);
            } finally {
                cursor.close();
            }
        }

        return mediaList;
    }

    /**
     * 过滤黑名单文件与目录 (例如 Syncthing 同步冲突文件与 .stfolder 目录)
     */
    public static boolean isBlacklisted(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains(BLACKLIST_DIR) || lower.contains(BLACKLIST_CONFLICT);
    }
}
