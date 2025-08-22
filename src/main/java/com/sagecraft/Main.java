package com.sagecraft;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;


public final class Main extends JavaPlugin implements Listener {

    private PacketEventsBubbleManager packetBubbles; // PacketEvents气泡管理器

    @Override
    public void onEnable() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        //On Bukkit, calling this here is essential, hence the name "load"
        PacketEvents.getAPI().load();
        PacketEvents.getAPI().init();
        // Plugin startup logic
        saveDefaultConfig();
        packetBubbles = new PacketEventsBubbleManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        // 注册PacketEvents监听器
        PacketEvents.getAPI().getEventManager().registerListener(packetBubbles, PacketListenerPriority.NORMAL);
        
        getLogger().info("ChatBubble 插件已启用！");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        // 清理所有玩家的气泡数据
        for (Player player : Bukkit.getOnlinePlayers()) {
            packetBubbles.cleanupPlayer(player.getUniqueId());
        }
        getLogger().info("ChatBubble 插件已禁用！");
        PacketEvents.getAPI().terminate();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("chatbubble") || command.getName().equalsIgnoreCase("cb")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("chatbubble.reload")) {
                    sender.sendMessage("§c你没有权限使用此命令！");
                    return true;
                }
                
                // 重新加载配置
                reloadConfig();
                // 重新初始化PacketEventsBubbleManager
                packetBubbles = new PacketEventsBubbleManager(this);
                sender.sendMessage("§aChatBubble 配置已重新加载！");
                return true;
            }
            
            sender.sendMessage("§eChatBubble 插件命令：");
            sender.sendMessage("§7/chatbubble reload §8- 重新加载配置文件");
            return true;
        }
        
        if (command.getName().equalsIgnoreCase("testia")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c此命令只能由玩家使用！");
                return true;
            }
            
            Player p = (Player) sender;
            
            if (args.length > 0) {
                String testType = args[0].toLowerCase();
                switch (testType) {
                    case "packet":
                        // 测试PacketEvents气泡
                        packetBubbles.onChat(p, "这是PacketEvents测试文本！");
                        p.sendMessage("§a已创建PacketEvents气泡！");
                        break;
                    case "broadcast":
                        // 测试广播
                        packetBubbles.onChat(p, "服务器广播测试消息！");
                        p.sendMessage("§a已向所有玩家广播气泡！");
                        break;
                    case "chat":
                        // 测试聊天事件
                        packetBubbles.onChat(p, "测试聊天消息");
                        p.sendMessage("§a已触发聊天事件测试！");
                        break;
                    default:
                        p.sendMessage("§e可用的测试类型：");
                        p.sendMessage("§7/testia bubble §8- 测试传统气泡");
                        p.sendMessage("§7/testia packet §8- 测试PacketEvents文本显示");
                        p.sendMessage("§7/testia broadcast §8- 测试广播文本显示");
                        p.sendMessage("§7/testia chat §8- 测试聊天事件");
                        break;
                }
            } else {
                p.sendMessage("§e测试命令使用说明：");
                p.sendMessage("§7/testia bubble §8- 测试传统气泡");
                p.sendMessage("§7/testia packet §8- 测试PacketEvents文本显示");
                p.sendMessage("§7/testia broadcast §8- 测试广播文本显示");
                p.sendMessage("§7/testia chat §8- 测试聊天事件");
            }
            return true;
        }
        
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent e) {
        // 异步事件，需要切换到主线程处理实体操作
        Player player = e.getPlayer();
        String message = e.getMessage();
        
        // 在主线程中执行气泡创建
        Bukkit.getScheduler().runTask(this, () -> {
            // 使用PacketEvents气泡管理器
            packetBubbles.onChat(player, message);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent e) {
        // 使用Packet系统，位置更新由BubbleTag内部处理
        // 不再需要手动调用更新方法
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e) {
        // 玩家退出时清理气泡数据
        packetBubbles.cleanupPlayer(e.getPlayer().getUniqueId());
    }
}
