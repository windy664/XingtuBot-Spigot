package org.windy.xingtubot.module.whitelist;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fr.xephi.authme.api.v3.AuthMeApi;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.windy.xingtubot.XingtuBot;
import org.windy.xingtubot.event.GuildMessageEvent;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Type;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public class WhitelistModule implements Listener {
    private final XingtuBot plugin;
    private File whiteListFile;
    private final Gson gson = new Gson();
    public final List<WhiteListEntry> whiteListEntries = new ArrayList<>();

    private final Map<String, String> pendingQQ = new HashMap<>();   // playerName -> QQ
    private final Map<String, String> pendingCode = new HashMap<>(); // playerName -> code

    private static final Pattern QQ_PATTERN = Pattern.compile("^[1-9][0-9]{4,14}$");
    private static final Pattern CODE_PATTERN = Pattern.compile("^\\d{4}$");
    private static WhitelistModule instance;
    public WhitelistModule(XingtuBot plugin) {
        this.plugin = plugin;
        instance = this;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        whiteListFile = new File(plugin.getDataFolder(), "whitelist.json");

        if (!whiteListFile.exists()) {
            try {
                plugin.getDataFolder().mkdirs();
                whiteListFile.createNewFile();
                saveWhiteList();
            } catch (IOException e) {
                plugin.getLogger().severe("无法创建 whitelist.json: " + e.getMessage());
            }
        }
        loadWhiteList();

        //处理AuthMe的消息文件

        Plugin authMePlugin = plugin.getServer().getPluginManager().getPlugin("AuthMe");
        if (authMePlugin == null || !authMePlugin.isEnabled()) {
            plugin.getLogger().severe("❌ AuthMe插件未启用或未安装!");
            return;
        }

        File authMeDataFolder = authMePlugin.getDataFolder();
        File messagesDir = new File(authMeDataFolder, "messages");


        // 处理所有YML文件
        File[] ymlFiles = messagesDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (ymlFiles == null || ymlFiles.length == 0) {
            plugin.getLogger().warning("⚠️ AuthMe消息目录中没有找到YML文件");
            return;
        }

        for (File ymlFile : ymlFiles) {
            try {
                YamlConfiguration config = YamlConfiguration.loadConfiguration(ymlFile);

                // 动态读取当前插件的配置参数
                String registerPrompt = plugin.getConfig().getString("register-prompt", "&a请注册账号");
                String loginPrompt = plugin.getConfig().getString("login-mssage", "&c请登录账号");

                // 修改配置项（支持多级路径）
                config.set("registration.register_request", registerPrompt);
                config.set("login.login_request", loginPrompt);

                // 强制保存所有配置（包括注释）
                config.options().copyDefaults(true);
                config.save(ymlFile);

                plugin.getLogger().info("✅ 已更新文件: " + ymlFile.getName());
            } catch (IOException e) {
                plugin.getLogger().severe("❌ 修改文件 " + ymlFile.getName() + " 失败: " + e.getMessage());
            }
        }
        if (authMePlugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "authme reload");
            });
        }
    }

    private void loadWhiteList() {
        try (Reader reader = new FileReader(whiteListFile)) {
            Type listType = new TypeToken<List<WhiteListEntry>>() {}.getType();
            List<WhiteListEntry> loaded = gson.fromJson(reader, listType);
            if (loaded != null) {
                whiteListEntries.clear();
                whiteListEntries.addAll(loaded);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("读取白名单失败: " + e.getMessage());
        }
    }

    public void saveWhiteList() {
        try (Writer writer = new FileWriter(whiteListFile)) {
            gson.toJson(whiteListEntries, writer);
        } catch (IOException e) {
            plugin.getLogger().severe("保存白名单失败: " + e.getMessage());
        }
    }

    private boolean isPlayerVerified(String playerName) {
        return whiteListEntries.stream().anyMatch(entry -> entry.player.equalsIgnoreCase(playerName));
    }

    private String generateCode() {
        return String.format("%04d", ThreadLocalRandom.current().nextInt(0, 10000));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isPlayerVerified(player.getName())) {
            //       player.sendMessage("§6请发送你的QQ号码以完成验证（请输入5~15位数字）");
            pendingQQ.put(player.getName(), null);
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String name = player.getName();
        String msg = event.getMessage();

        // 若处于待输入QQ状态
        if (pendingQQ.containsKey(name) && pendingQQ.get(name) == null) {
            event.setCancelled(true);
            if (!QQ_PATTERN.matcher(msg).matches()) {
                player.sendMessage("§cQQ号格式不正确，请重新输入（5~15位数字）");
                return;
            }
            // 正确QQ
            pendingQQ.put(name, msg);
            String code = generateCode();
            pendingCode.put(name, code);
            player.sendMessage("§a您的验证码为: §e" + code + "，请发送至QQ群完成验证。");
            String titlePattern = plugin.getConfig().getString("title-code");
            String title = MessageFormat.format(titlePattern, code);
            player.sendTitle(title, plugin.getConfig().getString("subtitle-code"), 10, 200, 20);
            // 异步下载 QQ 头像
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String qqUrl = "https://q.qlogo.cn/headimg_dl?dst_uin=" + msg + "&spec=640";
                    downloadImage(qqUrl, "qq_" + name + ".jpg");
                    plugin.getLogger().info("已下载QQ头像: " + msg);
                } catch (IOException e) {
                    plugin.getLogger().warning("下载QQ头像失败: " + e.getMessage());
                }
            });
        }
    }

    @EventHandler
    public void onGuildMessage(GuildMessageEvent event) {
        String message = event.getMessage();
        String formId = event.getFormId();

        if (plugin.getConfig().getString("login-prompt","登录").equals(message)) { // 使用 equals 比较字符串内容
            // 查找白名单中 formId 对应的玩家名
            String playerName = null;
            for (WhiteListEntry entry : whiteListEntries) {
                if (entry.formId.equals(formId)) {
                    playerName = entry.player;
                    break;
                }
            }

            if (playerName == null) {
                event.reply("⚠️ 您未申请白名单！");
                return;
            }

            Player player = Bukkit.getPlayerExact(playerName);
            if (player != null && player.isOnline()) {
                // 调用 AuthMe 强制登录
                AuthMeApi.getInstance().forceLogin(player);
                event.reply(plugin.getConfig().getString("login-success"));
            } else {
                event.reply("⚠️ 玩家 " + playerName + " 当前不在线，无法自动登录。");
            }

            return;
        }


        if (!CODE_PATTERN.matcher(message).matches()) return;

        for (Map.Entry<String, String> entry : pendingCode.entrySet()) {
            String player = entry.getKey();
            String code = entry.getValue();

            if (code.equals(message)) {

                String qq = pendingQQ.get(player);
                String formIdUrl = "https://q.qlogo.cn/qqapp/102093306/" + formId + "/640";
                File qqImg = new File(plugin.getDataFolder(), "cache/qq_" + player + ".jpg");
                File formImg;

                try {
                    formImg = downloadImage(formIdUrl, "form_" + player + ".jpg");
                } catch (IOException e) {
                    plugin.getLogger().warning("下载FormId头像失败: " + e.getMessage());
                    return;
                }

                if (!areImagesSimilar(qqImg, formImg)) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        event.reply("⚠️ 验证失败：QQ头像与FormId头像不一致！");
                    });
                    return;
                }


                int nextIndex = whiteListEntries.size() + 1;
                whiteListEntries.add(new WhiteListEntry(nextIndex, formId, player, code, qq));
                saveWhiteList();

                pendingQQ.remove(player);
                pendingCode.remove(player);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    event.reply("✅ 验证成功！恭喜玩家 " + player + " (QQ: " + qq + ") 申请通过！");
                    Player p = Bukkit.getPlayerExact(player);
                    if (p != null && p.isOnline()) {
                        p.sendMessage("§a验证成功！你已可以正常游玩了！");
                        AuthMeApi.getInstance().forceRegister(p, plugin.getConfig().getString("default-password","yC[c=a8G"), true); // true = 自动登录
                    }
                });
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            event.reply("❌ 验证失败：验证码无效或已使用。");
        });
    }

    public static class WhiteListEntry {
        int index; // 序号
        String timestamp; // 时间戳
        public String formId;
        public String player;
        String code;
        public String qq;

        public WhiteListEntry(int index, String formId, String player, String code, String qq) {
            this.index = index;
            this.timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            this.formId = formId;
            this.player = player;
            this.code = code;
            this.qq = qq;
        }
    }

    private File downloadImage(String url, String filename) throws IOException {
        File outFile = new File(plugin.getDataFolder(), "cache/" + filename);
        outFile.getParentFile().mkdirs();
        try (InputStream in = new java.net.URL(url).openStream();
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return outFile;
    }

    private boolean areImagesSimilar(File file1, File file2) {
        try {
            BufferedImage img1 = javax.imageio.ImageIO.read(file1);
            BufferedImage img2 = javax.imageio.ImageIO.read(file2);
            if (img1.getWidth() != img2.getWidth() || img1.getHeight() != img2.getHeight()) return false;

            long diff = 0;
            for (int y = 0; y < img1.getHeight(); y++) {
                for (int x = 0; x < img1.getWidth(); x++) {
                    int rgb1 = img1.getRGB(x, y);
                    int rgb2 = img2.getRGB(x, y);
                    diff += Math.abs((rgb1 & 0xFF) - (rgb2 & 0xFF));
                }
            }

            double maxDiff = 255.0 * img1.getWidth() * img1.getHeight();
            double similarity = 1.0 - (diff / maxDiff);
            return similarity > 0.9; // 90%以上相似
        } catch (IOException e) {
            plugin.getLogger().warning("图片对比失败: " + e.getMessage());
            return false;
        }
    }
    public static WhitelistModule getInstance() {
        return instance;
    }

    public List<WhiteListEntry> getWhiteListEntries() {
        return whiteListEntries;
    }

}