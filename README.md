# Metallum — 单独适配版 / Standalone Adapted Fork

> 本仓库是 [kokodio/metallum](https://github.com/kokodio/metallum) 的**单独适配版**（Fork），仅包含本适配所需的改动，不随上游同步。
> This repository is a **standalone adapted fork** of [kokodio/metallum](https://github.com/kokodio/metallum). It contains only the changes required for this adaptation and does not track upstream.

## 适配内容 / What's adapted

在原有 Metallum（macOS 上的 Apple Metal 渲染后端）基础上，新增与 **Meteor Client** 的兼容：

- **修复问题**：使用 Metal 后端时，打开 Meteor GUI 的滚动区域（`WView`）会抛
  `ClassCastException: com.metallum.render.MetalDevice cannot be cast to IGpuDevice` 并崩溃。
- **新增**：`MetalDevice` 实现 Meteor 的 `IGpuDevice`（scissor 状态管理），并在
  `MetalCommandEncoder.createRenderPass` 返回时应用待定的 scissor，使 Meteor GUI 在 Metal 后端下正确裁剪。
- **条件加载**：仅当 `meteor-client` 存在时应用相关 mixin（通过 `MetallumMixinConfigPlugin` 门控），
  未安装 Meteor 时行为与上游一致。
- **构建**：`build.gradle` 增加可选的 `compileOnly` 依赖（Meteor jar），
  可通过 `-PmeteorClientJar=/path/to/meteor-client-26.2-<build>.jar` 指定，默认为 `../meteor-client-metallum/build/libs/meteor-client-26.2-local.jar`。

On top of the original Metallum (Apple Metal render backend for macOS), this fork adds Meteor Client compatibility:

- **Fixed**: with the Metal backend active, opening a scrollable Meteor GUI view (`WView`) crashed with
  `ClassCastException: com.metallum.render.MetalDevice cannot be cast to IGpuDevice`.
- **Added**: `MetalDevice` now implements Meteor's `IGpuDevice` (scissor state tracking), and pending scissors are
  applied when `MetalCommandEncoder.createRenderPass` returns, so Meteor's GUI clips correctly on Metal.
- **Conditional**: the Meteor mixins are only applied when `meteor-client` is loaded (gated by `MetallumMixinConfigPlugin`);
  without Meteor the behaviour is identical to upstream.
- **Build**: an optional `compileOnly` dependency on the Meteor jar was added to `build.gradle`;
  point it with `-PmeteorClientJar=/path/to/meteor-client-26.2-<build>.jar` (defaults to `../meteor-client-metallum/build/libs/meteor-client-26.2-local.jar`).

## 构建与安装 / Build & Install

```bash
./gradlew build
```

构建产物：`build/libs/metallum-<version>.jar`，放入 Fabric 实例的 `mods/` 目录即可（与[适配版 Meteor Client](https://github.com/functy23/meteor-client-metallum) 一同使用）。

Artifact: `build/libs/metallum-<version>.jar` — drop it into your Fabric instance's `mods/` folder and use it together with the [adapted Meteor Client](https://github.com/functy23/meteor-client-metallum).

---

## Metallum
Metallum is an experimental rendering backend for Minecraft on macOS that uses Apple’s Metal API instead of OpenGL/Vulkan. It provides a more native rendering path and aims to improve performance and efficiency on Apple Silicon.

This project is still experimental. Performance, stability, and compatibility may vary depending on your system and installed mods. If you encounter bugs, please report them on GitHub.

Compatible with Sodium.

vibecoded as hell

## Requirements
- macOS
- Apple Silicon (M1 or newer)
