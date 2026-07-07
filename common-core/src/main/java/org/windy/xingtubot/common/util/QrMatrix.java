package org.windy.xingtubot.common.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码矩阵生成（平台无关）：内容 → {@code boolean[][]}（true=黑点）。
 *
 * <p>复用项目已依赖的 ZXing（扫码接入也用它）。放在 common 让 spigot/velocity
 * 都能拿矩阵，平台侧只负责把矩阵画到地图/图片——zxing 不暴露给平台模块编译期。
 */
public final class QrMatrix {

    private QrMatrix() {
    }

    /**
     * 生成二维码矩阵。
     *
     * @param content 编码内容（加群链接 / 群号 / 任意文本）
     * @return {@code boolean[row][col]}，true=黑点；失败返回 null
     */
    public static boolean[][] encode(String content) {
        if (content == null || content.trim().isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);                       // 留白：聊天二维码需要够的安静区才扫得出
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);

            BitMatrix m = new QRCodeWriter().encode(
                    content.trim(), BarcodeFormat.QR_CODE, 0, 0, hints);
            int w = m.getWidth();
            int h = m.getHeight();
            boolean[][] out = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    out[y][x] = m.get(x, y);
                }
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
