package com.sagecraft;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 基于PacketEvents的聊天气泡管理器
 * 简化版本，专注于核心功能
 */
public class PacketEventsBubbleManager implements PacketListener {
    
    private final JavaPlugin P;
    private final MiniMessage mm = MiniMessage.miniMessage();
    
    // Display / TextDisplay 元数据索引（根据Minecraft Wiki）
    private static final int META_TRANSLATION   = 11; // Vector3f 本地位移
    private static final int META_SCALE         = 12; // Vector3f 缩放
    private static final int META_BILLBOARD     = 15; // byte: 0 FIXED, 3 CENTER
    private static final int META_TEXT          = 23; // Component
    private static final int META_LINE_WIDTH    = 24; // int
    private static final int META_BG_COLOR      = 25; // int (ARGB)
    private static final int META_TEXT_OPACITY  = 26; // byte 0..255
    private static final int META_FLAGS         = 27; // byte (bit mask)
    
    // 动画参数
    private static final int BUBBLE_ENTER_TICKS = 4;
    private static final int BUBBLE_EXIT_TICKS = 4;
    private static final float TARGET_SCALE = 1.0f;
    private static final float START_SCALE = 0.05f;
    private static final float END_SCALE = 0.01f;
    private static final boolean FADE_WITH_SCALE = true;
    
    // player -> 气泡实体信息 [bg, text, tail]
    private final Map<UUID, BubbleInfo[]> playerBubbles = new ConcurrentHashMap<>();
    // player -> 正在运行的动画任务
    private final Map<UUID, List<BukkitTask>> runningTasks = new ConcurrentHashMap<>();
    
    public PacketEventsBubbleManager(JavaPlugin plugin) {
        this.P = plugin;
    }
    
    /**
     * 处理玩家聊天事件，创建聊天气泡
     */
    public void onChat(Player player, String rawMessage) {
        final FileConfiguration c = P.getConfig();
        if (!c.getBoolean("bubble.enabled", true)) return;
        
        // 预处理消息
        final String preprocessed = preprocessEmojiToIA(rawMessage);
        final String cleaned = strip(preprocessed);
        
        // 处理文本换行
        final int innerPxDefault = c.getInt("bubble.inner-px", 140);
        final boolean autoWrap = c.getBoolean("bubble.auto-wrap", true);
        
        List<String> lines = autoWrap
                ? wrapByPxRespectingNewlinesScaled(cleaned, innerPxDefault, 1.0)
                : splitByNewlines(cleaned);
        
        // 限制最大行数
        final int maxLines = Math.max(1, c.getInt("bubble.max-lines", 3));
        final String ellipsis = c.getString("bubble.ellipsis", "...");
        if (lines.size() > maxLines) {
            lines = hardCapReplaceLastChar(lines, maxLines, innerPxDefault, 1.0, ellipsis);
        }
        
        // 创建文本组件
        final String template = c.getString("bubble.mini-message", "<white>%message%</white>");
        final boolean usePapiText = c.getBoolean("bubble.use-papi-for-text", false);
        final List<Component> parts = new ArrayList<>(lines.size());
        
        for (String line : lines) {
            String safe = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String mmText = template.replace("%message%", safe);
            if (usePapiText) mmText = PlaceholderAPI.setPlaceholders(player, mmText);
            parts.add(mm.deserialize(mmText));
        }
        
        final Component finalText = Component.join(Component.newline(), parts);
        
        // 3) 背景宽度 = 最长行"缩放宽度" + 内边距
        final int maxPxScaled = lines.stream().mapToInt(s -> pxScaled(s, 1.0)).max().orElse(0);
        final SliceSet set = loadSet(c, pickHeight(c, lines.size()));
        final int fillPx = maxPxScaled + Math.max(0, set.paddingPx);
        
        final String bgStr = threeSlice(player, fillPx, set);
        // tail 两侧 mid 的裁剪量（可被 config 覆盖）
        final int tailTrimPerSide = Math.max(0, c.getInt("bubble.tail-trim-per-side", 0));
        final String tailStr = tailLineMidBothSides(player, fillPx, set, tailTrimPerSide);
        
        // 获取或创建气泡实体 [bg, text, tail]
        BubbleInfo[] bubbleInfos = getOrCreateBubbleInfos(player);
        
        // 更新文本内容
        bubbleInfos[0].textContent = Component.text(bgStr);   // 背景
        bubbleInfos[1].textContent = finalText;               // 文本
        bubbleInfos[2].textContent = Component.text(tailStr); // 尾部
        
        // 设置位置 - 只在玩家头部位置生成，yOffset通过META_TRANSLATION设置
        final double yOffset = c.getDouble("bubble.y-offset", 0.55);
        Location headLocation = SpigotConversionUtil.fromBukkitLocation(
            player.getLocation().add(0, player.isSneaking() ? 1.6 : 1.9, 0)
        );
        
        // 取消旧动画任务
        cancelRunningTasks(player.getUniqueId());
        
        // 发送实体到所有在线玩家
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(onlinePlayer);
            if (user != null) {
                spawnBubbleEntities(user, bubbleInfos, headLocation, (float)yOffset);
                // 设置passenger关系，让实体跟随玩家
                setPassengerRelationship(user, player, bubbleInfos);
            }
        }
        
