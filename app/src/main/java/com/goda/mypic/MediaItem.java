package com.goda.mypic;

import android.net.Uri;

public class MediaItem {
    public Uri uri;
    public String path;
    public long dateAdded;
    public long dateModified; // 记录文件的最后修改时间戳
    public String mimeType;
    public MediaType type;

    // 🚨 恢复了你原本的 GIF 类型，修复编译报错
    public enum MediaType {
        IMAGE, VIDEO, GIF
    }

    public MediaItem(Uri uri, String path, long dateAdded, long dateModified, String mimeType, MediaType type) {
        this.uri = uri;
        this.path = path;
        this.dateAdded = dateAdded;
        this.dateModified = dateModified;
        this.mimeType = mimeType;
        this.type = type;
    }
}