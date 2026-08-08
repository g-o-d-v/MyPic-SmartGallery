package com.goda.mypic;

import android.net.Uri;

import java.util.Objects;

public class MediaItem {
    public Uri uri;
    public String path;
    public long dateAdded;
    public long dateModified; // 记录文件的最后修改时间戳
    public String mimeType;
    public MediaType type;
    // MediaStore 元数据：相似图扫描可直接使用，避免每次为尺寸/大小额外打开文件。
    public int width;
    public int height;
    public long fileSize;

    // 🚨 恢复了你原本的 GIF 类型，修复编译报错
    public enum MediaType {
        IMAGE, VIDEO, GIF
    }

    public MediaItem(Uri uri, String path, long dateAdded, long dateModified, String mimeType, MediaType type) {
        this(uri, path, dateAdded, dateModified, mimeType, type, 0, 0, 0L);
    }

    public MediaItem(Uri uri, String path, long dateAdded, long dateModified, String mimeType, MediaType type,
                     int width, int height, long fileSize) {
        this.uri = uri;
        this.path = path;
        this.dateAdded = dateAdded;
        this.dateModified = dateModified;
        this.mimeType = mimeType;
        this.type = type;
        this.width = width;
        this.height = height;
        this.fileSize = fileSize;
    }

    /**
     * MediaStore 每次重新扫描都会创建新的 MediaItem 对象。
     * 以 Uri 作为稳定身份，确保“扫描返回的新对象”仍能和删除前保存的对象正确匹配。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MediaItem)) return false;
        MediaItem other = (MediaItem) obj;
        return Objects.equals(uri, other.uri);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(uri);
    }

    public long stableId() {
        if (uri != null) {
            String last = uri.getLastPathSegment();
            if (last != null) {
                try {
                    long mediaId = Long.parseLong(last);
                    return mediaId * 4L + type.ordinal();
                } catch (NumberFormatException ignored) {}
            }
            return uri.toString().hashCode();
        }
        return 0L;
    }
}
