package com.tripmate.identity.verification;

import java.awt.image.BufferedImage;

/**
 * A 64-bit difference hash: the image is averaged down to a 9x8 grid of grey
 * levels, and each bit says whether a cell is brighter than the one to its
 * right. Near-identical pictures give hashes a few bits apart; unrelated ones
 * differ in about half the bits. Deterministic and dependency-free, which is
 * what a stand-in for a face-match service needs, but it compares pictures,
 * not faces.
 */
final class DifferenceHash {

    static final int BITS = 64;

    private static final int COLUMNS = 9;

    private static final int ROWS = 8;

    private DifferenceHash() {
    }

    static long of(BufferedImage image) {
        double[][] grid = averageGrid(image);
        long hash = 0;
        for (int row = 0; row < ROWS; row++) {
            for (int column = 0; column < COLUMNS - 1; column++) {
                hash <<= 1;
                if (grid[row][column] > grid[row][column + 1]) {
                    hash |= 1;
                }
            }
        }
        return hash;
    }

    static int distance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    /** Exact cell averages, rather than a resampling filter, so the result does not depend on the JDK's scaler. */
    private static double[][] averageGrid(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        double[][] grid = new double[ROWS][COLUMNS];
        for (int row = 0; row < ROWS; row++) {
            int top = row * height / ROWS;
            int bottom = Math.max(top + 1, (row + 1) * height / ROWS);
            for (int column = 0; column < COLUMNS; column++) {
                int left = column * width / COLUMNS;
                int right = Math.max(left + 1, (column + 1) * width / COLUMNS);
                double sum = 0;
                for (int y = top; y < bottom; y++) {
                    for (int x = left; x < right; x++) {
                        int rgb = image.getRGB(x, y);
                        sum += 0.299 * ((rgb >> 16) & 0xff) + 0.587 * ((rgb >> 8) & 0xff) + 0.114 * (rgb & 0xff);
                    }
                }
                grid[row][column] = sum / ((double) (bottom - top) * (right - left));
            }
        }
        return grid;
    }
}
