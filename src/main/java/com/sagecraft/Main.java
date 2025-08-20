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

public final class Main extends JavaPlugin implements Listener {

    private BubbleManager bubbles;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        bubbles = new BubbleManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        
        // 启动定时任务，每tick更新气泡位置
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            bubbles.tickFollowAll();
        }, 1L, 1L);
        
        getLogger().info("ChatBubble 插件已启用！");
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        // 清理所有玩家的气泡数据
        for (Player player : Bukkit.getOnlinePlayers()) {
            bubbles.cleanupPlayer(player.getUniqueId());
        }
        getLogger().info("ChatBubble 插件已禁用！");
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
                // 重新初始化BubbleManager
                bubbles = new BubbleManager(this);
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
            p.sendMessage("§a测试命令执行成功！");
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
            bubbles.onChat(player, message);
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
        bubbles.cleanupPlayer(e.getPlayer().getUniqueId());
    }
}
