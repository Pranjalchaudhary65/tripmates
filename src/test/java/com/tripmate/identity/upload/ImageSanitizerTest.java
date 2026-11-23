package com.tripmate.identity.upload;

import com.tripmate.platform.error.ApiProblemException;
import com.tripmate.support.TestImages;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageSanitizerTest {

    private final ImageSanitizer sanitizer = new ImageSanitizer();

    @Test
    void exifIsGoneFromTheStoredBytes() {
        ImageSanitizer.SanitizedImage image = sanitizer.sanitize(TestImages.jpegWithExif());

        String stored = new String(image.jpeg(), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain("Exif", "GPSLatitude", "secret-home-address");
        assertThat(image.pixels().getWidth()).isEqualTo(360);
    }

    @Test
    void aPngComesOutAsAJpeg() {
        byte[] out = sanitizer.sanitize(TestImages.png(0)).jpeg();

        assertThat(out[0] & 0xff).isEqualTo(0xff);
        assertThat(out[1] & 0xff).isEqualTo(0xd8);
    }

    @Test
    void bytesAppendedAfterTheImageAreNotCarriedOver() {
        byte[] jpeg = TestImages.jpeg(0);
        byte[] polyglot = new byte[jpeg.length + 40];
        System.arraycopy(jpeg, 0, polyglot, 0, jpeg.length);
        byte[] script = "<script>alert('stored')</script>".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(script, 0, polyglot, jpeg.length, script.length);

        String stored = new String(sanitizer.sanitize(polyglot).jpeg(), StandardCharsets.ISO_8859_1);

        assertThat(stored).doesNotContain("<script>");
    }

    @Test
    void largePhotosAreScaledDownBeforeStorage() {
        byte[] big = encode(new BufferedImage(3000, 2000, BufferedImage.TYPE_INT_RGB), "jpeg");

        BufferedImage stored = sanitizer.sanitize(big).pixels();

        assertThat(stored.getWidth()).isEqualTo(ImageSanitizer.STORED_MAX_EDGE);
        assertThat(stored.getHeight()).isEqualTo(853);
    }

    @Test
    void otherFormatsBombsAndJunkAreRefused() {
        assertInvalid(TestImages.gif());
        assertInvalid(TestImages.decompressionBomb());
        assertInvalid("not an image at all".getBytes(StandardCharsets.UTF_8));
        assertInvalid(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0, 0, 0});
        assertInvalid(new byte[0]);
        assertInvalid(null);
        assertInvalid(new byte[ImageSanitizer.MAX_BYTES + 1]);
    }

    @Test
    void tinyImagesAreRefused() {
        assertInvalid(encode(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "png"));
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private void assertInvalid(byte[] upload) {
        assertThatThrownBy(() -> sanitizer.sanitize(upload))
                .isInstanceOf(ApiProblemException.class)
                .hasMessage("invalid_image");
    }
}
