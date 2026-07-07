package org.windy.xingtubot.ext.xtauth.binding;

import org.windy.xingtubot.common.util.Http;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 头像下载 + 感知哈希（dHash）比对 + 低信息量（默认头像）检测。
 *
 * <p>dHash 流程：缩放到 9×8 灰度 → 每行相邻像素比较得 64 位指纹 → 比较两指纹汉明距离。
 * 对缩放、轻微压缩差异稳健，不要求原图同尺寸。
 *
 * <p>低信息量检测：算缩放后灰度的方差。QQ 默认/纯色头像内容单调、方差很低，
 * 据此可拒绝默认头像参与绑定，避免「两人都用默认灰头像 → 误绑」。
 *
 * <p>纯 JDK 实现（java.awt / javax.imageio）；图像处理不需要显示环境。
 * 白名单绑定专属，归 xt-auth；下载工具 {@link Http} 复用 common-core。
 */
public final class AvatarMatcher {

    /** 候选匹配阈值：汉明距离 <= 此值视为同一头像（略宽松以容忍接口差异）。 */
    public static final int DEFAULT_THRESHOLD = 12;

    /** 高置信阈值：距离 <= 此值认为高度可信；超过但 <= DEFAULT_THRESHOLD 则记日志留痕。 */
    public static final int HIGH_CONFIDENCE = 6;

    /** 低信息量方差下限：灰度方差低于此值视为默认/纯色头像，拒绝绑定。可按实测调整。 */
    public static final double LOW_INFO_VARIANCE = 50.0;

    /**
     * 源图最小边长下限：低于此值视为「占位图/取不到真头像」。
     *
     * <p>实测：openid 头像接口（{@code q.qlogo.cn/qqapp/{appId}/{openid}/640}）在 <b>appId 配错或
     * 取不到</b>时，会以 HTTP 200 回落成一张 <b>40×40 的默认企鹅占位图</b>（而非报错）。这张企鹅
     * 黑白橙对比强、方差高达 5000+，<b>{@link #LOW_INFO_VARIANCE} 方差检测拦不住它</b>，但它尺寸
     * 极小——真头像一律是 640×640。故用尺寸下限来识别这种「假头像」。
     */
    public static final int MIN_DIMENSION = 100;

    private static final int W = 9, H = 8;

    private AvatarMatcher() {
    }

    /** 下载并计算指纹（含 dHash、方差、源图尺寸）；失败抛 IOException。 */
    public static Fingerprint fingerprintFromUrl(String url) throws IOException {
        BufferedImage img = download(url);
        if (img == null) throw new IOException("无法解析图片: " + url);
        int[] gray = smallGray(img);
        return new Fingerprint(dHash(gray), variance(gray), img.getWidth(), img.getHeight());
    }

    public static boolean isSimilar(long hashA, long hashB, int threshold) {
        return hammingDistance(hashA, hashB) <= threshold;
    }

    public static int hammingDistance(long a, long b) {
        return Long.bitCount(a ^ b);
    }

    // ---------------- 内部实现 ----------------

    private static BufferedImage download(String url) throws IOException {
        byte[] data = Http.get(url).userAgent("XingtuBot").timeout(8000, 8000).bytes();
        return ImageIO.read(new ByteArrayInputStream(data));
    }

    /** 缩放到 9×8 灰度数组（亮度加权）。 */
    private static int[] smallGray(BufferedImage src) {
        BufferedImage small = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.drawImage(src.getScaledInstance(W, H, java.awt.Image.SCALE_SMOOTH), 0, 0, null);
        g.dispose();

        int[] gray = new int[W * H];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = small.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gg = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                gray[y * W + x] = (r * 299 + gg * 587 + b * 114) / 1000;
            }
        }
        return gray;
    }

    /** 每行相邻像素比较生成 64 位指纹。 */
    private static long dHash(int[] gray) {
        long hash = 0L;
        int bit = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W - 1; x++) {
                if (gray[y * W + x] > gray[y * W + x + 1]) hash |= (1L << bit);
                bit++;
            }
        }
        return hash;
    }

    /** 灰度方差（衡量图像信息量，低 = 单调/默认头像）。 */
    private static double variance(int[] gray) {
        double mean = 0;
        for (int v : gray) mean += v;
        mean /= gray.length;
        double var = 0;
        for (int v : gray) var += (v - mean) * (v - mean);
        return var / gray.length;
    }

    /** 头像指纹：dHash + 信息量 + 源图尺寸。 */
    public static final class Fingerprint {
        public final long hash;
        public final double variance;
        public final int srcWidth;
        public final int srcHeight;

        Fingerprint(long hash, double variance, int srcWidth, int srcHeight) {
            this.hash = hash;
            this.variance = variance;
            this.srcWidth = srcWidth;
            this.srcHeight = srcHeight;
        }

        /** 是否疑似默认/纯色头像（信息量过低）。 */
        public boolean isLowInfo() {
            return variance < LOW_INFO_VARIANCE;
        }

        /** 是否疑似占位图/取不到真头像（源图过小，如 appId 配错回落的 40×40 企鹅）。 */
        public boolean isTooSmall() {
            return srcWidth < MIN_DIMENSION || srcHeight < MIN_DIMENSION;
        }

        /** 是否可用于比对：既非纯色默认头像、也非占位小图。 */
        public boolean isUsable() {
            return !isLowInfo() && !isTooSmall();
        }
    }
}
