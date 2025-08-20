package com.sagecraft;

import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.regex.Pattern;

public final class BubbleManager {

    private final JavaPlugin P;
    private final MiniMessage mm = MiniMessage.miniMessage();

    // —— 动画参数 ——
    private static final int BUBBLE_ENTER_TICKS = 4;
    private static final int BUBBLE_EXIT_TICKS  = 4;
    private static final float TARGET_SCALE = 1.0f;
    private static final float START_SCALE  = 0.05f;
    private static final float END_SCALE    = 0.01f;
    private static final boolean FADE_WITH_SCALE = true;

    // 只裁剪 tail 左右的 mid（每一侧裁掉多少块）。可被 config 覆盖：bubble.tail-trim-per-side
    private static final int TAIL_TRIM_PER_SIDE_HC = 0;

    // 透明背景色（兼容旧构建）
    private static final Color CLEAR;
    static {
        Color tmp;
        try { tmp = Color.fromARGB(0); } catch (Throwable t) { tmp = Color.fromRGB(255,255,255); }
        CLEAR = tmp;
    }

    // player -> [ bg, text, tail ]
    private final Map<UUID, TextDisplay[]> displays = new HashMap<>();
    // player -> 正在运行的动画任务（进/出）
    private final Map<UUID, List<BukkitTask>> runningTasks = new HashMap<>();

    public BubbleManager(JavaPlugin plugin) { this.P = plugin; }

    /* =====================================
     *               API
     * ===================================== */

