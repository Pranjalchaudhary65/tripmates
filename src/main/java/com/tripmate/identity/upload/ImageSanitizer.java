package com.tripmate.identity.upload;

import com.tripmate.identity.service.VerificationProblem;
import com.tripmate.platform.error.ApiProblemException;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Turns an uploaded photo into a fresh JPEG made only of its pixels.
 *
 * <ol>
 *   <li>Size: at most {@link #MAX_BYTES}; the multipart limit has already
 *       refused anything larger before it was read into memory.</li>
 *   <li>Magic bytes: JPEG or PNG only, decided from the first bytes, never
 *       from the file name or the client's Content-Type, and the decoder used
 *       must be the one for that format.</li>
 *   <li>Dimensions are read from the header before any pixel is decoded, so
 *       a small file claiming 50000x50000 pixels (a decompression bomb) is
 *       refused without allocating anything.</li>
 *   <li>The pixels are decoded with metadata ignored, scaled down to at most
 *       {@link #STORED_MAX_EDGE} on the long side, and encoded again as a new
 *       JPEG. The original bytes are never stored, so EXIF (GPS position,
 *       camera serial, time taken), ICC profiles, comments and anything
 *       appended after the image are gone, along with any polyglot payload.</li>
 * </ol>
 */
@Component
public class ImageSanitizer {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    static final int MIN_EDGE = 64;

    static final int MAX_EDGE = 4096;

    static final int STORED_MAX_EDGE = 1280;

    private static final float JPEG_QUALITY = 0.9f;

    public SanitizedImage sanitize(byte[] upload) {
        if (upload == null || upload.length == 0 || upload.length > MAX_BYTES) {
            throw invalid();
        }
        String format = detectFormat(upload);
        BufferedImage decoded = decode(upload, format);
        BufferedImage pixels = toRgb(decoded, scale(decoded.getWidth(), decoded.getHeight()));
        return new SanitizedImage(encodeJpeg(pixels), pixels);
    }

    /** Re-reads an image this class wrote earlier, such as a stored profile photo. */
    public BufferedImage read(byte[] storedJpeg) {
        return decode(storedJpeg, "jpeg");
    }

    private static String detectFormat(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff) {
            return "jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
        if (bytes.length >= png.length) {
            boolean matches = true;
            for (int i = 0; i < png.length; i++) {
                matches &= bytes[i] == png[i];
            }
            if (matches) {
                return "png";
            }
        }
        throw invalid();
    }

    private static BufferedImage decode(byte[] bytes, String format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = readerFor(in, format);
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < MIN_EDGE || height < MIN_EDGE || width > MAX_EDGE || height > MAX_EDGE) {
                    throw invalid();
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalid();
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (ApiProblemException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw invalid();
        }
    }

    private static ImageReader readerFor(ImageInputStream in, String format) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            try {
                if (reader.getFormatName().toLowerCase(Locale.ROOT).equals(format)) {
                    return reader;
                }
            } catch (IOException e) {
                reader.dispose();
                throw invalid();
            }
            reader.dispose();
        }
        throw invalid();
    }

    private static double scale(int width, int height) {
        int longest = Math.max(width, height);
        return longest <= STORED_MAX_EDGE ? 1.0 : (double) STORED_MAX_EDGE / longest;
    }

    /** Drawn onto an opaque white canvas: JPEG has no alpha, and a transparent PNG must not turn black. */
    private static BufferedImage toRgb(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return rgb;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("The JDK has no JPEG writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new IllegalStateException("Encoding a JPEG in memory failed", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApiProblemException invalid() {
        return new ApiProblemException(VerificationProblem.INVALID_IMAGE);
    }

    /**
     * @param jpeg   the only bytes ever stored
     * @param pixels the same image, already decoded
     */
    public record SanitizedImage(byte[] jpeg, BufferedImage pixels) {

        @Override
        public String toString() {
            return "SanitizedImage[" + pixels.getWidth() + "x" + pixels.getHeight() + ", " + jpeg.length + " bytes]";
        }
    }
}
