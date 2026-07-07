package org.windy.xingtubot.common.bot;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.windy.xingtubot.common.platform.BotLogger;
import org.windy.xingtubot.common.util.Http;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * QQ 机器人扫码接入流程。
 *
 * <p>仿照 openclaw / hermes-agent 的 onboard.ts 实现：
 * <ol>
 *   <li>生成随机 AES-256 密钥</li>
 *   <li>POST {@code q.qq.com/lite/create_bind_task} → 拿 task_id</li>
 *   <li>拼二维码 URL 让用户用 QQ 扫码</li>
 *   <li>轮询 {@code q.qq.com/lite/poll_bind_result} → 拿到 app_id + 加密的 secret</li>
 *   <li>AES-256-GCM 解密 secret</li>
 * </ol>
 *
 * <p>扫码成功后返回 app_id 和 client_secret，可直接写入 config.yml。
 */
public class QQOnboard {

    private static final String PORTAL_HOST = "q.qq.com";
    private static final String CREATE_PATH = "/lite/create_bind_task";
    private static final String POLL_PATH = "/lite/poll_bind_result";
    private static final String QR_URL_TEMPLATE =
            "https://q.qq.com/qqbot/openclaw/connect.html?task_id=%s";

    private static final int POLL_INTERVAL_SEC = 2;
    private static final int TIMEOUT_SEC = 600; // 10 分钟
    private static final int MAX_REFRESHES = 3;

    private final BotLogger logger;

    public QQOnboard(BotLogger logger) {
        this.logger = logger;
    }

    /**
     * 执行扫码接入流程（阻塞直到成功或超时）。
     *
     * @return 扫码结果（含 app_id + client_secret），失败返回 null
     */
    public ScanResult run() {
        for (int refresh = 0; refresh <= MAX_REFRESHES; refresh++) {
            try {
                // ① 创建绑定任务
                String aesKey = generateBase64Key();
                String taskId = createBindTask(aesKey);

                // ② 显示二维码（字符画 + 链接）
                String qrUrl = String.format(QR_URL_TEMPLATE, urlEncode(taskId));
                log("");
                log("  QQ 机器人扫码接入");
                log("  请用 QQ 手机版扫描下方二维码：");
                log("");
                renderQrToConsole(qrUrl);
                log("");
                log("  无法扫码？在 QQ 中打开此链接：");
                log("  " + qrUrl);
                log("");

                // ③ 轮询结果
                long deadline = System.currentTimeMillis() + TIMEOUT_SEC * 1000L;
                while (System.currentTimeMillis() < deadline) {
                    PollResult poll = pollBindResult(taskId);
                    if (poll == null) {
                        TimeUnit.SECONDS.sleep(POLL_INTERVAL_SEC);
                        continue;
                    }

                    if (poll.status == 2) { // COMPLETED
                        String clientSecret = decryptSecret(poll.encryptedSecret, aesKey);
                        log("✅ 扫码成功！App ID: " + poll.appId);
                        if (poll.userOpenid != null && !poll.userOpenid.isEmpty()) {
                            log("   扫码用户 OpenID: " + poll.userOpenid);
                        }
                        return new ScanResult(poll.appId, clientSecret, poll.userOpenid);
                    }

                    if (poll.status == 3) { // EXPIRED
                        if (refresh >= MAX_REFRESHES) {
                            log("❌ 二维码已过期 " + MAX_REFRESHES + " 次，请重新运行");
                            return null;
                        }
                        log("⏰ 二维码已过期，正在刷新... (" + (refresh + 1) + "/" + MAX_REFRESHES + ")");
                        break; // 外层 for 循环重新创建任务
                    }

                    // status == 1 (PENDING) → 继续等
                    TimeUnit.SECONDS.sleep(POLL_INTERVAL_SEC);
                }

                log("⏰ 等待超时（" + TIMEOUT_SEC + " 秒）");

            } catch (Exception e) {
                log("❌ 扫码流程异常: " + e.getMessage());
                return null;
            }
        }
        return null;
    }

    // ========================= API 调用 =========================

    private String createBindTask(String aesKeyBase64) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("key", aesKeyBase64);

        String resp = httpPost("https://" + PORTAL_HOST + CREATE_PATH, body.toString());
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();

        if (json.has("retcode") && json.get("retcode").getAsInt() != 0) {
            throw new IOException("create_bind_task 失败: " + json.get("msg").getAsString());
        }

