# Steve AI 文档

## 核心文档

### 基础层
1. [概述](00-overview.md) - 项目介绍、快速开始
2. [核心架构](01-architecture.md) - 整体结构、核心组件
3. [配置参考](03-config.md) - 配置文件、技术栈

### AI 核心
4. [世界感知](04-world-knowledge.md) - 环境扫描与感知系统
5. [提示词构建](05-prompt-builder.md) - LLM 提示词工程系统
6. [LLM 集成](06-llm.md) - 提供商、缓存、熔断器
7. [弹性系统](14-resilience.md) - LLM 调用容错机制

### 执行层
8. [动作系统](02-actions.md) - 动作执行、可用动作列表
9. [多 Agent 协作](07-multi-agent.md) - 象限分配、并发控制
10. [代码执行引擎](08-code-execution.md) - GraalVM、SteveAPI

### 架构模式
11. [插件系统](12-plugin-system.md) - 可扩展的动作注册框架
12. [事件系统](13-event-system.md) - 解耦的组件通信
13. [实体管理](15-entity-management.md) - Steve 生命周期管理

### 数据层
14. [记忆系统](09-memory.md) - 对话历史、世界状态
15. [可用结构](10-structures.md) - Minecraft 结构参考
16. [NBT 建筑模板](03-config.md#nbt-建筑模板) - 模板配置

### 界面层
16. [Steve GUI](11-steve-gui.md) - 侧边栏聊天界面实现

## 黑客马拉松项目

黑客马拉松期间开发的施工系统相关文档：

1. [mempalace 集成](hackathon/01-mempalace-integration.md) - mempalace × ReAct 模板调度
2. [四阶段 plan 模式](hackathon/02-plan-mode.md) - plan-then-build 状态机
3. [外部 HTML Plan Dashboard](hackathon/施工流程.md) - HTTP server + React + Three.js UI
