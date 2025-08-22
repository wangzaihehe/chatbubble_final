# PacketEvents 集成指南

## 概述

本指南说明如何使用 PacketEvents API 来实现基于 TEXT_DISPLAY 实体的聊天气泡功能。

## 参考示例

参考你提供的示例代码：

```java
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.UserLoginEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PacketEventsPacketListener implements PacketListener {
    private FakeArmorStand fakeArmorStand = null;

    @Override
    public void onUserLogin(UserLoginEvent event) {
        User user = event.getUser();
        Player player = event.getPlayer();

        // Create the Armor Stand (if we haven't already)
        if (fakeArmorStand == null) {
            // Generate a random UUID
            UUID uuid = UUID.randomUUID();
            // Generate an Entity ID
            int entityId = SpigotReflectionUtil.generateEntityId();

            fakeArmorStand = new FakeArmorStand(uuid, entityId);
        }
        
        // Spawn the Armor Stand at the user's current location
        Location spawnLocation = SpigotConversionUtil.fromBukkitLocation(player.getLocation());
        fakeArmorStand.spawn(user, spawnLocation);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        User user = event.getUser();
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) 
            return;
        // They interacted with an entity.
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        // Retrieve that entity's ID
        int entityId = packet.getEntityId();

        // Check if the client interacted with the Armor Stand
        if (entityId == fakeArmorStand.entityId) {
            // Increment their clicks
            int clicks = fakeArmorStand.clicks.getOrDefault(user.getUUID(), 0) + 1;
            fakeArmorStand.clicks.put(user.getUUID(), clicks);
            user.sendMessage("You now have " + clicks + " clicks on the Armor Stand!");
        }
    }

    private static class FakeArmorStand {
        private final int entityId;
        private final UUID uuid;
        // Track their clicks
        private final Map<UUID, Integer> clicks = new ConcurrentHashMap<>();

        public FakeArmorStand(UUID uuid, int entityId) {
            this.uuid = uuid;
            this.entityId = entityId;
        }
        
        public void spawn(User user, Location location) {
            WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
                    entityId,
                    uuid,
                    EntityTypes.ARMOR_STAND,
                    location,
                    location.getYaw(), // Head yaw
                    0, // No additional data
                    null // We won't specify any initial velocity
            );
            user.sendPacket(packet);
        }
    }
}
```

## 关键修改点

要将 ARMOR_STAND 改为 TEXT_DISPLAY，主要需要修改：

### 1. 实体类型
```java
// 原来的
EntityTypes.ARMOR_STAND

// 改为
EntityTypes.TEXT_DISPLAY
```

### 2. 实体生成包
```java
WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(
    entityId,
    uuid,
    EntityTypes.TEXT_DISPLAY,  // 使用 TEXT_DISPLAY
    location,
    location.getYaw(),
    0,
    null
);
```

### 3. 实体元数据设置
TEXT_DISPLAY 实体需要额外的元数据包来设置文本内容：

```java
// 需要发送实体元数据包来设置文本
WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(
    entityId,
    Arrays.asList(
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_TEXT, Component.text("你的文本")),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_LINE_WIDTH, 200),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_BACKGROUND_COLOR, 0),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_TEXT_OPACITY, (byte) 255),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_ALIGNMENT, (byte) 1), // CENTER
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_SEE_THROUGH, false),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_SHADOW, false),
        new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_DEFAULT_BACKGROUND, false)
    )
);
user.sendPacket(metadataPacket);
```

## 实现步骤