        String taskId = json.getAsJsonObject("data").get("task_id").getAsString();
        log("创建绑定任务成功: task_id=" + taskId);
        return taskId;
    }

    private static class PollResult {
        final int status;
        final String appId;
        final String encryptedSecret;
        final String userOpenid;

        PollResult(int status, String appId, String encryptedSecret, String userOpenid) {
            this.status = status;
            this.appId = appId;
            this.encryptedSecret = encryptedSecret;
            this.userOpenid = userOpenid;
        }
    }

    private PollResult pollBindResult(String taskId) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("task_id", taskId);

        String resp = httpPost("https://" + PORTAL_HOST + POLL_PATH, body.toString());
        JsonObject json = JsonParser.parseString(resp).getAsJsonObject();

        if (json.has("retcode") && json.get("retcode").getAsInt() != 0) {
            return null; // 忽略临时错误
        }

        JsonObject data = json.getAsJsonObject("data");
        int status = data.has("status") ? data.get("status").getAsInt() : 0;

        if (status == 0) return null; // NONE

        return new PollResult(
                status,
                data.has("bot_appid") ? data.get("bot_appid").getAsString() : "",
                data.has("bot_encrypt_secret") ? data.get("bot_encrypt_secret").getAsString() : "",
                data.has("user_openid") ? data.get("user_openid").getAsString() : ""
        );
    }

    // ========================= 加密 =========================

    /**
     * 生成 256 位随机 AES 密钥，返回 base64 编码。
     */
    private static String generateBase64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * AES-256-GCM 解密。
     * 密文布局：IV (12 bytes) ‖ ciphertext ‖ AuthTag (16 bytes)
     */
    private static String decryptSecret(String encryptedBase64, String keyBase64) throws Exception {
        byte[] key = Base64.getDecoder().decode(keyBase64);
        byte[] raw = Base64.getDecoder().decode(encryptedBase64);

        byte[] iv = new byte[12];
        byte[] ciphertextWithTag = new byte[raw.length - 12];
        System.arraycopy(raw, 0, iv, 0, 12);
        System.arraycopy(raw, 12, ciphertextWithTag, 0, ciphertextWithTag.length);

        SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv); // 128-bit auth tag
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

        byte[] plaintext = cipher.doFinal(ciphertextWithTag);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    // ========================= HTTP =========================

    private String httpPost(String urlStr, String jsonBody) throws IOException {
        Http.Response resp = Http.post(urlStr)
                .json(jsonBody)
                .header("Accept", "application/json")
                .send();
        if (resp.code >= 400) {
            throw new IOException("HTTP " + resp.code + ": " + resp.body);
        }
        return resp.body;
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    // ========================= 结果 =========================

    public static class ScanResult {
        public final String appId;
        public final String clientSecret;
        public final String userOpenid;

        public ScanResult(String appId, String clientSecret, String userOpenid) {
            this.appId = appId;
            this.clientSecret = clientSecret;
            this.userOpenid = userOpenid;
        }
    }

    /**
     * 用 ZXing 生成 QR 码并以 Unicode 方块字符渲染到控制台。
     * 每行用两个竖排像素合并为一个字符（▀=上黑下白，▄=上白下黑，█=全黑，空格=全白），
     * 使二维码在终端中更紧凑。
     */
    private void renderQrToConsole(String text) {
        try {
            com.google.zxing.qrcode.QRCodeWriter writer = new com.google.zxing.qrcode.QRCodeWriter();
            java.util.Map<com.google.zxing.EncodeHintType, Object> hints = new java.util.HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.ERROR_CORRECTION, "M");
            hints.put(com.google.zxing.EncodeHintType.MARGIN, "1");
            com.google.zxing.common.BitMatrix matrix = writer.encode(
                    text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, hints);

            int width = matrix.getWidth();
            int height = matrix.getHeight();

            // 每两行合并为一行（▀▄ 字符）
            for (int y = 0; y < height; y += 2) {
                StringBuilder line = new StringBuilder("  ");
                for (int x = 0; x < width; x++) {
                    boolean top = matrix.get(x, y);
                    boolean bottom = (y + 1 < height) ? matrix.get(x, y + 1) : false;
                    if (top && bottom) {
                        line.append('█'); // █ 全黑
                    } else if (top && !bottom) {
                        line.append('▀'); // ▀ 上黑下白
                    } else if (!top && bottom) {
                        line.append('▄'); // ▄ 上白下黑
                    } else {
                        line.append(' ');       // 全白
                    }
                }
                log(line.toString());
            }
        } catch (Exception e) {
            // ZXing 不可用时降级为纯链接
            log("  [QR 码生成失败: " + e.getMessage() + "]");
        }
    }

    private void log(String msg) {
        if (logger != null) {
            logger.info("[Onboard] " + msg);
        } else {
            System.out.println("[Onboard] " + msg);
        }
    }
}
