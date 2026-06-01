package com.goda.mypic;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.InputStream;

public class ImageHasher {

    public static long calculateDHashFromUri(ContentResolver resolver, Uri imageUri) {
        Bitmap bitmap = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(imageUri)) {
                if (in == null) return 0;
                BitmapFactory.decodeStream(in, null, options);
            }

            options.inSampleSize = calculateInSampleSize(options, 32, 32);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            try (InputStream in = resolver.openInputStream(imageUri)) {
                if (in == null) return 0;
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }

            if (bitmap == null) return 0;

            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 9, 8, true);
            if (bitmap != scaled) bitmap.recycle();

            int[] pixels = new int[72];
            scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8);
            scaled.recycle();

            long hash = 0;
            int index = 0;
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int pLeft = pixels[y * 9 + x];
                    int pRight = pixels[y * 9 + x + 1];

                    int grayLeft = (((pLeft >> 16) & 0xFF) + ((pLeft >> 8) & 0xFF) + (pLeft & 0xFF)) / 3;
                    int grayRight = (((pRight >> 16) & 0xFF) + ((pRight >> 8) & 0xFF) + (pRight & 0xFF)) / 3;

                    if (grayLeft > grayRight) {
                        hash |= (1L << (63 - index));
                    }
                    index++;
                }
            }
            return hash;

        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean isHighlySimilar(long hash1, long hash2) {
        if (hash1 == 0 || hash2 == 0) return false;
        int distance = Long.bitCount(hash1 ^ hash2);
        // 【核心修复】：64位指纹中，允许的最大差异位数设为3。
        // 相似度公式：1 - (3 / 64) = 95.31%。锁定 95% 以上精确度！
        return distance <= 3;
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }
}