### 1. 创建气泡实体类
```java
private static class ChatBubble {
    private final int bgEntityId, textEntityId, tailEntityId;
    private final UUID bgUuid, textUuid, tailUuid;
    private Component bgText, textContent, tailText;
    
    public ChatBubble(int bgId, int textId, int tailId, UUID bgUuid, UUID textUuid, UUID tailUuid) {
        this.bgEntityId = bgId;
        this.textEntityId = textId;
        this.tailEntityId = tailId;
        this.bgUuid = bgUuid;
        this.textUuid = textUuid;
        this.tailUuid = tailUuid;
    }
    
    public void spawn(User user, Location location) {
        // 生成背景实体
        WrapperPlayServerSpawnEntity bgPacket = new WrapperPlayServerSpawnEntity(
            bgEntityId, bgUuid, EntityTypes.TEXT_DISPLAY, location, 0, 0, 0, null
        );
        user.sendPacket(bgPacket);
        
        // 生成文本实体
        WrapperPlayServerSpawnEntity textPacket = new WrapperPlayServerSpawnEntity(
            textEntityId, textUuid, EntityTypes.TEXT_DISPLAY, location, 0, 0, 0, null
        );
        user.sendPacket(textPacket);
        
        // 生成尾部实体
        WrapperPlayServerSpawnEntity tailPacket = new WrapperPlayServerSpawnEntity(
            tailEntityId, tailUuid, EntityTypes.TEXT_DISPLAY, location, 0, 0, 0, null
        );
        user.sendPacket(tailPacket);
        
        // 设置元数据
        setMetadata(user);
    }
    
    private void setMetadata(User user) {
        // 设置背景元数据
        WrapperPlayServerEntityMetadata bgMeta = new WrapperPlayServerEntityMetadata(
            bgEntityId, createTextDisplayMetadata(bgText)
        );
        user.sendPacket(bgMeta);
        
        // 设置文本元数据
        WrapperPlayServerEntityMetadata textMeta = new WrapperPlayServerEntityMetadata(
            textEntityId, createTextDisplayMetadata(textContent)
        );
        user.sendPacket(textMeta);
        
        // 设置尾部元数据
        WrapperPlayServerEntityMetadata tailMeta = new WrapperPlayServerEntityMetadata(
            tailEntityId, createTextDisplayMetadata(tailText)
        );
        user.sendPacket(tailMeta);
    }
    
    private List<EntityDataItem> createTextDisplayMetadata(Component text) {
        return Arrays.asList(
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_TEXT, text),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_LINE_WIDTH, 200),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_BACKGROUND_COLOR, 0),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_TEXT_OPACITY, (byte) 255),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_ALIGNMENT, (byte) 1),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_SEE_THROUGH, false),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_SHADOW, false),
            new EntityDataItem(EntityDataTypes.TEXT_DISPLAY_DEFAULT_BACKGROUND, false)
        );
    }
}
```

### 2. 在聊天事件中创建气泡
```java
@EventHandler
public void onChat(AsyncPlayerChatEvent event) {
    Player player = event.getPlayer();
    String message = event.getMessage();
    
    // 在主线程中创建气泡
    Bukkit.getScheduler().runTask(plugin, () -> {
        ChatBubble bubble = createChatBubble(player, message);
        
        // 向所有在线玩家发送气泡
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(onlinePlayer);
            if (user != null) {
                Location location = SpigotConversionUtil.fromBukkitLocation(
                    player.getLocation().add(0, 2, 0)
                );
                bubble.spawn(user, location);
            }
        }
    });
}
```

## 注意事项

1. **API 版本兼容性**：PacketEvents 的不同版本可能有不同的 API，需要根据具体版本调整
2. **实体生命周期管理**：需要正确管理实体的创建和销毁
3. **位置同步**：TEXT_DISPLAY 实体不会自动跟随玩家，需要手动更新位置
4. **性能考虑**：频繁创建和销毁实体可能影响性能，建议使用对象池

## 优势

使用 PacketEvents API 的优势：

1. **无依赖**：不需要 ProtocolLib 等额外依赖
2. **高性能**：直接操作网络数据包，性能更好
3. **跨平台**：支持多种服务器软件（Spigot、Paper、Fabric、Velocity等）
4. **精确控制**：可以精确控制每个数据包的发送

## 总结

通过将 `EntityTypes.ARMOR_STAND` 改为 `EntityTypes.TEXT_DISPLAY`，并添加相应的元数据设置，就可以使用 PacketEvents API 实现基于 TEXT_DISPLAY 实体的聊天气泡功能。这种方法比传统的 Bukkit API 更高效，也更灵活。
