# AGENT.md — metallum-meteor-client 代理工作指南

> 本文件面向在本仓库工作的 AI 代理（以及新人开发者），尽可能完整地描述项目定位、架构、构建、上游同步与发版流程。内容基于 2026-08-29 时点的代码状态（上游 0.0.24 / fe3cc3b，本仓库合并提交 bc80cbe）。

---

## 目录

1. [项目定位](#1-项目定位)
2. [仓库拓扑与 Git 布局](#2-仓库拓扑与-git-布局)
3. [关键事实速查表](#3-关键事实速查表)
4. [构建指南](#4-构建指南)
5. [架构详解](#5-架构详解)
6. [Mixin 体系与条件加载](#6-mixin-体系与条件加载)
7. [本 fork 与上游的差异清单](#7-本-fork-与上游的差异清单)
8. [上游同步 SOP（标准作业流程）](#8-上游同步-sop标准作业流程)
9. [测试与验证](#9-测试与验证)
10. [发版 SOP](#10-发版-sop)
11. [代码风格与约定](#11-代码风格与约定)
12. [常见坑（Pitfalls）](#12-常见坑pitfalls)
13. [相关链接](#13-相关链接)

---

## 1. 项目定位

**Metallum** 是一个实验性的 Minecraft（Fabric）渲染后端，在 **macOS / Apple Silicon** 上用 **Apple Metal** 替代 OpenGL/Vulkan，实现 `com.mojang.blaze3d` 的 `GpuBackend`/`GpuDeviceBackend` 抽象。上游仓库为 [kokodio/metallum](https://github.com/kokodio/metallum)（作者 kokodio，README 自述 "vibecoded as hell"，MIT 协议）。

**本仓库 `functy23/metallum-meteor-client`** 是上游的**单独适配 fork**：

- 核心目标只有一个：让 [Meteor Client](https://github.com/MeteorDevelopment/meteor-client)（实际搭配本作者的二次适配版 [functy23/meteor-client-metallum](https://github.com/functy23/meteor-client-metallum)）能在 Metal 后端下正常运行。
- 与上游保持**低频同步**：README 明确声明"仅包含本适配所需的改动，不随上游同步"，即只在需要时手动合并上游。
- **不修改上游核心渲染逻辑**。所有适配通过**新增 mixin** 和少量**防御性兜底**实现，未安装 Meteor 时行为与上游一致（由 mixin 门控保证）。

历史背景：本 fork 曾经本地先行修复过两个渲染崩溃问题（无深度附件渲染通道、已关闭纹理视图），上游后来在 0.0.24 中实现了等效修复；合并时以**上游实现为准**，本地只保留上游没有的防御性 try-catch 兜底（见第 8 节）。

## 2. 仓库拓扑与 Git 布局

| 远程 | URL | 用途 |
|---|---|---|
| `origin` | `https://github.com/functy23/metallum-meteor-client.git` | 本 fork，推送目标 |
| `upstream` | `https://github.com/kokodio/metallum.git` | 上游，只 fetch 不 push |

- 唯一工作分支：`master`（同时是 PR 目标分支）。
- 标签方案：
  - `v0.0.2` … `v0.0.23`：从上游历史继承的**上游标签**（不要动）。
  - `v0.0.23-adapted`、`v0.0.23-adapted-2`、`v0.0.23-adapted-3`、`v0.0.24-adapted`：**本 fork 的发版标签**，格式 `v<上游版本>-adapted[-<序号>]`。同一上游版本的第二个适配版起加 `-2`、`-3` 后缀。
- `gh` CLI 的一个坑：本仓库配置了两个远程，`gh release create` 不带 `-R` 时会把默认仓库解析成 **upstream（kokodio/metallum）**，必须显式加 `-R functy23/metallum-meteor-client`。
- 本地开发目录通常与伴生仓库 `../meteor-client-metallum` 并列存在（构建依赖它，见第 4 节）。

## 3. 关键事实速查表

| 项 | 值 | 出处 |
|---|---|---|
| mod id | `metallum` | `fabric.mod.json` |
| 当前版本策略 | `mod_version` 与上游保持一致（当前 `0.0.24`） | `gradle.properties` |
| Minecraft | `26.2`（快照线，fabric.mod.json depends `~26.2-`） | `gradle.properties` |
| Fabric Loader | `0.19.3`（mod 最低要求 `>=0.19.2`） | `gradle.properties` / `fabric.mod.json` |
| Java | **25**（`options.release = 25`，运行时建议 Zulu 25） | `build.gradle` |
| Gradle | 9.4.1（wrapper 固定） | `gradle/wrapper/gradle-wrapper.properties` |
| Loom | `1.16-SNAPSHOT` | `gradle.properties` |
| Sodium | `mc26.2-0.9.1-fabric`（Modrinth maven，`implementation`） | `gradle.properties` |
| Meteor Client | `compileOnly` 本地 jar，**绝不能是 runtime 依赖** | `build.gradle` |
| 运行平台 | 仅 macOS + Apple Silicon（M1+）；编译本身可在任意平台 | README / 运行时依赖 Metal |
| access widener | `src/main/resources/metallum.accesswidener`（放开 `SpvUniformBuffer/SpvSampler/SpvVariable`） | `build.gradle` / resources |
| 自动化测试 | **没有**（`test NO-SOURCE`），`./gradlew build` 即全部检查 | 构建输出 |
| 入口点 | `com.metallum.Metallum`（main），仅做 telemetry ping | `fabric.mod.json` / `Metallum.java` |

## 4. 构建指南

### 4.1 前置条件

1. **JDK 25**（例如 Zulu 25）。`java -version` 必须显示 25.x，否则 `options.release = 25` 直接编译失败。
2. **Meteor Client jar（编译期需要）**。`build.gradle` 中的 `compileOnly` 逻辑按顺序取：
   - `-PmeteorClientJar=/absolute/path/to/meteor-client-26.2-<build>.jar`（Gradle 属性，优先）；
   - 否则默认 `../meteor-client-metallum/build/libs/meteor-client-26.2-local.jar`（伴生仓库的本地构建产物）。
   
   为什么需要：`com.metallum.mixin.meteor.*` 里的两个 mixin 实现了 Meteor 的 `meteordevelopment.meteorclient.mixininterface.IGpuDevice` 接口，编译期必须能解析该类型。**运行期绝不能打进依赖**——mixin 由 `MetallumMixinConfigPlugin` 门控（见第 6 节），未装 Meteor 时这些类不会被应用，jar 里对 Meteor 的引用必须保持"软"状态。
   
   如果默认路径和 `-P` 都没有提供 jar：Gradle 配置阶段只告警不报错，但 `compileJava` 会在 meteor mixin 处报"找不到符号/程序包不存在"。**注意：CI（GitHub Actions ubuntu-latest）目前没有这个 jar，所以 CI 构建必然失败**——见 4.4。
3. 编译可在任意 OS 上进行；**运行**只能在 macOS + Apple Silicon（需要 `MTLCreateSystemDefaultDevice`、`CAMetalLayer` 等 ObjC 运行时）。

### 4.2 常用命令

```bash
# 完整构建（编译 + processResources + validateAccessWidener + sourcesJar）
./gradlew build

# 显式指定 Meteor jar
./gradlew -PmeteorClientJar=/path/to/meteor-client-26.2-123.jar build

# 只打 jar / 清理
./gradlew jar
./gradlew clean build
```

### 4.3 产物

- 主 jar：`build/libs/metallum-<version>.jar`（`fabric.mod.json` 的 `${version}` 由 `processResources` 展开）。
- 附带 `-sources.jar`（`withSourcesJar()`）。
- 安装方式：把主 jar 放进 Fabric 实例 `mods/`，与适配版 Meteor（`functy23/meteor-client-metallum`）同用。

### 4.4 CI 的现状与局限

`.github/workflows/build.yml` 在 `push tag v*` 和手动触发时运行，jobs：

1. `build`：ubuntu-latest + JDK 25（microsoft 发行版）→ `./gradlew build` → 上传 `build/libs/` 为 artifact `metallum`。
2. `publish`：仅当 tag push 时，先发布到 **Modrinth 项目 `w79ASAJD`（这是上游 kokodio 的项目 ID！）**（需要 secret `MODRINTH_TOKEN`），再用 `softprops/action-gh-release` 创建 GitHub release。

**重要事实：截至 2026-08-29，这个 workflow 在本 fork 从未成功运行过**（`gh run list` 为空，历史发版全部是手动 `gh release create` 上传本地构建的 jar）。原因：

- ubuntu 构建机上没有 `../meteor-client-metallum/...` jar，也没有传 `-PmeteorClientJar`，`compileJava` 会失败；
- 即使构建成功，`publish` 也会因为没有 `MODRINTH_TOKEN`（且 Modrinth 项目 ID 属于上游）而出问题。

因此**本 fork 的既定发版方式是手动构建 + `gh release create -R functy23/metallum-meteor-client`**（见第 10 节）。如果想修复 CI，需要解决 Meteor jar 的获取（例如在 workflow 里下载 meteor-client-metallum 的 release jar，或发布 Meteor 到 maven 并加 fallback 依赖），并处理 Modrinth 步骤。

## 5. 架构详解

### 5.1 总览

```
Minecraft (blaze3d GpuBackend SPI)
        │
        ▼
MetalBackend (GpuBackend)           ← PreferredGraphicsApiMixin 把 Metal 插到后端链首位
  │ MTLDevice.createSystemDefault()
  │ Cocoa(GLFW NSWindow/NSView) + CAMetalLayer
  ▼
MetalDevice (GpuDeviceBackend)      ← 资源工厂 + 缓存（pipeline/shader/function/depth-stencil state）
  ├── MetalCommandEncoder (CommandEncoderBackend)   ← 编码、submit、瞬态内存、销毁队列
  │     ├── MetalSurface (GpuSurfaceBackend)        ← CAMetalLayer present（FIFO/Mailbox）
  │     ├── MetalTransientMemory (TransientMemory)  ← 每 submit 轮转的 suballocation
  │     └── MetalDestructionQueue (3 个轮转队列)     ← 延迟销毁
  ├── MetalRenderPass (RenderPassBackend)           ← 渲染通道状态机（bind/draw/scissor/blend…）
  ├── MetalCompiledRenderPipeline                   ← 两个 MTLRenderPipelineState（带/不带深度）
  ├── MetalCrossShaderCompiler                      ← SPIR-V → MSL
  └── 资源对象: MetalGpuBuffer / MetalGpuTexture / MetalGpuTextureView / MetalGpuSampler /
                MetalGpuQueryPool / MetalFence
        │
        ▼ (FFI)
objc 包: ObjC / Msg / ObjCBlock / Cocoa / AutoreleasePool
mtl 包:  手写的 MTL* / CA* 薄封装（~40 个类）
```

### 5.2 启动链与后端选择

1. `PreferredGraphicsApiMixin`（`mixin/render/`）注入 `net.minecraft.client.PreferredGraphicsApi`：当设置为 `DEFAULT` 时，把"尝试的后端列表"改成 `[MetalBackend, VulkanBackend, GlBackend]`，并把选项界面文案改为 "Prefer Metal"。Metal 创建失败会自然 fallback 到 Vulkan/GL。
2. `MetalBackend.createDevice()`：
   - `MTLDevice.createSystemDefault()` 取系统默认 GPU（失败→`BackendCreationException`）；
   - 通过 `GLFWNativeCocoa` 拿 `NSWindow`/`NSView`，包成 `Cocoa`；
   - 创建 `CAMetalLayer` 并 `setViewLayer` 挂到 NSView 上；
   - 构造 `MetalDevice`（包在 Mojang 的 `GpuDevice` 里返回）。
3. `Metallum.onInitialize()`（main 入口点）只做一件事：`Telemetry.pingOncePerVersion()`。

### 5.3 FFI 层（`com.metallum.objc` + `com.metallum.mtl`）

- **不使用 JNI**，全部基于 `java.lang.foreign`（Project Panama）+ LWJGL 的 `MemorySegment` 风格：
  - `Msg`：缓存 selector 的消息发送描述符（`Msg.of("selector", retType, argTypes...)`），核心原语；
  - `ObjC`：运行时辅助（类查找、retain/release、nil 判断等）；
  - `ObjCBlock`：ObjC block（回调）封装；
  - `Cocoa`：AppKit 互操作（NSWindow/NSView、backingScaleFactor、setViewLayer）；
  - `AutoreleasePool`：autorelease 池管理。
- `mtl` 包是对 Metal/AppKit API 的**手写一对一薄封装**，每类一个 ObjC 类：`MTLDevice`、`MTLCommandQueue`、`MTLCommandBuffer`、`MTLRenderCommandEncoder`、`MTLBlitCommandEncoder`、`MTLBuffer/Texture/TextureDescriptor/TextureUsage/TextureType`、`MTLRenderPipelineDescriptor`、`MTLVertexDescriptor/Format/StepFunction`、`MTLDepthStencilDescriptor`、`MTLSampler*`、`MTLPixelFormat`、`MTLResourceOptions/StorageMode/HazardTrackingMode`、`MTLFence`、`CAMetalLayer`、`CAMetalDrawable` 等，外加枚举常量表（blend factor/op、compare function、cull mode、primitive type 等）。
- **修改 FFI 的规则**：selector 名、参数/返回编码（`ADDRESS/JAVA_LONG/...`）、是否 `static`/实例方法必须与 Apple 文档逐字一致；上游 0.0.24 就修过一处（`minimumLinearTextureAlignmentForPixelFormat:` → `minimumTextureBufferAlignmentForPixelFormat:`）。`MTLTextureUsage` 这类常量表也可能与 Apple 头文件有出入（0.0.24 修正了 `PixelFormatView=16`、`ShaderAtomic=32`）。

### 5.4 着色器编译管线（`MetalCrossShaderCompiler`）

Minecraft 的着色器源是 GLSL（`ShaderSource`），Metal 需要 MSL。转换链：

```
GLSL (Mojang 语法)
  → 复用 Mojang Vulkan 后端的 GLSL 前端 (com.mojang.blaze3d.vulkan.glsl.*)
  → SPIR-V (IntermediaryShaderModule，经 MetalDevice 的 shaderCache 缓存)
  → SPIRV-Cross（LWJGL org.lwjgl.util.spvc 绑定，原生库由 LWJGL 提供）
      选项: MSL 平台 macOS、MSL 4.0、启用 decoration binding、
            native texture buffer、FLIP_VERTEX_Y、禁用 frag depth builtin（可开）
  → MSL 源码
  → MTLDevice.getOrCompileFunction()（缓存 MslFunctionKey → MTLFunction）
  → MTLRenderPipelineState（withDepth / withoutDepth 两个变体，见下）
```

要点：

- `MetalDevice` 维护三层缓存：`shaderCache`（SPIR-V）、`functionCache`（MTLFunction）、`compiledPipelines`（IdentityHashMap：`RenderPipeline` → `MetalCompiledRenderPipeline`）。
- `MetalCrossShaderCompiler` 还负责：重绑定 interface variables（顶点属性按 `RenderPipeline` 的 vertex format 命名对齐、容忍未提供的输入）、push constants 绑定、收集 active resources 生成 `ResourceBinding` 表（`MetalCompiledRenderPipeline.ResourceBinding`，kind 包括 SAMPLED_IMAGE / TEXEL_BUFFER / uniform 等），`MetalRenderPass.pushDescriptor()` 运行期按这张表绑定资源。
- `BUILT_IN_UNIFORMS = {Projection, Lighting, Fog, Globals}`。
- **`MetalCompiledRenderPipeline` 总是同时编译两个管线变体**：`withDepthPipeline`（depthStencilPixelFormat = Depth32Float）和 `withoutDepthPipeline`（= Invalid）。渲染通道没有深度附件（例如 Meteor Blur 的 FBO 通道）时必须用后者。上游 0.0.24 前的行为是"管线声明了深度模板状态时 withoutDepth = NULL"，会导致崩溃 "Native pipeline is unavailable"。

### 5.5 渲染对象与生命周期

- `MetalGpuBuffer`：`GpuBuffer` 子类；`isCpuAccessible()` 由 usage 决定（**上游 0.0.24 起索引缓冲也 CPU 可访问**，加上 MAP_READ / MAP_WRITE / CLIENT_STORAGE）；CPU 可访问的缓冲创建时直接 `writeDirect`（零拷贝写入 shared/storage 内存），否则走 encoder `writeToBuffer`（blit 上传）。内部有 `sliceStorage(offset)` 支持别名视图。
- `MetalGpuTexture` / `MetalGpuTextureView`：纹理有引用计数语义（`addView()`/`removeView()`）；视图的 `nativeHandle()` **懒创建**——全 mip 范围时 `ObjC.retain(texture.nativeHandle())`，否则 `MTLTexture.newTextureView(...)`；`close()` 时 `queueNativeRelease(handle)` + `removeView()`。**关闭后的视图不再抛异常**（nativeHandle 仍非空，返回已排队释放的句柄，由销毁队列的延迟性兜底）。
- `MetalGpuSampler`、`MetalGpuQueryPool`（timestamp）、`MetalFence`：对应 Metal 的 sampler / counter sample buffer / MTLFence/事件。
- `MetalTransientMemory`：实现 Mojang `TransientMemory`。`BLOCK_SIZE = 512KiB`，CPU 块（`nmemAlloc`，16 字节对齐）与 GPU 块（USAGE_MAP_READ|WRITE 的 `MetalGpuBuffer`）各一个 `TransientBlockAllocator`；**每次 `encoder.submit()` 后 `rotate()`**，旧块进入销毁队列延迟释放——这是"transient 分配当帧有效"语义的实现。

### 5.6 编码、提交与同步（`MetalCommandEncoder`）

- **`MAX_SUBMITS_IN_FLIGHT = 3`**：最多 3 个 command buffer 在 GPU 上排队。`submit()` 流程：结束当前 render pass → `commitWithCompletionBlock`（信号量在 `submitSignalBlocks[slot]` 里释放）→ 轮换 `InFlight` 槽位 → `awaitSubmitCompletion(currentSubmitIndex - 3, 5000ms)`（**5 秒超时直接抛 IllegalStateException**）→ `transientMemory.rotate()` → `destroyQueue.rotate()`。
- `MetalDestructionQueue`：3 个 `List<Runnable>` 轮转队列（数量 = MAX_SUBMITS_IN_FLIGHT）。`queueForDestroy/add` 把释放动作放进当前队列，每次 submit 轮转销毁最老队列——保证销毁时对应 GPU 工作至少已过去 3 个 submit，避免 use-after-free。释放动作抛异常只记日志（资源可能泄漏），不崩。
- `MetalSurface`：包装 `CAMetalLayer`，支持 `FIFO` 与 `MAILBOX` 两种 present mode（Mailbox 映射为 layer 的 `maximumDrawableCount=2` 语义，见 `CAMetalLayer.configure`）；`isSuboptimal()` 恒 false；持有一个 `pendingPresentEncoder` 处理 present。
- `MetalFence` / `waitForSubmittedGpuWork()`：设备级等待。
- `createRenderPass(RenderPassDescriptor)`：创建 `MetalRenderPass` 并作为 `RenderPassBackend` 返回；**本 fork 在此 RETURN 处注入 Meteor scissor 应用**（见第 6 节）。

### 5.7 `MetalRenderPass`

`RenderPassBackend` 的完整实现（~700 行）：状态机管理 viewport/scissor/blend/depth-stencil/cull、顶点/索引/_uniform_ 绑定（`pushDescriptor` 按编译期反射的 `ResourceBinding` 表驱动）、draw（含 TriangleFan 软件展开 `drawTriangleFan`，用 mapped index buffer 模拟）、`allocateTransient`、clear（颜色/深度/组合）。注意：

- `VALIDATION = SharedConstants.IS_RUNNING_IN_IDE`：**仅在 IDE 里跑客户端时为 true** 的校验开关（关闭的 buffer/视图等直接抛异常）。生产环境（正常启动游戏）全部跳过，所以生产路径依赖"延迟销毁 + 容错绑定"而不是校验。
- 本 fork 在 `pushDescriptor` 的 SAMPLED_IMAGE 分支保留了一个 try-catch：`textureView.nativeHandle()` 抛 `IllegalStateException`（如今只剩 "Failed to create Metal texture view for mip range..." 会抛）时**跳过该 descriptor**并（IDE 下）打 warn，而不是崩掉渲染线程。这是防御性兜底，上游没有。

### 5.8 Sodium 集成

Sodium 0.9+ 自带 GPU 后端抽象（`net.caffeinemc.mods.sodium.client.gpu.device.*`）。两个 mixin 把 Metal 接进去：

- `DrawBackendMixin` → `DrawBackend.chooseBackend`：当 `RenderSystem.getDevice().getDeviceInfo().backendName().equals("Metal")` 时返回 `DrawBackend.VK_INDIRECT`（Sodium 的 VK-indirect 风格绘制路径）。
- `DrawContextMixin` → `DrawContext.create`：同条件返回 `MetalDrawContext`。
- `MetalDrawContext extends VKIndirectContext`：把 Sodium 的 region/camera 数据写进 `push_constants` uniform（相机平移 + region 存活时间 + region id），复用 Sodium 的 VK-indirect 布局。
- `SodiumPreferredGraphicsApiMixin`：重定向 Sodium 设置界面里图形 API 枚举的显示名，把 `DEFAULT` 项显示为 Minecraft 的 caption（即 "Prefer Metal"）。

### 5.9 Telemetry

- 客户端：`Telemetry.pingOncePerVersion()` 在 mod 初始化时向 `https://metallum-telemetry.kirill-zaripow.workers.dev/ping` POST 一次（query 带 os.version 与 mod 版本）；标记文件 `config/metallum-telemetry.txt` 记录上次 ping 的版本（写 `off` 可永久关闭）。
- 服务端：`telemetry/` 目录是一个独立的 **Cloudflare Worker**（`wrangler.toml` + `src/index.js` + D1 数据库 `metallum-telemetry`，表结构见 `schema.sql`），`POST /ping` 自增计数，`GET /stats` 返回 JSON。**不属于 mod 构建产物**，只是上游附带的部署源码。
- 这是上游带来的行为，fork 未改动；做隐私相关讨论时要知道它的存在。

## 6. Mixin 体系与条件加载

`metallum.mixins.json`：`package com.metallum.mixin`，plugin `MetallumMixinConfigPlugin`，`compatibilityLevel JAVA_25`，client mixins：

```
render.PreferredGraphicsApiMixin      ← macOS 必加载（后端注入 + "Prefer Metal" 文案）
sodium.DrawBackendMixin               ← 仅当 sodium 已加载
sodium.DrawContextMixin               ← 仅当 sodium 已加载
sodium.SodiumPreferredGraphicsApiMixin← 仅当 sodium 已加载
meteor.MetalDeviceMixin               ← 仅当 meteor-client 已加载
meteor.MetalCommandEncoderMixin       ← 仅当 meteor-client 已加载
```

`MetallumMixinConfigPlugin.shouldApplyMixin` 决策顺序（读懂这个就不会加错 mixin）：

1. **非 macOS → 全部不加载**（游戏在别的平台启动时本 mod 完全惰性）；
2. `mixin.sodium.*` → `FabricLoader.isModLoaded("sodium")`；
3. `mixin.meteor.*` → `isModLoaded("meteor-client")`；
4. 其余（目前只有 `render.PreferredGraphicsApiMixin`）→ 无条件加载。插件还会读 `options.txt` 的 `preferredGraphicsBackend` 判断用户是否选了 `default`（`isDefaultGraphicsApi`），供非 sodium/meteor 的可门控 mixin 使用（当前无使用者，但加新 mixin 时可以利用这个机制）。

**Meteor 适配层的两个 mixin 在做什么**：

- `MetalDeviceMixin`：给 `MetalDevice` 实现 Meteor 的 `IGpuDevice`（`meteor$pushScissor`/`meteor$popScissor`/`meteor$onCreateRenderPass`）。Meteor 的 `Scissor#push/pop` 会把 active `GpuDeviceBackend` 强转成 `IGpuDevice` 存全局 scissor；Meteor 只给 GL/Vulkan 设备实现了该接口，缺了这个 mixin 打开任何带滚动区域（`WView`）的 Meteor 窗口都会 `ClassCastException: com.metallum.render.MetalDevice cannot be cast to IGpuDevice` 并崩掉渲染线程。
- `MetalCommandEncoderMixin`：注入 `MetalCommandEncoder.createRenderPass` 的 RETURN，把待定 scissor 转发给新建的 `RenderPassBackend.enableScissor(...)`。对应 Meteor 对 GL 的 `GlCommandEncoderMixin`。
- 两者模仿 Meteor 自己的 `GlDeviceMixin`；`IGpuDevice` 只来自 `compileOnly` jar，`defaultRequire=1` 且配置在 `overwrites.requireAnnotations=true` 下运行。

## 7. 本 fork 与上游的差异清单

`git diff upstream/master...master`（2026-09-24，合并 0.0.24 之后；上游 HEAD 仍为 `fe3cc3b`，无新增提交）全部内容：

| 文件 | 差异 |
|---|---|
| `AGENT.md` | 本文件，上游没有（fork 自有的代理工作指南） |
| `README.md` | fork 说明、适配内容、构建/安装说明（双语） |
| `build.gradle` | Meteor jar 的可选 `compileOnly` 依赖（`-PmeteorClientJar` 属性 + 默认伴生路径） |
| `metallum.mixins.json` | 注册 `meteor.MetalDeviceMixin`、`meteor.MetalCommandEncoderMixin` |
| `mixin/MetallumMixinConfigPlugin.java` | 增加 `.mixin.meteor.` → `isModLoaded("meteor-client")` 门控分支 |
| `mixin/meteor/MetalDeviceMixin.java` | **新增**：`MetalDevice implements IGpuDevice`（scissor 状态） |
| `mixin/meteor/MetalCommandEncoderMixin.java` | **新增**：`createRenderPass` RETURN 时应用待定 scissor |
| `render/MetalCommandEncoder.java` | 1 行改动：`createRenderPass` 返回后（实际是 RenderPass 创建路径）与 scissor 接驳的调用点适配 |
| `render/MetalDevice.java` | 1 行改动（适配 IGpuDevice 接驳的访问面） |
| `render/MetalRenderPass.java` | 保留防御性 try-catch：`pushDescriptor` 中 `nativeHandle()` 抛异常时跳过 descriptor（上游没有） |

合并历史中的决策记录（后续同步可能再用到）：

- **上游 0.0.24 采纳了本 fork 之前本地修复的两个崩溃**（无深度管线变体、已关闭纹理视图）。合并冲突时两边选了上游实现：`MetalCompiledRenderPipeline.java`、`MetalGpuTextureView.java` 现在与上游**逐字节一致**；本地 `MetalRenderPass` 的 try-catch 因为仍覆盖"mip view 创建失败"的路径而保留。
- `gradle.properties` 的 `mod_version` 始终跟随上游（合并即取上游值），fork 的身份通过发版标签 `v*-adapted[-N]` 表达，而不是改版本号。

## 8. 上游同步 SOP（标准作业流程）

```bash
git fetch upstream --tags          # 拉上游与标签
git log --oneline master..upstream/master   # 看新增提交
git rev-list --count master..upstream/master
git show <commit> --stat           # 逐个审阅改动
git merge upstream/master          # 合并（预期有冲突，见下）
# 解决冲突后：
./gradlew build                    # 必须通过
git commit --no-edit               # 或写合并说明
git push origin master
```

**冲突决策原则**（按优先级）：

1. **上游对 `mtl/` FFI 封装、渲染核心逻辑的修改 → 一律取上游**。那是上游的领域知识（Metal 语义、Apple API 行为），fork 不应持有分叉版本。
2. **上游实现了 fork 已有的等效修复 → 取上游实现**，并清理本地注释/包装（降低未来合并摩擦）。本地历史意义由 commit message 承载。
3. **fork 独有的适配层（mixin、门控、build.gradle、README）→ 保留本地**；若上游动了被 mixin 的目标方法签名，修 mixin 而不是回退上游。
4. **拿不准时**：先看 `git log --follow <file>` 双边历史，再对比 `git show upstream/master:<path>` 与本地版本语义差异；上游修复通常更"整体"（例如纹理视图的懒创建 + retain + 销毁队列延迟释放是一套设计，不要只摘半截）。

**历史上出现过的冲突文件**（再同步时优先关注）：`render/MetalCompiledRenderPipeline.java`、`render/MetalGpuTextureView.java`、`render/MetalRenderPass.java`（上游若再次改 `pushDescriptor`/`nativeHandle` 语义，需重新评估本地 try-catch 兜底是否还有意义——若 `nativeHandle()` 已不再抛出关闭相关异常，兜底只覆盖 "Failed to create Metal texture view" 路径）。

**同步后必查清单**：

- [ ] `gradle.properties` 版本号取上游值；
- [ ] `./gradlew build` 通过；
- [ ] `git diff upstream/master...master` 只剩第 7 节列出的适配面（含 fork 自有的 `AGENT.md`）；
- [ ] `metallum.mixins.json` 与 `MetallumMixinConfigPlugin` 的一致性（新 mixin 都有门控）；
- [ ] 若上游改了 `MetalDevice`/`MetalCommandEncoder` 结构，检查两个 meteor mixin 的 `@Shadow`/`@Mixin` 目标仍能解析。

## 9. 测试与验证

- **没有单元测试、没有集成测试**（`src/test` 不存在；`./gradlew test` 显示 `NO-SOURCE`）。`./gradlew build` 包含的全部检查：编译（release 25）、`processResources`（fabric.mod.json 版本展开）、`validateAccessWidener`、jar/sourcesJar 打包。
- 因此"保证构建和测试通过"的实际含义 = `./gradlew build` 成功；**运行时行为只能在 macOS + Apple Silicon 上进游戏验证**（IDE 内跑客户端时 `VALIDATION=true` 会激活额外校验路径，注意与生产的差异）。
- 修改渲染代码后的建议冒烟清单（人工）：主世界渲染正常、打开 Meteor GUI（含滚动列表 WView）不崩、Meteor Blur 等无深度附件通道正常、Sodium 区块渲染正常、窗口缩放/全屏切换正常。
- 构建警告：Gradle 提示 "Deprecated Gradle features ... incompatible with Gradle 10"（来自 Loom），可忽略。

## 10. 发版 SOP

以"上游刚发了 X.Y.Z，fork 要发适配版"为例：

1. **确认版本**：`gradle.properties` 的 `mod_version` = 上游版本（合并上游时自然带入；若无上游合并只是重发，则保持不变）。
2. **构建**：`./gradlew clean build`，确认 `build/libs/metallum-<version>.jar` 与 `-sources.jar` 生成。
3. **推送主干**：`git push origin master`。
4. **打标签**：
   ```bash
   git tag -a v<version>-adapted -m "Metallum <version> 单独适配版 / Adapted build of upstream <version> (<sha>) with Meteor Client compatibility"
   git push origin v<version>-adapted
   ```
   同一上游版本的第 N 次适配重发用 `v<version>-adapted-2`、`-3`…（历史先例：v0.0.23-adapted / -2 / -3）。
5. **创建 GitHub Release**（**必须带 `-R`**，见第 2 节的坑）：
   ```bash
   gh release create v<version>-adapted build/libs/metallum-<version>.jar \
     -R functy23/metallum-meteor-client \
     --title "Metallum <version> 单独适配版 / Adapted build" \
     --notes "<双语说明：上游更新内容 + 本 fork 保留的适配 + 搭配使用链接>"
   ```
   notes 风格参照 v0.0.24-adapted：分「上游 X.Y.Z 更新 / Upstream changes」「本仓库保留的适配 / Adaptation retained」两节，末尾附 `https://github.com/functy23/meteor-client-metallum/releases`。
6. **不要指望 CI 发版**：tag push 会触发 `build.yml`，但它会在 ubuntu 上因缺 Meteor jar 编译失败（且 Modrinth publish 指向上游项目），历史发版从未依赖它（见 4.4）。
7. 发布后人工验证 release 资产里有主 jar（不带 `-dev`/`-sources` 后缀），说明文案完整。

## 11. 代码风格与约定

- **Java**：4 空格缩进；`final` 参数/局部变量风格明显（上游大量使用 `final`）；类按 `@Environment(EnvType.CLIENT)` 标注；可空性用 `org.jspecify.annotations.@Nullable`；FFI 值类型用 `java.lang.foreign.MemorySegment`。
- **命名**：Mojang 接口实现类前缀 `Metal*`；mixin 类名 = 目标类名 + `Mixin`，注入方法前缀 `metallum$`，Meteor 接口方法保持 Meteor 的 `meteor$` 前缀；`mtl` 包类名与 ObjC 类一一对应。
- **mixin 编写规则**：目标方法要写 `remap = false`（目标多为 Mojang blaze3d 非 obfuscated 类或 Sodium/Meteor 类，不在 Mojang mappings 里）；新 meteor mixin 必须确认 `MetallumMixinConfigPlugin` 的 `.mixin.meteor.` 路径门控能覆盖（包路径必须在 `com.metallum.mixin.meteor` 下）；`@Mixin(MetalDevice.class)`/`@Mixin(MetalCommandEncoder.class)` 目标是本 mod 类（duplicating 普通注入即可，不必 accessor）。
- **注释**：类级 Javadoc 解释"为什么"（尤其 meteor mixin 说明了对应 Meteor GL 侧的实现与崩溃信息）；合并冲突解决时倾向保留解释约束的注释，删掉叙述"改了什么"的注释。
- **Gradle 文件**：`build.gradle` 用 tab 缩进（照抄现状）。
- **兼容性红线**：`compileOnly` 的 Meteor 依赖永远不许升级为 `implementation`/`modImplementation`（会把 Meteor 类打进运行时 classpath，破坏"未装 Meteor 时行为与上游一致"的保证）；`fabric.mod.json` 的 `depends` 不要加 `meteor-client`。

## 12. 常见坑（Pitfalls）

1. **`gh release create` 忘加 `-R`** → 落到 upstream 仓库或报"tag 未推送"。永远 `-R functy23/metallum-meteor-client`。
2. **CI 期望与现实的落差**：workflow 看起来全自动，实际从未跑通；不要把它当作"构建通过"的证据，也不要在 PR 描述里引用它。
3. **CI 里的 Modrinth project `w79ASAJD` 是上游的**，fork 不应向其发布；如果将来修 CI，先移除/替换该步骤。
4. **Meteor jar 缺失时的报错形态**：Gradle 配置阶段只有 warning（"file ... not found"），真正的错误在 `compileJava`：meteor mixin 里 `IGpuDevice`、`meteordevelopment.*` 找不到符号。先检查 `-PmeteorClientJar` 或 `../meteor-client-metallum/build/libs/meteor-client-26.2-local.jar`。
5. **Loom 1.16-SNAPSHOT**：SNAPSHOT 插件，构建可复现性一般；本地 Gradle 9.4.1 wrapper 固定，不要随手升 wrapper。
6. **Java 25 硬约束**：`options.release = 25` + `fabric.mod.json depends java >=25`。用低版本 JDK 会直接失败。
7. **VALIDATION 双面性**：`MetalRenderPass.VALIDATION = SharedConstants.IS_RUNNING_IN_IDE`。IDE 内复现的"校验异常"在正常启动中并不存在；反之，生产环境靠的是延迟销毁和容错路径，IDE 调试时的崩溃日志可能误导。
8. **纹理视图生命周期**：`nativeHandle()` 懒创建 + retain，`close()` 后句柄交给 3-submit 延迟销毁队列；不要在 fork 侧手动 `ObjC.release` 这类句柄，一律走 `queueForDestroy`/`queueNativeRelease`。
9. **上游标签继承**：仓库里 `v0.0.2..v0.0.23` 是上游标签，fork 发版标签一定带 `-adapted` 后缀，避免混淆（也避免和未来上游同名标签冲突）。
10. **上游改动可能静默影响适配层**：上游改 `MetalDevice`/`MetalCommandEncoder` 的私有字段/方法名会让 meteor mixin 的 `@Shadow` 编译失败——构建通过不代表 mixin 语义还成立，合并后要人工过一遍两个 meteor mixin。
11. **README 是对外门面**：改适配行为时同步更新 README 的"适配内容"小节（双语），保持与实际 diff 一致。

## 13. 相关链接

- 上游仓库：<https://github.com/kokodio/metallum>
- 本仓库：<https://github.com/functy23/metallum-meteor-client>
- 伴生适配版 Meteor Client：<https://github.com/functy23/meteor-client-metallum>（本 fork 构建默认依赖它的本地 jar；运行时必须同装）
- Meteor Client 上游：<https://github.com/MeteorDevelopment/meteor-client>
- 最新发版：<https://github.com/functy23/metallum-meteor-client/releases>
- Telemetry Worker：`telemetry/` 目录（Cloudflare Workers + D1）
