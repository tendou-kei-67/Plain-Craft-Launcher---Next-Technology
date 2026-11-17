package io.pclnt.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

final class ResourceIconImages {
    private ResourceIconImages() {}

    static Image decode(byte[] bytes) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("资源图片格式无法识别");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || (long) w * h > 16_000_000 || Math.max(w, h) > 8192) {
                    throw new IOException("资源图片尺寸无效");
                }
                // Use one pixel grid for all formats, including WebP icons from project CDNs.
                int sample = Math.max(1, Math.max(w, h) / 100);
                var parameters = reader.getDefaultReadParam();
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                var image = reader.read(0, parameters);
                w = image.getWidth();
                h = image.getHeight();
                int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
                var rendered = new WritableImage(w, h);
                rendered.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), pixels, 0, w);
                return rendered;
            } finally {
                reader.dispose();
            }
        }
    }
}