    public void onChat(Player p, String rawMessage) {
        final FileConfiguration c = P.getConfig();
        if (!c.getBoolean("bubble.enabled", true)) return;

        // 0) 预处理：:admin: -> %img_admin%
        final String preprocessed = preprocessEmojiToIA(rawMessage);

        // 1) 初包行（识别 \n），用默认行宽粗包（widthMul=1.0）
        final int innerPxDefault = c.getInt("bubble.inner-px", 140);
        final boolean autoWrap = c.getBoolean("bubble.auto-wrap", true);
        final String cleaned = strip(preprocessed);

        List<String> rough = autoWrap
                ? wrapByPxRespectingNewlinesScaled(cleaned, innerPxDefault, 1.0)
                : splitByNewlines(cleaned);

        // 2) 第一轮 取高度/行宽/宽度系数
        int H1 = pickHeight(c, rough.size());
        int inner1 = getInnerPxForHeight(c, H1, innerPxDefault);
        double wm1 = getWidthMulForHeight(c, H1);

        // 2') 用 H1 参数重包一次
        List<String> pass2 = autoWrap
                ? wrapByPxRespectingNewlinesScaled(cleaned, inner1, wm1)
                : rough;

        // 2'') 第二轮“定稿一组 final 参数”（不再修改）
        final int Hf = pickHeight(c, pass2.size());
        final int innerPxF = getInnerPxForHeight(c, Hf, innerPxDefault);
        final double widthMulF = getWidthMulForHeight(c, Hf);
        List<String> linesF = pass2;

        // 2.1) 硬上限：超过 max-lines（默认 3）就把最后一个字符替换成 …，并保证像素宽度不溢出
        final int maxLines = Math.max(1, c.getInt("bubble.max-lines", 3));
        final String ellipsis = c.getString("bubble.ellipsis", "...");
        final List<String> lines = (linesF.size() > maxLines)
                ? hardCapReplaceLastChar(linesF, maxLines, innerPxF, widthMulF, ellipsis)
                : linesF;

        // 3) 背景宽度 = 最长行“缩放宽度” + 内边距
        final int maxPxScaled = lines.stream().mapToInt(s -> pxScaled(s, widthMulF)).max().orElse(0);
        final SliceSet set = loadSet(c, Hf);
        final int fillPx = maxPxScaled + Math.max(0, set.paddingPx);

        final String bgStr   = threeSlice(p, fillPx, set);
        // tail 两侧 mid 的裁剪量（可被 config 覆盖）
        final int tailTrimPerSide = Math.max(0, c.getInt("bubble.tail-trim-per-side", TAIL_TRIM_PER_SIDE_HC));
        final String tailStr = tailLineMidBothSides(p, fillPx, set, tailTrimPerSide); // mid*左 + tail + mid*右

        // 4) 文本组件
        final String tpl = c.getString("bubble.mini-message", "<white>%message%</white>");
        final boolean usePapiText = c.getBoolean("bubble.use-papi-for-text", false);
        final List<Component> parts = new ArrayList<>(lines.size());
        for (String ln : lines) {
            String safe = ln.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            String mmText = tpl.replace("%message%", safe);
            if (usePapiText) mmText = PlaceholderAPI.setPlaceholders(p, mmText);
            parts.add(mm.deserialize(mmText));
        }
        final Component finalText = Component.join(JoinConfiguration.separator(Component.newline()), parts);

        // 5) 复用/创建三层（bg/tx/tail 赋值后不再改引用 → effectively final，可在匿名类中使用）
        final TextDisplay bg, tx, tail;
        TextDisplay[] arr = displays.get(p.getUniqueId());
        if (arr == null || arr.length < 3 || arr[0] == null || arr[0].isDead()) {
            TextDisplay _bg   = (TextDisplay) p.getWorld().spawnEntity(p.getLocation(), EntityType.TEXT_DISPLAY);
            TextDisplay _tx   = (TextDisplay) p.getWorld().spawnEntity(p.getLocation(), EntityType.TEXT_DISPLAY);
            TextDisplay _tail = (TextDisplay) p.getWorld().spawnEntity(p.getLocation(), EntityType.TEXT_DISPLAY);

            for (TextDisplay d : new TextDisplay[]{_bg, _tx, _tail}) {
                d.setBillboard(Display.Billboard.CENTER);
                d.setDefaultBackground(false);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setLineWidth(Integer.MAX_VALUE / 2);
                d.setSeeThrough(false);
                try { d.setBackgroundColor(CLEAR); } catch (Throwable ignored) {}
                d.setViewRange((float) c.getDouble("bubble.view-range", 36.0));
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(2); // 轻插值丝滑
            }
            var tb = _bg.getTransformation();   tb.getTranslation().set(0f, 0.5f, -0.010f); _bg.setTransformation(tb);
            var tt = _tail.getTransformation(); tt.getTranslation().set(0f, 0.5f,  0.000f); _tail.setTransformation(tt);
            var txf = _tx.getTransformation();  txf.getTranslation().set(0f, 0.5f,  0.010f); _tx.setTransformation(txf);

            _tx.setShadowed(c.getBoolean("bubble.show-shadow", false));
            displays.put(p.getUniqueId(), new TextDisplay[]{_bg, _tx, _tail});

            bg = _bg; tx = _tx; tail = _tail;
        } else {
            bg   = arr[0]; tx = arr[1]; tail = arr[2];
        }

        // 6) 赋值文本并清默认背景
        bg.text(Component.text(bgStr));
        tail.text(Component.text(tailStr));
        tx.text(finalText);
        bg.setDefaultBackground(false); tail.setDefaultBackground(false); tx.setDefaultBackground(false);
        try { bg.setBackgroundColor(CLEAR); tail.setBackgroundColor(CLEAR); tx.setBackgroundColor(CLEAR); } catch (Throwable ignored) {}

        // 7) 设置为玩家的passenger，自动跟随移动
        // 硬编码Y偏移量，让气泡更明显        
        final double oy = c.getDouble("bubble.y-offset", 0.55);
        var head = p.getLocation().add(0, p.isSneaking() ? 1.6 : 1.9, 0).add(0, oy, 0);
        bg.teleport(head); tail.teleport(head); tx.teleport(head);
        
        // 将TextDisplay设置为玩家的passenger，实现自动跟随
        p.addPassenger(bg);
        p.addPassenger(tail);
        p.addPassenger(tx);

        // —— 取消旧动画任务，避免叠加 ——
        cancelRunningTasks(p.getUniqueId());

        // 初始缩放 & 透明度
        setScaleAll(START_SCALE, bg, tail, tx);
        if (FADE_WITH_SCALE) setOpacityAll(0, bg, tail, tx);

        // 8) 入场 → 停留 → 退场
        final int life = c.getInt("bubble.lifetime-ticks", 80);
        final int enterTicks = Math.max(2, BUBBLE_ENTER_TICKS);
        final int exitTicks  = Math.max(2, BUBBLE_EXIT_TICKS);
        final int hold       = Math.max(0, life - enterTicks - exitTicks);

        // 入场
        final BukkitTask enterTask = new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (!valid3(bg, tx, tail)) { cancel(); return; }
                float progress = Math.min(1f, t / (float) enterTicks);
                float eased = easeOutBack(progress);
                float s = START_SCALE + (TARGET_SCALE - START_SCALE) * eased;
                setScaleAll(s, bg, tail, tx);
                if (FADE_WITH_SCALE) setOpacityAll((int)(255 * progress), bg, tail, tx);
                if (++t > enterTicks) {
                    cancel();
                    if (hold > 0) {
                        final BukkitTask holdTask = new BukkitRunnable() {
                            public void run() { startExit(bg, tx, tail, exitTicks, p.getUniqueId()); }
                        }.runTaskLater(P, hold);
                        pushTask(p.getUniqueId(), holdTask);
                    } else {
                        startExit(bg, tx, tail, exitTicks, p.getUniqueId());
                    }
                }
            }
        }.runTaskTimer(P, 0L, 1L);
        pushTask(p.getUniqueId(), enterTask);
    }

    /** 每 tick 跟随玩家头部（使用passenger系统，无需手动更新位置） */
    public void tickFollowAll() {
        // 使用passenger系统后，TextDisplay会自动跟随玩家移动
        // 这里可以保留用于其他可能的处理，但不再需要手动teleport
        for (Player p : Bukkit.getOnlinePlayers()) {
            TextDisplay[] a = displays.get(p.getUniqueId());
            if (a == null) continue;
            // 检查实体是否仍然有效，如果无效则清理
            for (TextDisplay d : a) {
                if (d != null && !d.isValid()) {
                    cleanupPlayer(p.getUniqueId());
                    break;
                }
            }
        }
    }

    /** 清理指定玩家的气泡数据 */
    public void cleanupPlayer(UUID playerId) {
        // 取消正在运行的动画任务
        cancelRunningTasks(playerId);
        
        // 移除并销毁TextDisplay实体
        TextDisplay[] arr = displays.remove(playerId);
        if (arr != null) {
            for (TextDisplay display : arr) {
                if (display != null && display.isValid()) {
                    // 先移除passenger关系，再删除实体
                    if (display.getVehicle() != null) {
                        display.leaveVehicle();
                    }
                    display.remove();
                }
            }
        }
    }

    /* ========= 动画工具 ========= */

    private void startExit(final TextDisplay bg, final TextDisplay tx, final TextDisplay tail, final int exitTicks, final UUID uid) {
        final BukkitTask exitTask = new BukkitRunnable() {
            int t = 0;
            public void run() {
                if (!valid3(bg, tx, tail)) { cancel(); return; }
                float progress = Math.min(1f, t / (float) exitTicks); // 0->1
                float eased = easeInCubic(progress);
                float s = TARGET_SCALE - (TARGET_SCALE - END_SCALE) * eased; // 大->小
                setScaleAll(s, bg, tail, tx);
                if (FADE_WITH_SCALE) setOpacityAll((int)(255 * (1f - progress)), bg, tail, tx);
                if (++t > exitTicks) {
                    if (bg.isValid()) {
                        if (bg.getVehicle() != null) bg.leaveVehicle();
                        bg.remove();
                    }
                    if (tail.isValid()) {
                        if (tail.getVehicle() != null) tail.leaveVehicle();
                        tail.remove();
                    }
                    if (tx.isValid()) {
                        if (tx.getVehicle() != null) tx.leaveVehicle();
                        tx.remove();
                    }
                    displays.remove(uid);
                    cancel();
                }
            }
        }.runTaskTimer(P, 0L, 1L);
        pushTask(uid, exitTask);
    }

    private static boolean valid3(TextDisplay a, TextDisplay b, TextDisplay c) {
        return a != null && b != null && c != null && a.isValid() && b.isValid() && c.isValid();
    }
    private void pushTask(UUID id, BukkitTask t) { runningTasks.computeIfAbsent(id, k -> new ArrayList<>()).add(t); }
    private void cancelRunningTasks(UUID id) {
        List<BukkitTask> list = runningTasks.remove(id);
        if (list != null) for (BukkitTask t : list) try { t.cancel(); } catch (Throwable ignored) {}
    }

    // 缓动
    private static float easeOutBack(float t) { float s = 1.70158f; float p = t - 1f; return (p*p*((s+1)*p + s) + 1f); }
    private static float easeInCubic(float t) { return t*t*t; }
    private static void setScaleAll(float s, TextDisplay... arr) {
        for (TextDisplay d : arr) if (d != null && d.isValid()) {
            var tf = d.getTransformation(); tf.getScale().set(s, s, s); d.setTransformation(tf);
        }
    }
    private static void setOpacityAll(int op, TextDisplay... arr) {
        byte b = (byte) Math.max(0, Math.min(255, op));
        for (TextDisplay d : arr) if (d != null && d.isValid()) d.setTextOpacity(b);
    }

    /* ========= 包行 / 估宽 ========= */

    /** 每个高度的可用行宽；没配则回退到 bubble.inner-px */
    private int getInnerPxForHeight(FileConfiguration c, int h, int fallback) {
        String k = "bubble.inner-px-per-height." + h;
        return c.isInt(k) ? c.getInt(k) : fallback;
    }

    /** 每个高度的“宽度系数”，把逻辑像素换算成实际渲染宽（对齐 IA 的 scale_ratio） */
    private double getWidthMulForHeight(FileConfiguration c, int h) {
        String k = "bubble.width-mul-per-height." + h;
        if (c.isDouble(k)) return Math.max(0.5, c.getDouble(k));
        return Math.max(0.5, c.getDouble("bubble.width-mul", 1.0));
    }

    /** 缩放后的像素宽：pxBase(s) * widthMul（四舍五入到 int） */
    private int pxScaled(String s, double widthMul) {
        return (int) Math.round(pxBase(s) * widthMul);
    }

    /** 仅按 \r?\n 切分（不做换行） */
    private List<String> splitByNewlines(String s) {
        String[] parts = s.split("\\r?\\n", -1);
        return parts.length == 0 ? List.of("") : Arrays.asList(parts);
    }

    /** 按“缩放后宽度”换行，尊重原始 \n */
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

    /** 超过 maxLines：把最后一行的**最后一个字符**替换为 …（若仍超宽则继续删） */
    private List<String> hardCapReplaceLastChar(List<String> lines, int maxLines, int innerPx, double widthMul, String ellipsis) {
        List<String> out = new ArrayList<>(lines.subList(0, maxLines));
        String last = out.get(maxLines - 1);

        // 先删掉最后一个 codepoint
        last = removeLastCodePoint(last);

        // 再保证 “文本 + …” 不超过像素宽
        while (pxScaled(last + ellipsis, widthMul) > innerPx && last.length() > 0) {
            last = removeLastCodePoint(last);
        }
        if (last.isEmpty() && pxScaled(ellipsis, widthMul) > innerPx) {
            // 极端：连 … 都放不下，就什么都不放
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
     *            Internals / utils
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

    /** 基础像素估宽（不含宽度系数；与旧 px() 逻辑一致） */
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

    /** 旧接口向后兼容（如果别处还调用了 px(String)） */
    private int px(String s) { return pxBase(s); }

    /** 去除§格式码，仅用于估宽 */
    private String strip(String s) {
        return s == null ? "" : s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    /* ========= 冒号表情到 IA 的替换 ========= */

    private static final Pattern EMOJI_COLON = Pattern.compile(":([A-Za-z0-9_]+):");

    /** 把 :admin: 这类写法替换成 %img_admin% */
    private String preprocessEmojiToIA(String s) {
        if (s == null || s.isEmpty()) return "";
        return EMOJI_COLON.matcher(s).replaceAll("%img_$1%");
    }
}
