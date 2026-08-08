package com.goda.mypic;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class MediaScanner {

    public static List<MediaItem> scanAllMedia(Context context) {
        List<MediaItem> mediaList = new ArrayList<>();

        // 统一查询外部媒体库 (包含图片和视频)
        Uri collection = MediaStore.Files.getContentUri("external");

        // 🚨 投影列：告诉系统我们要查哪些数据 (新增了 DATE_MODIFIED)
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.DATE_MODIFIED, // 🚨 新增查询修改时间
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.SIZE
        };

        // 过滤条件：只查图片和视频
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
                + " OR " + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" + MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO;

        // 排序规则：按添加时间倒序 (最新的在最上面)
        String sortOrder = MediaStore.Files.FileColumns.DATE_ADDED + " DESC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                int pathCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA);
                int dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED);
                int dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED); // 🚨 获取列索引
                int mimeTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                int mediaTypeCol = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE);
                int widthCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH);
                int heightCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String path = cursor.getString(pathCol);
                    long dateAdded = cursor.getLong(dateAddedCol);
                    long dateModified = cursor.getLong(dateModifiedCol); // 🚨 读取游标里的真实修改时间
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

                    // 🚨 传入 dateModified
                    mediaList.add(new MediaItem(contentUri, path, dateAdded, dateModified, mimeType, type,
                            width, height, fileSize));
                }
            }
        } catch (Exception e) {
            Log.e("MediaScanner", "媒体扫描失败", e);
        }

        return mediaList;
    }
}