        // 直接启动动画
        startBubbleAnimation(bubbleInfos, player.getUniqueId());
    }
    
    /**
     * 获取或创建气泡信息 [bg, text, tail]
     */
    private BubbleInfo[] getOrCreateBubbleInfos(Player player) {
        return playerBubbles.computeIfAbsent(player.getUniqueId(), uuid -> {
            BubbleInfo bg = new BubbleInfo(SpigotReflectionUtil.generateEntityId(), UUID.randomUUID());
            BubbleInfo text = new BubbleInfo(SpigotReflectionUtil.generateEntityId(), UUID.randomUUID());
            BubbleInfo tail = new BubbleInfo(SpigotReflectionUtil.generateEntityId(), UUID.randomUUID());
            return new BubbleInfo[]{bg, text, tail};
        });
    }
    
    /**
     * 生成气泡实体 [bg, text, tail]
     */
    private void spawnBubbleEntities(User user, BubbleInfo[] bubbleInfos, Location location, float yOffset) {
        try {
            // 生成三个TEXT_DISPLAY实体：背景、文本、尾部
            for (int i = 0; i < bubbleInfos.length; i++) {
                BubbleInfo info = bubbleInfos[i];
                
                // 所有实体使用相同的位置，通过META_TRANSLATION设置本地偏移
                Location entityLoc = new Location(
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
                );
                
                WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
                    info.entityId,
                    info.entityUuid,
                    EntityTypes.TEXT_DISPLAY,
                    entityLoc,
                    0f, // headYaw
                    0,  // data
                    null // velocity
                );
                user.sendPacket(spawnPacket);
                
                // 设置实体元数据
                // i == 0: 背景实体, i == 1: 文本实体, i == 2: 尾部实体
                setTextDisplayMetadata(user, info.entityId, info.textContent, i, yOffset);
            }
            
        } catch (Exception e) {
            P.getLogger().warning("生成气泡实体失败: " + e.getMessage());
        }
    }
    
    /**
     * 设置passenger关系，让气泡实体跟随玩家
     */
    private void setPassengerRelationship(User user, Player player, BubbleInfo[] bubbleInfos) {
        try {
            // 获取玩家的实体ID
            int playerEntityId = player.getEntityId();
            
            // 创建passenger实体ID数组
            int[] passengerIds = new int[bubbleInfos.length];
            for (int i = 0; i < bubbleInfos.length; i++) {
                passengerIds[i] = bubbleInfos[i].entityId;
            }
            
            // 发送SetPassengers包设置passenger关系
            try {
                // 使用PacketEvents API发送SetPassengers包
                WrapperPlayServerSetPassengers setPassengersPacket = new WrapperPlayServerSetPassengers(playerEntityId, passengerIds);
                user.sendPacket(setPassengersPacket);
                
                P.getLogger().info("成功设置玩家 " + player.getName() + " 的passenger: " + java.util.Arrays.toString(passengerIds));
                
            } catch (Exception e) {
                P.getLogger().warning("发送SetPassengers包失败: " + e.getMessage());
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            P.getLogger().warning("设置passenger关系失败: " + e.getMessage());
        }
    }
    
    /**
     * 设置TEXT_DISPLAY实体的元数据
     */
    /**
     * 设置TEXT_DISPLAY实体的元数据
     * layer: 0=bg, 1=tx, 2=tail
     */
    private void setTextDisplayMetadata(User user, int entityId, Component text, int layer, float yOffset) {
        try {
            List<EntityData<?>> md = new ArrayList<>();

            // 本地平移：用 translation.z 拉开层次（不要用世界Z）
            // 如果仍偶发摩尔纹，把 0.020f 提大到 0.03f~0.05f
            float tz = (layer == 0) ? -0.020f : (layer == 1 ? 0.020f : 0.000f);
            md.add(new EntityData<>(META_TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(0f, yOffset, tz)));

            // 文字：直接发 Adventure Component（不要自己拼 JSON）
            md.add(new EntityData<>(META_TEXT, EntityDataTypes.ADV_COMPONENT, text));

            // 大行宽、透明背景、不透明字体
            md.add(new EntityData<>(META_LINE_WIDTH,   EntityDataTypes.INT,  Integer.MAX_VALUE / 2));
            md.add(new EntityData<>(META_BG_COLOR,     EntityDataTypes.INT,  0x00000000));
            md.add(new EntityData<>(META_TEXT_OPACITY, EntityDataTypes.BYTE, (byte)255));
            
            // 设置flags：居中、无阴影、不使用默认背景
            byte flags = 0x08; // 0x08 = 居中对齐
            md.add(new EntityData<>(META_FLAGS,        EntityDataTypes.BYTE, flags));

            // 可选：让它总朝向摄像机（和你原 Bukkit 版一致）
            md.add(new EntityData<>(META_BILLBOARD,    EntityDataTypes.BYTE, (byte)3));

            user.sendPacket(new WrapperPlayServerEntityMetadata(entityId, md));
            
            P.getLogger().info("实体 " + entityId + " (layer " + layer + ") 设置本地偏移 Z=" + tz);
        } catch (Exception e) {
            P.getLogger().warning("设置TEXT_DISPLAY元数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 设置所有实体的缩放
     */
    private void setScaleAll(float scale, BubbleInfo[] infos) {
        List<EntityData<?>> md = List.of(new EntityData<>(META_SCALE, EntityDataTypes.VECTOR3F, new Vector3f(scale, scale, scale)));
        broadcastMeta(infos, md);
    }

    /**
     * 设置所有实体的透明度
     */
    private void setOpacityAll(int opacity, BubbleInfo[] infos) {
        byte b = (byte)Math.max(0, Math.min(255, opacity));
        List<EntityData<?>> md = List.of(new EntityData<>(META_TEXT_OPACITY, EntityDataTypes.BYTE, b));
        broadcastMeta(infos, md);
    }

    /**
     * 广播元数据到所有玩家
     */
    private void broadcastMeta(BubbleInfo[] infos, List<EntityData<?>> md) {
        for (Player pl : Bukkit.getOnlinePlayers()) {
            User u = PacketEvents.getAPI().getPlayerManager().getUser(pl);
            if (u == null) continue;
            for (BubbleInfo info : infos) {
                u.sendPacket(new WrapperPlayServerEntityMetadata(info.entityId, md));
            }
        }
    }

    /**
     * 移除气泡实体
     */
    private void removeBubbleEntities(BubbleInfo[] bubbleInfos) {
        int[] ids = Arrays.stream(bubbleInfos).mapToInt(b -> b.entityId).toArray();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            User u = PacketEvents.getAPI().getPlayerManager().getUser(pl);
            if (u != null) u.sendPacket(new WrapperPlayServerDestroyEntities(ids));
        }
    }

    /**
     * 从Component中提取纯文本内容
     */
    private String extractPlainText(Component component) {
        try {
            // 直接使用Adventure的PlainTextComponentSerializer
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
        } catch (Exception e) {
            // 如果失败，尝试简单的字符串处理
            String text = component.toString();
            P.getLogger().info("原始Component文本: " + text);
            
            // 移除Component的内部结构
            if (text.contains("TextComponentImpl")) {
                // 提取content字段的内容
                int start = text.indexOf("content=\"");
                if (start != -1) {
                    start += 9; // "content=\"" 的长度
                    int end = text.indexOf("\"", start);
                    if (end != -1) {
                        String extracted = text.substring(start, end);
                        P.getLogger().info("提取的文本: " + extracted);
                        return extracted;
                    }
                }
            }
            
            // 如果是连接的组件，尝试提取所有文本部分
            if (text.contains("children=[")) {
                StringBuilder result = new StringBuilder();
                String[] parts = text.split("TextComponentImpl\\{content=\"");
                for (int i = 1; i < parts.length; i++) {
                    int endIndex = parts[i].indexOf("\"");
                    if (endIndex != -1) {
                        result.append(parts[i].substring(0, endIndex));
                        if (i < parts.length - 1) {
                            result.append("\n"); // 添加换行符
                        }
                    }
                }
                if (result.length() > 0) {
                    String extracted = result.toString();
                    P.getLogger().info("提取的多行文本: " + extracted);
                    return extracted;
                }
            }
            
            P.getLogger().warning("无法提取文本内容，使用原始文本");
            return text;
        }
    }
    
    /**
     * 开始气泡动画
     */
    private void startBubbleAnimation(BubbleInfo[] bubbleInfos, UUID playerId) {
        final int life = P.getConfig().getInt("bubble.lifetime-ticks", 80);
        final int enterTicks = Math.max(2, BUBBLE_ENTER_TICKS);
        final int exitTicks = Math.max(2, BUBBLE_EXIT_TICKS);
        final int hold = Math.max(0, life - enterTicks - exitTicks);
        
        // 入场动画
        final BukkitTask enterTask = new BukkitRunnable() {
            int t = 0;
            public void run() {
                float progress = Math.min(1f, t / (float) enterTicks);
                float eased = easeOutBack(progress);
                float s = START_SCALE + (TARGET_SCALE - START_SCALE) * eased;
                setScaleAll(s, bubbleInfos);
                if (FADE_WITH_SCALE) setOpacityAll((int)(255 * progress), bubbleInfos);
                
                if (++t > enterTicks) {
                    cancel();
                    if (hold > 0) {
                        final BukkitTask holdTask = new BukkitRunnable() {
                            public void run() { startExit(bubbleInfos, exitTicks, playerId); }
                        }.runTaskLater(P, hold);
                        pushTask(playerId, holdTask);
                    } else {
                        startExit(bubbleInfos, exitTicks, playerId);
                    }
                }
            }
        }.runTaskTimer(P, 0L, 1L);
        pushTask(playerId, enterTask);
    }
    
    /**
     * 开始退出动画
     */
    private void startExit(final BubbleInfo[] bubbleInfos, final int exitTicks, final UUID playerId) {
        final BukkitTask exitTask = new BukkitRunnable() {
            int t = 0;
            public void run() {
                float progress = Math.min(1f, t / (float) exitTicks);
                float eased = easeInCubic(progress);
                float s = TARGET_SCALE - (TARGET_SCALE - END_SCALE) * eased;
                setScaleAll(s, bubbleInfos);
                if (FADE_WITH_SCALE) setOpacityAll((int)(255 * (1f - progress)), bubbleInfos);
                
                if (++t > exitTicks) {
                    // 移除实体
                    removeBubbleEntities(bubbleInfos);
                    playerBubbles.remove(playerId);
                    cancel();
                }
            }
        }.runTaskTimer(P, 0L, 1L);
        pushTask(playerId, exitTask);
    }
    

    
    /**
     * 移除passenger关系
     */
    private void removePassengerRelationship(User user, Player player) {
        try {
            // 发送空的passenger数组来清除所有passenger
            int playerEntityId = player.getEntityId();
            int[] emptyPassengerIds = new int[0]; // 空数组
            
            WrapperPlayServerSetPassengers setPassengersPacket = new WrapperPlayServerSetPassengers(playerEntityId, emptyPassengerIds);
            user.sendPacket(setPassengersPacket);
            
            P.getLogger().info("成功清除玩家 " + player.getName() + " 的passenger关系");
            
        } catch (Exception e) {
            P.getLogger().warning("清除passenger关系失败: " + e.getMessage());
        }
    }
    

    
    /**
     * 清理指定玩家的气泡数据
     */
    public void cleanupPlayer(UUID playerId) {
        // 取消正在运行的动画任务
        cancelRunningTasks(playerId);
        
        // 移除并销毁气泡实体
        BubbleInfo[] bubbleInfos = playerBubbles.remove(playerId);
        if (bubbleInfos != null) {
            removeBubbleEntities(bubbleInfos);
        }
    }
    
    /**
     * 每tick更新所有气泡位置
     */
    public void tickFollowAll() {
        // TODO: 实现位置更新逻辑
    }
    
    // 工具方法
    private void pushTask(UUID id, BukkitTask task) {
        runningTasks.computeIfAbsent(id, k -> new ArrayList<>()).add(task);
    }
    
    private void cancelRunningTasks(UUID id) {
        List<BukkitTask> list = runningTasks.remove(id);
        if (list != null) {
            for (BukkitTask task : list) {
                try { task.cancel(); } catch (Throwable ignored) {}
            }
        }
    }
    
    // 缓动函数
    private static float easeOutBack(float t) {
        float s = 1.70158f;
        float p = t - 1f;
        return (p*p*((s+1)*p + s) + 1f);
    }
    
    private static float easeInCubic(float t) {
        return t*t*t;
    }
    
    // 文本处理工具方法
    private String preprocessEmojiToIA(String s) {
        if (s == null || s.isEmpty()) return "";
        return EMOJI_COLON.matcher(s).replaceAll("%img_$1%");
    }
    
    private static final Pattern EMOJI_COLON = Pattern.compile(":([A-Za-z0-9_]+):");
    
    private String strip(String s) {
        return s == null ? "" : s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }
    
    private List<String> splitByNewlines(String s) {
        String[] parts = s.split("\\r?\\n", -1);
        return parts.length == 0 ? List.of("") : Arrays.asList(parts);
    }
    
    private List<String> wrapByPxRespectingNewlinesScaled(String s, int inner, double widthMul) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int cur = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            
            if (cp == '\r') continue;
            if (cp == '\n') { out.add(line.toString()); line.setLength(0); cur = 0; continue; }
            
            String ch = new String(Character.toChars(cp));
            int w = pxScaled(ch, widthMul);
            
            if (cur + w > inner && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                cur = 0;
            }
            line.append(ch);
            cur += w;
        }
        out.add(line.toString());
        return out;
    }
    
    private int pxScaled(String s, double widthMul) {
        return (int) Math.round(pxBase(s) * widthMul);
    }
    
    private int pxBase(String s) {
        int ascii = P.getConfig().getInt("bubble.font-px", 6);
        int px = 0;
        for (int cp : s.codePoints().toArray()) {
            if (cp == 32) px += Math.max(4, ascii - 2);
            else if (cp < 128) px += ascii;
            else if ((cp >= 0x4E00 && cp <= 0x9FFF) || (cp >= 0x3040 && cp <= 0x30FF)) px += (int)Math.round(ascii * 1.6);
            else px += (int)Math.round(ascii * 1.3);
        }
        return px;
    }
    
    private List<String> hardCapReplaceLastChar(List<String> lines, int maxLines, int innerPx, double widthMul, String ellipsis) {
        List<String> out = new ArrayList<>(lines.subList(0, maxLines));
        String last = out.get(maxLines - 1);
        
        last = removeLastCodePoint(last);
        
        while (pxScaled(last + ellipsis, widthMul) > innerPx && last.length() > 0) {
            last = removeLastCodePoint(last);
        }
        if (last.isEmpty() && pxScaled(ellipsis, widthMul) > innerPx) {
            out.set(maxLines - 1, "");
        } else {
            out.set(maxLines - 1, last + ellipsis);
        }
        return out;
    }
    
    private String removeLastCodePoint(String s) {
        if (s == null || s.isEmpty()) return "";
        int end = s.length();
        int start = s.offsetByCodePoints(end, -1);
        return s.substring(0, start);
    }
    
    /* =====================================
     *            背景生成系统
     * ===================================== */
    
    /** 三段式集合 */
    private static final class SliceSet {
        String left, mid, right, tail;
        int leftPx, midPx, rightPx, tailPx, paddingPx;
    }
    
    /** 取最合适高度（1 行=13px，之后每多一行+10px；没有就往下找） */
    private int pickHeight(FileConfiguration c, int lines) {
        int h = 13 + (Math.max(1, lines) - 1) * 10;
        int max = c.getInt("bubble.max-height", h);
        h = Math.min(h, max);
        while (h > 0 && !c.isConfigurationSection("bubble.sets." + h)) h -= 10;
        return Math.max(h, 13);
    }
    
    /** 从 config 载入某高度的三段式贴图定义 */
    private SliceSet loadSet(FileConfiguration c, int h) {
        String base = "bubble.sets." + h + ".";
        SliceSet s = new SliceSet();
        s.left  = c.getString(base + "left");
        s.mid   = c.getString(base + "mid");
        s.right = c.getString(base + "right");
        s.tail  = c.getString(base + "tail");
        s.leftPx   = c.getInt(base + "left_px", 3);
        s.midPx    = c.getInt(base + "mid_px", 5);
        s.rightPx  = c.getInt(base + "right_px", 3);
        s.tailPx   = c.getInt(base + "tail_px", 7);
        s.paddingPx= c.getInt(base + "padding_px", 12);
        return s;
    }
    
    /** 背景：Left + Mid*repeat + Right（严格按像素凑满，不重叠） */
    private String threeSlice(Player p, int innerPx, SliceSet s) {
        int avail = Math.max(0, innerPx - s.leftPx - s.rightPx);
        int rep = Math.max(1, (int) Math.ceil(avail / (double) s.midPx));
        String L = glyph(p, s.left), M = glyph(p, s.mid), R = glyph(p, s.right);
        return L + M.repeat(rep) + R;
    }
    
    /** tail 行：mid*左 + tail + mid*右 —— 仅在 tail 两侧裁掉若干 mid，不改背景宽度 */
    private String tailLineMidBothSides(Player p, int innerPx, SliceSet s, int trimPerSide) {
        int avail = Math.max(0, innerPx - s.leftPx - s.rightPx);
        int rep   = Math.max(1, (int) Math.ceil(avail / (double) s.midPx));
        int tailTiles = Math.max(1, Math.round((float) s.tailPx / (float) s.midPx));
        int rim = Math.max(0, rep - tailTiles);
        int left  = rim / 2;
        int right = rim - left;

        int wantTrimTotal = Math.max(0, trimPerSide) * 2;
        int doTrimTotal   = Math.min(rim, wantTrimTotal);
        int trimL = doTrimTotal / 2;
        int trimR = doTrimTotal - trimL;
        left  = Math.max(0, left  - trimL);
        right = Math.max(0, right - trimR);

        String M = glyph(p, s.mid), T = glyph(p, s.tail);
        return M.repeat(left) + T + M.repeat(right);
    }
    
    /** IA 字形解析：支持 "img_xxx" / "%img_xxx%" / "ns:id" / 直接 "xxx" */
    private String glyph(Player p, String token) {
        if (token == null || token.isEmpty()) return "";
        String holder;
        if (token.startsWith("%")) holder = token;
        else if (token.startsWith("img_")) holder = "%" + token + "%";
        else if (token.contains(":")) holder = "%img_" + token.replace(':', '_') + "%";
        else holder = "%img_" + token + "%";
        String uni = PlaceholderAPI.setPlaceholders(p, holder);
        return (uni == null || uni.equals(holder)) ? "" : uni;
    }
    
    // 气泡信息类
    private static class BubbleInfo {
        final int entityId;
        final UUID entityUuid;
        Component textContent;
        
        public BubbleInfo(int entityId, UUID entityUuid) {
            this.entityId = entityId;
            this.entityUuid = entityUuid;
        }
    }
    
    // PacketEvents事件处理
    @Override
    public void onUserLogin(UserLoginEvent event) {
        P.getLogger().info("玩家登录: " + event.getUser().getName());
    }
    
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        // 处理接收到的数据包
    }
}
