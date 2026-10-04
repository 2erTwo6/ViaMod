# Via PageZoom — LSPosed 模块

用 **LSPosed/Xposed Hook** 给**原版 Via 浏览器**（`mark.via.gp`）加上「页面缩放」功能，
**不修改、不重打包、不重签名**原版 APK。

> 这是 `ViaMod`（APK 打补丁方案）的替代实现。原方案要把 `via-release-cn.apk` 拆开改 smali
> 再重签，装之前必须先卸载官方版；本模块直接在原版 Via 进程里 Hook，官方版照常使用、照常更新。

**产物：`build/ViaPageZoom-1.0.apk`**（模块包名 `io.github.zw1.viapagezoom`）

---

## 1. 功能

| 项 | 说明 |
|---|---|
| 入口 | 浏览器工具箱菜单新增「页面缩放」项（工具 id = 42，复用字体大小图标） |
| 范围 | 50% – 200%，SeekBar 拖动**实时预览** |
| 确认方式 | **无「确定」按钮**——任何方式关闭对话框（返回键 / 点背景）都等价确认，保存当前值并立即生效 |
| 恢复默认 | 「恢复默认」按钮清除该域名记录并恢复 100% |
| 记忆 | **按域名**存 SharedPreferences（文件名 `viamod_pagezoom`，key = `Uri.parse(url).getHost().toLowerCase()`） |
| 自动应用 | 每次页面加载（onPageStarted / onPageFinished / onPageCommitVisible）自动重新应用 |
| 实现 | CSS 布局缩放：`document.documentElement.style.zoom = N/100`（**不是** setTextZoom 文字缩放） |

与官方 Via「网站设定 → 字体大小」互不影响。

---

## 2. 安装与启用

```bash
# 安装模块（不会影响原版 Via）
adb install -r ViaPageZoom-1.0.apk
# 或
pm install -r /data/local/tmp/ViaPageZoom-1.0.apk
```

然后在 **LSPosed 管理器**里启用：

1. 打开 LSPosed 管理器
   - 拨号盘输入 `*#*#5776733#*#*`（LSPOSED 的 secret code），或
   - 从 KernelSU / Magisk 管理器的模块页面进入
2. **模块** → 找到 **Via PageZoom** → 打开开关
3. **作用域**：勾选 `mark.via.gp`（模块 manifest 已声明 `xposedscope=mark.via.gp`，一般会自动勾上）
4. **重启手机**（新启用模块必须重启才会注入 zygote；只想生效于 Via 时，强制停止 Via 后重开也可能够）

> 手机上有 `mark.via.gp`（原版）和 `com.viamod.browser`（旧的 APK 补丁版）两个包。
> 本模块**只 Hook `mark.via.gp`**。

---

## 3. 验证

启用并重启后：

```bash
# 1) 看模块是否注入
adb shell "logcat -d -s ViaPageZoom:*"
# 期望看到: ViaPageZoom: loading into mark.via.gp process=mark.via.gp
#           ViaPageZoom: hooks installed
#           ViaPageZoom: toolbox menu extended with id 42 (total N)

# 2) 打开 Via，点右下角菜单 → 工具箱 → 找到「页面缩放」
# 3) 拖动滑块，页面应立即缩放；返回键关闭后重新加载同域名页面，缩放保持
```

一键冒烟测试（清日志 → 重启 Via → 抓日志）：

```bash
./verify.sh
```

---

## 4. 故障排查

| 现象 | 原因 / 处理 |
|---|---|
| 工具箱里没有「页面缩放」 | 模块未启用 / 未重启 / 作用域没勾 `mark.via.gp`。看 `logcat -s ViaPageZoom:*` 是否有 `hooks installed` |
| 日志里没有 `ViaPageZoom` | 模块没被加载：确认 LSPosed 里已启用、作用域正确、已重启 |
| 有 `loading into` 但无 `hooks installed` | Hook 失败，日志会带 `[hookXxx]` 异常。多半是 Via 版本变了导致混淆类名变化（见 DEVELOPMENT.md §4） |
| 点「页面缩放」没反应 | 看日志有无 `dialog shown`；`showDialog` 异常会 Toast 报错 |
| 页面缩放无效 | 日志应有 `zoom saved for <host> = N%`。注入走 `loadUrl`，若 Via 改了 WebView 封装需重新确认（见 DEVELOPMENT.md §3） |
| 老用户菜单里没有新项 | 工具箱菜单已自定义过。可在「定制菜单」里手动添加「页面缩放」 |

---

## 5. 构建

```bash
BUILD_TOOLS=/path/to/build-tools/36.0.0 \
ANDROID_JAR=/path/to/platforms/android-36/android.jar \
API_JAR=/path/to/api-82.jar \
./build.sh
```

需要自备三样东西（`build.sh` 不联网）：

| 组件 | 获取方式 |
|---|---|
| JDK 17+ | 发行版包管理器，或 [Adoptium](https://adoptium.net/) |
| Android build-tools（含 aapt2/d8/zipalign/apksigner） | `sdkmanager "build-tools;36.0.0"`，或直接下 `build-tools_r36_linux.zip` |
| platform `android.jar` | `sdkmanager "platforms;android-36"`，或下 `platform-36_r02.zip` |
| Xposed `api-82.jar` | `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`（仅 compileOnly；Maven Central 上没有） |

流水线：`javac -source/-target 8` → `d8 --min-api 26` → `aapt2 link`（`-A assets` 带上 `xposed_init`）
→ python 塞入 `classes.dex` → `zipalign -p 4` → `apksigner`（首次运行自动生成 `build/debug.keystore`，口令 `android`）。

> 为什么不用 Gradle/AGP：本模块没有 Android 组件、没有资源引用，手工四步足够，且省掉 AGP/Kotlin 的大量下载。
> 注意 `aapt2`/`d8` 要与宿主架构匹配（x86_64 宿主用官方 linux 包；aarch64 宿主需另找原生版本）。

目录：

```
ViaMod-LSPosed/
├── AndroidManifest.xml          # xposedmodule / xposedscope 元数据
├── assets/xposed_init           # 入口类名（LSPosed 的 legacy 加载方式）
├── src/io/github/zw1/viapagezoom/ViaPageZoomHook.java
├── build.sh                     # 一键构建
├── verify.sh                    # 启用后的冒烟测试
├── README.md                    # 本文档
└── DEVELOPMENT.md               # Hook 点、混淆类名、实现细节、踩坑
```

---

## 6. 已知边界

- 依赖 Via 7.3.3（versionCode `20260823`）的**混淆类名**，Via 升级后若 R8 重新编号则 Hook 失效
  （不会崩溃：每个 Hook 都独立 try/catch，失败只记日志）。
- `CustomTabs` / 独立 WebView 片段未接入分发链，不在作用范围。
- 极少数页面脚本自行重置根节点样式时，翻页/刷新后 Hook 会重新应用。
- 桌面图标名仍是「Via」——模块改不了原版 manifest（如需要，可在模块里做资源替换，但那是另一件事）。
