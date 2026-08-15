# S6-1 · blur/stencil shader 降级到 GLSL 120

## 0. 背景

S0 §5 已记录限制：`blur.frag`/`stencil.frag` 为 `#version 130`，在 MC 1.8.9 的 GL 2.1 context
（对应 GLSL 1.20）下会编译失败。二者只在真实播放、有封面时触发（高斯模糊封面背景 / 圆角裁剪），
故打开 UI 冒烟测试未暴露。本轮将其降级。

## 1. 改动

- 模块：渲染着色器资源
- 原文件：`src/main/resources/tritium/shaders/blur.frag`、`stencil.frag`
- 原依赖：无（纯 GLSL）
- 原行为：`#version 130`，`blur.frag` 用 GLSL 1.30 的 `texture(...)` 采样
- 分类：**C（Forge/GL 2.1 适配）**。不改着色逻辑，仅降到目标环境支持的 GLSL 版本。
- 改动：
  - `blur.frag`：`#version 130`→`#version 120`；4 处 `texture(...)`→`texture2D(...)`（GLSL 1.20 无 `texture` 重载）
  - `stencil.frag`：`#version 130`→`#version 120`（已用 `texture2D`，仅改版本行）
- 改动原因：GLSL 1.20 是 MC 1.8.9（OpenGL 2.1）最高支持版本；`texture()` 为 GLSL 1.30 引入。
- 是否改变外部行为：**否**。着色效果（加权高斯模糊 / 混合纹理按 stencil alpha 裁剪）不变；
  仅底层 GLSL 语法版本等价。
- 验证：全量 grep 确认 12 个 shader 均为 `#version 120`，无 `texture(`/`in`/`out` 等 1.30+ 语法；
  `./gradlew build` 通过（exit=0）；解包 jar 确认内嵌 `blur.frag`/`stencil.frag` 已为 `#version 120`。
  运行时验证待真实播放触发封面模糊背景后复测。

## 2. 备注

- `blur.frag` 的 `for (int i = 1; i <= u_radius; i++)` 用 uniform int 作循环上界：
  桌面 GLSL 1.20 允许非常量循环条件（仅 ES 2.0 要求常量上界），MC 1.8.9 为桌面 GL，无需改动。
- 其余 10 个 shader（blend/bloom/deconverge/rogq/roq/rq/rqg/rqt/vertex/vf_fadeout）此前已为 `#version 120`，本轮未动。
