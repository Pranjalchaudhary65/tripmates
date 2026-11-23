package com.tripmate.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Pictures whose difference hash is known in advance, so a test can choose
 * what the mock liveness provider decides.
 *
 * <p>Each picture is a 9x8 grid of 40-pixel grey cells (360x320, so a cell
 * lines up with JPEG's 8x8 blocks and survives re-encoding). In a "plain" row
 * the cells get brighter left to right, which hashes to eight 0 bits; a
 * "reversed" row gets darker, eight 1 bits. Neighbouring cells differ by 25
 * grey levels, far more than JPEG noise moves them.
 *
 * <ul>
 *   <li>{@link #face(int)} with 0 reversed rows is the reference profile photo;</li>
 *   <li>each reversed row moves 8 bits, i.e. 0.125 of similarity:
 *       0 rows = 1.0 (approve), 2 rows = 0.75 (review), 8 rows = 0.0 (reject);</li>
 *   <li>frames differing by at least one row pass the mock liveness check.</li>
 * </ul>
 */
public final class TestImages {

    private static final int CELL = 40;

    private static final int COLUMNS = 9;

    private static final int ROWS = 8;

    private TestImages() {
    }

    /** The grid with its first {@code reversedRows} rows reversed. */
    public static BufferedImage face(int reversedRows) {
        BufferedImage image = new BufferedImage(COLUMNS * CELL, ROWS * CELL, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            for (int row = 0; row < ROWS; row++) {
                for (int column = 0; column < COLUMNS; column++) {
                    int step = row < reversedRows ? COLUMNS - 1 - column : column;
                    int grey = 20 + 25 * step;
                    g.setColor(new Color(grey, grey, grey));
                    g.fillRect(column * CELL, row * CELL, CELL, CELL);
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    public static byte[] jpeg(int reversedRows) {
        return encode(face(reversedRows), "jpeg");
    }

    public static byte[] png(int reversedRows) {
        return encode(face(reversedRows), "png");
    }

    /** The reference photo. */
    public static byte[] profilePhoto() {
        return jpeg(0);
    }

    /**
     * A JPEG carrying an EXIF segment with a GPS marker, inserted right after
     * the start-of-image marker where cameras put it.
     */
    public static byte[] jpegWithExif() {
        byte[] plain = jpeg(0);
        byte[] tiff = ("MM\0*\0\0\0\u0008" + "GPSLatitude=28.6139N;GPSLongitude=77.2090E;secret-home-address")
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] exifHeader = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
        int length = 2 + exifHeader.length + tiff.length;
        ByteBuffer segment = ByteBuffer.allocate(2 + length);
        segment.put((byte) 0xff).put((byte) 0xe1).putShort((short) length).put(exifHeader).put(tiff);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2);
        out.writeBytes(segment.array());
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /**
     * A tiny, well-formed PNG header claiming 50000x50000 pixels: decoding it
     * would need about 10 GB. Valid signature, IHDR and CRC, no image data.
     */
    public static byte[] decompressionBomb() {
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(50_000).putInt(50_000).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        writeChunk(out, "IHDR", ihdr.array());
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** A GIF: a real image, but not a format the service accepts. */
    public static byte[] gif() {
        return encode(face(0), "gif");
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
