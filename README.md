# ChatBubble - 纯PacketEvents高性能聊天气泡插件

## 🎯 概述

ChatBubble 是一个基于 **PacketEvents API** 的高性能 Minecraft 聊天气泡插件，完全使用网络层直接发包实现，无需创建真实实体，性能卓越。

## ✨ 主要特性

### 🚀 纯PacketEvents架构
- **直接网络发包** - 使用 PacketEvents API 直接操作网络层
- **虚拟实体** - 不创建真实实体，只发送 Packet，性能更优
- **无反射依赖** - 完全基于 PacketEvents，无需 NMS 反射
- **轻量级** - 最小化依赖，仅需 PacketEvents 和 PlaceholderAPI

### 🎨 视觉效果
- **三层实体结构** - 背景、文本、尾部分离渲染
- **本地Z偏移** - 完美解决层级遮挡问题
- **META_BILLBOARD** - 气泡始终面向玩家摄像机
- **ADV_COMPONENT** - 支持完整的 MiniMessage 格式
- **平滑动画** - 入场、停留、退场动画

### 🎮 游戏体验
- **Passenger系统** - 气泡自动跟随玩家移动
- **蹲下适配** - 自动适配玩家蹲下状态
- **ItemsAdder支持** - 完美支持 ItemsAdder 字形
- **三段式背景** - 动态背景生成，支持不同高度

## 🛠️ 技术架构

### 核心组件
```java
// 主要类结构
├── Main.java                    # 插件主类，事件监听
└── PacketEventsBubbleManager.java # 纯PacketEvents实现
```

### 实体管理
```java
// 三层实体结构
BubbleInfo[] = [背景实体, 文本实体, 尾部实体]

// Passenger系统
player.addPassenger(bubbleEntities)
```

### 元数据设置
```java
// 本地Z偏移解决层级问题
META_TRANSLATION = new Vector3f(0f, yOffset, tz)
// 始终面向玩家
META_BILLBOARD = (byte)3
// 完整文本格式
META_TEXT = ADV_COMPONENT
```

## 📋 安装要求

- **Minecraft**: 1.20+
- **Bukkit/Spigot/Paper**: 1.20+
- **PacketEvents**: 2.9.4+
- **PlaceholderAPI**: 2.11.6+
- **可选**: ItemsAdder 4.0+ (用于表情支持)

## ⚙️ 配置说明

### 基础配置
```yaml
bubble:
  enabled: true                    # 是否启用气泡功能
  y-offset: 0.55                  # 垂直偏移
  inner-px: 140                   # 内边距
  auto-wrap: true                 # 自动换行
  max-lines: 4                    # 最大行数
  lifetime-ticks: 80              # 气泡持续时间
  show-shadow: false              # 文字阴影
```

### 高级配置
```yaml
bubble:
  # 每行高度对应的内边距
  inner-px-per-height:
    "13": 140
    "23": 120
    "33": 110
  
  # 宽度系数（对齐 IA 的 scale_ratio）
  width-mul-per-height:
    "13": 1.00
    "23": 0.90
    "33": 0.85
  
  # 三段式背景配置
  sets:
    13:
      left: "chatL_13"
      mid: "chatM_13"
      right: "chatR_13"
      tail: "chatT_13"
```

## 🎮 命令

- `/chatbubble reload` - 重新加载配置
- `/cb reload` - 重新加载配置（别名）
- `/testia packet` - 测试 PacketEvents 气泡
- `/testia broadcast` - 测试广播功能

## 🔐 权限

- `chatbubble.reload` - 允许重新加载配置（默认：op）

## 🚀 性能优势

### 与传统方法对比

| 特性 | ChatBubble (PacketEvents) | 传统 Bukkit API |
|------|---------------------------|-----------------|
| **实体创建** | 虚拟实体，仅发包 | 真实实体，占用内存 |
| **性能开销** | 极低，仅网络包 | 较高，实体管理 |
| **内存占用** | 最小化 | 较高 |
| **网络效率** | 直接发包，无中间层 | 经过 Bukkit 处理 |
| **扩展性** | 高度可扩展 | 受 Bukkit 限制 |

### 技术亮点

1. **零实体开销** - 不创建真实实体，只发送网络包
2. **直接网络操作** - 绕过 Bukkit 层，直接操作网络
3. **智能缓存** - PacketEvents 内部优化，减少重复操作
4. **内存友好** - 最小化内存占用，适合大型服务器

## 🔧 开发说明

### 核心实现

```java
// 实体生成
WrapperPlayServerSpawnEntity spawnPacket = new WrapperPlayServerSpawnEntity(
    entityId, entityUuid, EntityTypes.TEXT_DISPLAY, location, 0f, 0, null
);

// 元数据设置
EntityData translationData = new EntityData(META_TRANSLATION, EntityDataTypes.VECTOR3F, new Vector3f(0f, yOffset, tz));
EntityData textData = new EntityData(META_TEXT, EntityDataTypes.ADV_COMPONENT, component);

// Passenger系统
WrapperPlayServerSetPassengers passengerPacket = new WrapperPlayServerSetPassengers(playerEntityId, passengerIds);
```

### 关键技术点

1. **META_TRANSLATION** - 本地Z偏移解决层级问题
2. **META_BILLBOARD** - 确保气泡始终面向玩家
3. **ADV_COMPONENT** - 支持完整的文本格式
4. **Passenger系统** - 自动跟随玩家移动

## 🐛 故障排除

### 常见问题

1. **气泡不显示**
   - 检查 PacketEvents 是否正确安装
   - 确认 PlaceholderAPI 依赖
   - 查看控制台错误信息

2. **层级问题**
   - 检查 META_TRANSLATION 设置
   - 确认 Z 偏移值是否正确
   - 验证 META_BILLBOARD 设置

3. **性能问题**
   - 检查 PacketEvents 版本兼容性
   - 确认网络包发送频率
   - 监控内存使用情况

## 📝 更新日志

### v2.0.9 - 纯PacketEvents实现
- 🎉 **完全重写** - 移除所有 Bukkit API 依赖
- 🚀 **纯PacketEvents** - 使用 PacketEvents API 直接发包
- 🎨 **层级修复** - 完美解决文本被背景遮挡问题
- 🎮 **Passenger系统** - 气泡自动跟随玩家移动
- ✨ **完整动画** - 缩放、透明度、进出场动画
- 🎯 **ItemsAdder支持** - 完美支持字形和表情
- 🔧 **高度一致** - 修复生成和动画高度不一致问题

### v1.0.0 - 初始版本
- 基础聊天气泡功能
- Bukkit API 实现

## 📄 许可证

本项目采用 MIT 许可证。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

### 贡献指南
1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 🙏 致谢

- 感谢 **PacketEvents** 团队提供的优秀网络库
- 感谢 **PlaceholderAPI** 提供的占位符支持
- 感谢 **ItemsAdder** 提供的字形系统
- 感谢所有测试和反馈的用户

## 📞 支持

- **GitHub Issues**: [提交问题](https://github.com/your-repo/chatbubble/issues)
- **Discord**: [加入社区](https://discord.gg/your-server)

---

⭐ 如果这个项目对你有帮助，请给它一个星标！
