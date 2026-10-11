package com.hotel.hms.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.hotel.hms.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码渲染：把一段文本编成 PNG 字节流。
 *
 * <p>只依赖 zxing 的 {@code core}（出 BitMatrix），PNG 编码交给 JDK 自带的
 * {@link ImageIO}——因此不需要 {@code zxing:javase}，也不会带进
 * jai-imageio / jcommander 这些只在桌面扫图时才用得上的依赖。
 */
@Slf4j
@Service
public class QrCodeService {

    private static final int DEFAULT_SIZE = 320;
    private static final int QUIET_ZONE = 1;
    private static final int COLOR_BLACK = 0xFF000000;
    private static final int COLOR_WHITE = 0xFFFFFFFF;

    /**
     * @param text 待编码内容（扫码页完整 URL）
     * @param size 边长像素
     */
    public byte[] png(String text, int size) {
        if (text == null || text.isBlank()) {
            throw new BusinessException("二维码内容不能为空");
        }
        int px = size <= 0 ? DEFAULT_SIZE : Math.min(size, 1024);
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
        hints.put(EncodeHintType.MARGIN, QUIET_ZONE);
        try {
            BitMatrix matrix = new MultiFormatWriter()
                    .encode(text, BarcodeFormat.QR_CODE, px, px, hints);
            BufferedImage image = render(matrix);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (IOException | WriterException ex) {
            log.warn("二维码生成失败：{}", ex.getMessage());
            throw new BusinessException("二维码生成失败：" + ex.getMessage());
        }
    }

    private BufferedImage render(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, matrix.get(x, y) ? COLOR_BLACK : COLOR_WHITE);
            }
        }
        return image;
    }
}
