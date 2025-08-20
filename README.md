# ChatBubble - 高性能聊天气泡插件

## 概述

ChatBubble 是一个高性能的 Minecraft 聊天气泡插件，完全参照 Custom-Nameplates 的架构设计，使用自建的反射系统直接操作 Minecraft 网络层，无需 ProtocolLib 依赖。

## 主要特性

### 🚀 高性能架构
- **自建反射系统** - 无需 ProtocolLib，直接操作 NMS 类
- **虚拟实体** - 不创建真实实体，只发送 Packet，性能更优
- **缓存机制** - 反射结果缓存，减少重复查找
- **批量更新** - 支持批量发送多个数据包

### 🎯 精确跟踪
- **实时位置更新** - 每 tick 更新气泡位置
- **平滑动画** - 支持入场、停留、退场动画
- **自动跟随** - 气泡自动跟随玩家头部移动
- **蹲下适配** - 自动适配玩家蹲下状态

### 🎨 丰富的自定义
- **多行文本** - 支持自动换行和手动换行
- **动态高度** - 根据文本行数自动调整气泡高度
- **像素精确** - 精确的像素宽度计算
- **表情支持** - 支持 ItemsAdder 表情和自定义表情

## 技术架构

### 反射系统
```java
// 自建反射缓存
private static final Map<String, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
private static final Map<String, Constructor<?>> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

// 获取NMS类
private Class<?> getNMSClass(String className) {
    return CLASS_CACHE.computeIfAbsent(className, k -> {
        try {
            return Class.forName("net.minecraft." + className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    });
}
```

### 虚拟实体管理
```java
// 虚拟实体ID管理
private int nextEntityId = 1000000;

// 虚拟乘客系统
private final Map<UUID, Set<Integer>> virtualPassengers = new ConcurrentHashMap<>();
```

### Packet 系统
```java
// 直接创建和发送 Packet
private Object createTextDisplayPacket(int entityId, UUID entityUUID, Location location, String text) {
    // 使用反射直接创建 Minecraft Packet
    Class<?> packetClass = getNMSClass("network.protocol.game.ClientboundAddEntityPacket");
    Constructor<?> packetConstructor = packetClass.getConstructor(...);
    return packetConstructor.newInstance(...);
}
```

## 与 Custom-Nameplates 的对比

| 特性 | ChatBubble | Custom-Nameplates |
|------|------------|-------------------|
| **依赖** | 仅需 PlaceholderAPI | 无外部依赖 |
| **性能** | 自建反射，性能优秀 | 自建反射，性能优秀 |
| **架构** | 参照 Custom-Nameplates | 原始设计 |
| **维护** | 简化版本，易于理解 | 完整功能，复杂架构 |
| **学习价值** | 适合学习反射技术 | 适合学习完整架构 |

## 安装要求

- **Minecraft**: 1.20+
- **Bukkit/Spigot/Paper**: 1.20+
- **依赖插件**: PlaceholderAPI
- **可选插件**: ItemsAdder (用于表情支持)

## 配置说明

### 基础配置
```yaml
bubble:
  enabled: true                    # 是否启用气泡功能
  lifetime-ticks: 80              # 气泡持续时间
  y-offset: 0.55                  # 垂直偏移
  inner-px: 140                   # 内边距
  auto-wrap: true                 # 自动换行
  max-lines: 3                    # 最大行数
```

### 高级配置
```yaml
bubble:
  # 每行高度对应的内边距
  inner-px-per-height:
    13: 140
    23: 150
    33: 160
  
  # 每行高度对应的宽度系数
  width-mul-per-height:
    13: 1.0
    23: 1.1
    33: 1.2
  
  # 气泡贴图配置
  sets:
    13:
      left: "img_bubble_left"
      mid: "img_bubble_mid"
      right: "img_bubble_right"
      tail: "img_bubble_tail"
```

## 命令

- `/chatbubble reload` - 重新加载配置
- `/cb reload` - 重新加载配置（别名）

## 权限

- `chatbubble.reload` - 允许重新加载配置（默认：op）

## 开发说明

### 核心类结构

1. **BubbleManager** - 主要管理类
   - 反射系统初始化
   - 虚拟实体管理
   - Packet 创建和发送

2. **BubbleTag** - 气泡标签类
   - 单个气泡的生命周期管理
   - 位置更新和动画处理
   - 数据包发送

3. **Main** - 插件主类
   - 事件监听
   - 定时任务管理
   - 命令处理

### 关键技术点

1. **反射缓存** - 避免重复的反射查找
2. **虚拟实体** - 不创建真实实体，只发送 Packet
3. **批量更新** - 支持批量发送多个数据包
4. **错误处理** - 完善的异常处理机制

## 性能优化

1. **反射缓存** - 所有反射结果都进行缓存
2. **虚拟实体** - 不创建真实实体，减少服务器负担
3. **批量操作** - 支持批量发送多个数据包
4. **内存管理** - 及时清理不需要的数据

## 故障排除

### 常见问题

1. **气泡不显示**
   - 检查 PlaceholderAPI 是否正确安装
   - 检查配置文件是否正确
   - 查看控制台错误信息

2. **性能问题**
   - 检查是否有其他插件冲突
   - 调整 `lifetime-ticks` 参数
   - 检查服务器性能

3. **反射错误**
   - 确保服务器版本兼容
   - 检查 NMS 类名是否正确
   - 查看控制台错误信息

## 更新日志

### v1.0.0
- 完全重写，移除 ProtocolLib 依赖
- 使用自建反射系统
- 参照 Custom-Nameplates 架构
- 优化性能和稳定性

## 许可证

本项目采用 MIT 许可证。

## 贡献

欢迎提交 Issue 和 Pull Request！

## 致谢

- 感谢 Custom-Nameplates 项目提供的架构参考
- 感谢所有测试和反馈的用户
