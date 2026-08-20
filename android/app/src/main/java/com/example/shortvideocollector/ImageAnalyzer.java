package com.example.shortvideocollector;

import android.graphics.Bitmap;

final class ImageAnalyzer {
    private static final int SIDE = 32;

    private ImageAnalyzer() {}

    static byte[] signature(Bitmap bitmap) {
        byte[] result = new byte[SIDE * SIDE];
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        // Ignore common top/bottom controls and the right-side action rail.
        int left = (int) (width * 0.04f);
        int right = (int) (width * 0.86f);
        int top = (int) (height * 0.09f);
        int bottom = (int) (height * 0.82f);
        for (int y = 0; y < SIDE; y++) {
            int sourceY = top + (bottom - top) * y / SIDE;
            for (int x = 0; x < SIDE; x++) {
                int sourceX = left + (right - left) * x / SIDE;
                int color = bitmap.getPixel(sourceX, sourceY);
                int red = (color >> 16) & 0xff;
                int green = (color >> 8) & 0xff;
                int blue = color & 0xff;
                result[y * SIDE + x] = (byte) ((red * 30 + green * 59 + blue * 11) / 100);
            }
        }
        return result;
    }

    static float difference(byte[] first, byte[] second) {
        if (first == null || second == null || first.length != second.length) return 1f;
        long total = 0;
        for (int index = 0; index < first.length; index++) {
            total += Math.abs((first[index] & 0xff) - (second[index] & 0xff));
        }
        return total / (255f * first.length);
    }
}

