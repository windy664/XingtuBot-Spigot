package org.windy.xingtubot.common.image;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文字生图（纯 Java2D，不依赖 SCF）：加载本地背景图 + 自带中文字体，把文字居中排版画上去，
 * 输出 PNG 字节。配合 OpenAPI 的 file_data base64 直传发群。
 *
 * <p>背景图与字体从插件数据目录读取，模板可扩展（一个模板 = 一张背景图 + 一组排版参数）。
 * 服务器 JVM 默认无中文字体，故必须自带 .ttf。
 */
public class TextImageRenderer {

    private final Font baseFont;       // 自带中文字体（未加载成功则用逻辑字体兜底）
    private final File templateDir;    // 背景图目录
    private final String fontSource;   // 实际用的字体来源描述（供启动日志）

    // 常见系统中文字体（按优先级），Windows/部分 Linux 自带，免放字体文件
    private static final String[] SYSTEM_CJK_FONTS = {
            "Microsoft YaHei", "微软雅黑", "Microsoft JhengHei",
            "PingFang SC", "DengXian", "等线",
            "SimHei", "黑体", "SimSun", "宋体",
            "Noto Sans CJK SC", "Source Han Sans SC", "Source Han Sans CN",
            "WenQuanYi Micro Hei", "WenQuanYi Zen Hei"
    };

    /**
     * 字体优先级：① 自带 fontFile（若提供）② 系统中文字体（微软雅黑等）③ 逻辑字体兜底。
     *
     * @param fontFile   可选的自带 .ttf；为 null 或不存在则自动找系统中文字体
     * @param templateDir 背景图目录（模板名.png）
     */
    public TextImageRenderer(File fontFile, File templateDir) {
        this.templateDir = templateDir;

        Font chosen = null;
        String src = null;
        // ① 自带字体文件优先（买家想统一品牌字体时用）
        if (fontFile != null && fontFile.exists()) {
            try {
                chosen = Font.createFont(Font.TRUETYPE_FONT, fontFile);
                src = "自带字体 " + fontFile.getName();
            } catch (Exception ignored) {
            }
        }
        // ② 系统中文字体（Windows 一定有微软雅黑；装了中文的 Linux 也有）
        if (chosen == null) {
            try {
                java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
                java.util.Set<String> available = new java.util.HashSet<>();
                for (String n : ge.getAvailableFontFamilyNames()) available.add(n);
                for (String name : SYSTEM_CJK_FONTS) {
                    if (available.contains(name)) {
                        Font f = new Font(name, Font.PLAIN, 40);
                        if (f.canDisplay('中')) {
                            chosen = f;
                            src = "系统字体 " + name;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // ③ 逻辑字体兜底（可能中文方块，但不报错）
        if (chosen == null) {
            chosen = new Font("SansSerif", Font.PLAIN, 40);
            src = "兜底字体（中文可能显示为方块，建议放一个 font.ttf）";
        }
        this.baseFont = chosen;
        this.fontSource = src;
    }

    /** 实际使用的字体来源（供启动日志，让用户知道中文能不能正常显示）。 */
    public String getFontSource() {
        return fontSource;
    }

    /**
     * 渲染：用 template 对应的背景图，把 text 居中排版画上，返回 PNG 字节。
     * 背景图缺失时用纯色渐变兜底。
     */
    public byte[] render(String template, String text) throws IOException {
        BufferedImage bg = loadBackground(template);
        int w = bg.getWidth(), h = bg.getHeight();

        Graphics2D g = bg.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // 自适应字号：按图宽和文字量估个初始字号，过宽再缩
        int margin = w / 10;
        int maxTextWidth = w - margin * 2;
        int fontSize = Math.max(24, w / 12);
        Font font = baseFont.deriveFont(Font.BOLD, (float) fontSize);

        List<String> lines = wrap(g, text, font, maxTextWidth);
        // 行太多则缩小字号重排
        while (lines.size() * fontSize * 1.4 > h - margin * 2 && fontSize > 18) {
            fontSize -= 4;
            font = baseFont.deriveFont(Font.BOLD, (float) fontSize);
            lines = wrap(g, text, font, maxTextWidth);
        }
        g.setFont(font);

        FontRenderContext frc = g.getFontRenderContext();
        int lineHeight = (int) (fontSize * 1.4);
        int totalHeight = lines.size() * lineHeight;
        int y = (h - totalHeight) / 2 + fontSize;

        for (String line : lines) {
            Rectangle2D b = font.getStringBounds(line, frc);
            int x = (int) ((w - b.getWidth()) / 2);
            // 描边（深色）提升可读性
            g.setColor(new Color(0, 0, 0, 140));
            g.drawString(line, x + 2, y + 2);
            g.setColor(Color.WHITE);
            g.drawString(line, x, y);
            y += lineHeight;
        }
        g.dispose();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(bg, "png", bout);
        return bout.toByteArray();
    }

    /** 按宽度自动换行（按字符断，适配中文）。 */
    private List<String> wrap(Graphics2D g, String text, Font font, int maxWidth) {
        FontRenderContext frc = g.getFontRenderContext();
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(cur.toString());
                cur.setLength(0);
                continue;
            }
            cur.append(c);
            if (font.getStringBounds(cur.toString(), frc).getWidth() > maxWidth) {
                cur.deleteCharAt(cur.length() - 1);
                lines.add(cur.toString());
                cur.setLength(0);
                cur.append(c);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    /** 加载模板背景图：templateDir/模板名.png；缺失则生成渐变兜底图。 */
    private BufferedImage loadBackground(String template) throws IOException {
        if (templateDir != null && template != null) {
            File f = new File(templateDir, template + ".png");
            if (f.exists()) {
                BufferedImage img = ImageIO.read(f);
                if (img != null) return toRGB(img);
            }
        }
        // 兜底：渐变色块（不同模板名给不同色调）
        return gradient(800, 450, template == null ? "" : template);
    }

    private BufferedImage toRGB(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private BufferedImage gradient(int w, int h, String seedStr) {
        int seed = seedStr.hashCode();
        Color c1 = new Color((seed & 0x7F) + 40, ((seed >> 8) & 0x7F) + 40, ((seed >> 16) & 0x7F) + 80);
        Color c2 = new Color(20, 20, 40);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new java.awt.GradientPaint(0, 0, c1, w, h, c2));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }
}
