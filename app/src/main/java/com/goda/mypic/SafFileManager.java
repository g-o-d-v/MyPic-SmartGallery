package com.goda.mypic;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import androidx.documentfile.provider.DocumentFile;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * 核心文件操作引擎 (纯 SAF 版本 - 已锁定)
 * 100% 采用 Android 官方的 Storage Access Framework 流式操作。
 * 彻底摒弃 FileChannel 和 renameTo，确保复制、移动、删除的绝对稳定性。
 */
public class SafFileManager {

    /**
     * 1. 极速推导 SAF 的 Document URI
     * (通过路径字符串拼接，避免使用缓慢的 DocumentFile.findFile 遍历)
     */
    public static Uri buildSafUri(String absolutePath, String treeUriStr) {
        String basePath = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (absolutePath == null || !absolutePath.startsWith(basePath) || absolutePath.length() <= basePath.length()) {
            return null;
        }

        String relative = absolutePath.substring(basePath.length());
        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }

        if (treeUriStr == null || treeUriStr.isEmpty()) return null;

        try {
            Uri treeUri = Uri.parse(treeUriStr);
            String docId = "primary:" + relative;
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 2. SAF 安全删除底层文件及媒体库记录
     */
    public static boolean deleteFile(Context context, String absolutePath, Uri mediaStoreUri, String treeUriStr) {
        boolean deleted = false;

        try {
            // 步骤 A: SAF 底层安全抹杀文件，无视华为管家普通拦截
            Uri safUri = buildSafUri(absolutePath, treeUriStr);
            if (safUri != null) {
                DocumentsContract.deleteDocument(context.getContentResolver(), safUri);
                deleted = true;
            }
        } catch (Exception ignored) {}

        try {
            // 步骤 B: 无论底层是否删除成功，同步清空系统相册(MediaStore)里的缩略图记录
            if (mediaStoreUri != null) {
                context.getContentResolver().delete(mediaStoreUri, null, null);
                deleted = true;
            }
        } catch (Exception ignored) {}

        return deleted;
    }

    /**
     * 3. SAF 全局流式复制 (将文件以字节流形式灌入新的 Document 容器)
     */
    public static boolean copyFile(Context context, Uri sourceUri, Uri targetParentUri, String mimeType, String newFileName) {
        try {
            // 在目标文件夹中占位一个新文件
            Uri newDocUri = DocumentsContract.createDocument(context.getContentResolver(), targetParentUri, mimeType, newFileName);

            if (newDocUri != null) {
                byte[] buffer = new byte[1024 * 128]; // 128KB 极限双缓冲
                try (InputStream in = context.getContentResolver().openInputStream(sourceUri);
                     OutputStream out = context.getContentResolver().openOutputStream(newDocUri)) {

                    if (in == null || out == null) return false;

                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                    out.flush();
                    return true;
                }
            }
        } catch (Exception ignored) {}

        return false;
    }

    /**
     * 4. 获取/创建目标文件夹的 URI
     */
    public static Uri getOrCreateTargetDirectory(Context context, String rootTreeUriStr, String folderName) {
        if (rootTreeUriStr == null || rootTreeUriStr.isEmpty()) return null;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, Uri.parse(rootTreeUriStr));
            if (root != null) {
                DocumentFile targetDir = root.findFile(folderName);
                if (targetDir == null) {
                    // 如果文件夹不存在，则通过 SAF 原生创建
                    targetDir = root.createDirectory(folderName);
                }
                return targetDir != null ? targetDir.getUri() : null;
            }
        } catch (Exception ignored) {}
        return null;
    }
}