# Via PageZoom LSPosed 模块 — 开发文档

> 目的：记录 Hook 原版 Via 提供「页面缩放」的**全部实现细节**，便于接手或适配新版本 Via。
> 最后更新：v1.0（Hook 原版 `mark.via.gp` 7.3.3）。

---

## 0. 方案对比

| | APK 补丁方案 | Via PageZoom（本模块，LSPosed） |
|---|---|---|
| 做法 | 反编译 → 改 smali/axml/arsc → 重打包 → 重签名 | Hook 原版进程，改运行时行为 |
| 装前要求 | 必须卸载官方 Via（签名冲突、数据清空） | 不用动官方 Via，共存 |
| 包名/应用名 | 可改（改 manifest/arsc） | **改不了**（manifest 静态属性） |
| 官方升级 | 需重新打补丁 | 原版照常升级（但混淆类名变了要重新适配） |
| 风险 | 改了 dex，可能 VerifyError | 只 Hook，失败仅记日志、不影响原版 |
| 依赖 | 无 | 需要 LSPosed（实测 v2.2.0） |

**共同点**：功能实现（注入方式、prefs、对话框交互）完全照搬，见 §3。

---

## 1. 目标环境（实测）

| 项 | 值 |
|---|---|
| 设备 | Redmi（2602BRT18C），Android 16 / SDK 36，aarch64，KernelSU root |
| 框架 | LSPosed **v2.2.0 (7854)**，Zygisk 模式（`/data/adb/modules/zygisk_lsposed`） |
| 目标应用 | `mark.via.gp`，Via **7.3.3**，versionCode **20260823**，minSdk 19 / targetSdk 36，单 dex，R8 混淆 |
| 模块包名 | `io.github.zw1.viapagezoom` |

> 同一台设备上可以同时装原版 `mark.via.gp` 和旧的 APK 补丁版（`com.viamod.browser`）。
> 本模块**只 Hook `mark.via.gp`**。

---

## 2. 模块打包约定（legacy Xposed API）

LSPosed 2.2.0 **同时支持**新的 `libxposed` API 和传统 Xposed API。本模块走**传统 API**，
依据是机器上已装的 `io.github.zw1.copydirectanybrowser` 模块用的就是这套（实测可被 LSPosed 正常加载）。

必需的三件套：

1. **`AndroidManifest.xml`** 的 `<application>` 下四个 meta-data：
   ```xml
   <meta-data android:name="xposedmodule"       android:value="true" />
   <meta-data android:name="xposeddescription"  android:value="..." />
   <meta-data android:name="xposedminversion"   android:value="82" />
   <meta-data android:name="xposedscope"        android:value="mark.via.gp" />
   ```
2. **`assets/xposed_init`**：纯文本，一行入口类全名
   `io.github.zw1.viapagezoom.ViaPageZoomHook`
3. 入口类 `implements de.robv.android.xposed.IXposedHookLoadPackage`

编译用 `de.robv.android.xposed:api:82`（`api.xposed.info` 仍可下载；Maven Central 上没有）。

**注意**：`framework.dex` 里只有 legacy 的**内部**类（`XC_LoadPackage` 等），
模块元数据 key（`xposedmodule` 等）不在 framework/daemon/manager 的 dex 里 ——
不用去找，直接照抄已装模块的约定即可（`aapt2 dump xmltree` 看它的 manifest 最省事）。

---

## 3. 功能实现（与 APK 补丁版一致）

### 3.1 注入（★核心，不要改）

```
webView.loadUrl("javascript:(function(){try{document.documentElement.style.zoom=N/100;}catch(e){}})();")
```

- **必须 `loadUrl`，不能用 `evaluateJavascript`**：实测后者在 Via 的 WebView 封装
  （`t4/b`，覆写了 `loadUrl` 加请求头、没覆写 `evaluateJavascript`）下**完全不生效**。
  用户早期手写的 `ViaMod_参考.apk` 也证明 `loadUrl` 可用。
- 目标 `document.documentElement.style.zoom`（布局缩放，非 `setTextZoom`）。
- 清除时值换成 `''`（`zoom <= 0` 分支）。
- `N/100` 是 JS 运行时除法（150 → 1.5）。
- 在**主线程**执行（`runOnUi`），因为 `loadUrl` 必须主线程。

### 3.2 存储

- `getSharedPreferences("viamod_pagezoom", MODE_PRIVATE)`，写在 **Via 自己的数据目录**。
- key = `Uri.parse(url).getHost().trim().toLowerCase()`，值 int 50..200。
- 无记录 = 不注入（`getInt(host, -1) <= 0` 直接返回）。

### 3.3 对话框（关闭即确认）

用 Via **自带**的对话框构建器 `w5.k`（保持与应用主题一致），反射调用：

| 调用 | 作用 |
|---|---|
| `w5.k.l(Context)` | 静态入口，拿 builder |
| `.e0(String)` | 标题「页面缩放」 |
| `.y(View)` | 自定义内容（LinearLayout[TextView 标签 + SeekBar]） |
| `.O(String, OnClickListener)` | 主按钮位 → 「恢复默认」 |
| `.U(OnDismissListener)` | 关闭监听 → **保存** |
| `.f0()` | 显示（按钮点击后自动 dismiss） |

`resetPending` 静态标志的**时序依据**（与 APK 版一致）：`w5/k$a` 按钮包装器
**先调用户监听器、再 `Dialog.dismiss()` → onDismiss**。所以「恢复默认」先置位 flag 再清记录，
dismiss 回调看到 flag 就跳过回存。

打开对话框时先 `resetPending = false` 防泄漏。

SeekBar `max = 150`，`progress = zoom - 50`，显示值 = `progress + 50`。
`onProgressChanged` 里**即时 `applyValue` 实时预览** + 更新标签。

---

## 4. Hook 点（Via 7.3.3 / versionCode 20260823）

全部是 R8 混淆名。**每个 Hook 独立 try/catch**，任一失败只记日志、不影响其它、不崩原版。

| # | 类.方法 | 类型 | 作用 |
|---|---|---|---|
| 1 | `w9.k.s0()` → `int[]` | after | 工具箱菜单 id 数组。若不含 42 则**追加**。默认菜单（无 `displayedmenus` 偏好时）30 项、自定义菜单走 `Y2()` 解析偏好——追加式处理两种情况 |
| 2 | `i8.l.a(Context,int)` → `h8.a` | before | 工具工厂。id==42 时返回我们造的菜单项，跳过原方法 |
| 3 | `i8.l.c()` → `Set` | after | 合法工具 id 集合（39 项），追加 42，让「定制菜单」界面接受它 |
| 4 | `c8.s6.C9(int,int)` | before | **工具点击分发**（主 Fragment）。id==42 时 `setResult(null)` 跳过原逻辑，改弹我们的对话框 |
| 5 | `p4.j.onPageStarted(WebView,String,Bitmap)` | after | 页面加载 → 应用存储的缩放 |
| 6 | `p4.j.onPageFinished(WebView,String)` | after | 同上（双保险，幂等） |
| 7 | `WebViewClient.onPageCommitVisible` | after | `p4.j` 没覆写这个方法，所以在基类上 hook 并用 `thisObject.getClass().getName()=="p4.j"` 过滤 |

菜单项构造：`new h8.a(42, drawable, "页面缩放", true)`。
图标复用字体大小图标：先 `lb.b.a(ctx, x7.o.h0, x7.u.se)` 拿到 `Drawable`
（**不能把资源 id 直接传给 `h8.a` 的 Drawable 参数**——那是 APK 补丁版踩过的 VerifyError，§6）。

资源 id 通过**读混淆静态字段**拿到（版本内更稳）：
`x7.o.h0` = `0x7f08004d`（`drawable/c6`）、`x7.u.se` = `0x7f100361`（`string/wk`）。
拿不到就退回系统图标 `android.R.drawable.ic_menu_zoom`。

当前 WebView 的获取（照搬 APK 版）：`fragment.d()` → `r4.a` → `.p()` → `t4.b`(WebView)，
失败再退回 `fragment.C8()`；都拿不到就 Toast「请先打开网页」。

**id 42 是空闲的**：实测 `i8/l.a` 与 `c8/s6.C9` 的 packed-switch 都是 `1..41`，42 落在 default。
（`i8/l.a` 的 default 会落到 `:pswitch_3` 的代码块，返回 id 22 的项；因为我们在 before-hook 里
就 `setResult` 掉了，所以无影响。）

---

## 5. 构建

```bash
BUILD_TOOLS=/path/to/build-tools/36.0.0 \
ANDROID_JAR=/path/to/platforms/android-36/android.jar \
API_JAR=/path/to/api-82.jar \
./build.sh
```

| 组件 | 来源 | 说明 |
|---|---|---|
| JDK 17 | [Adoptium](https://adoptium.net/) Temurin 17 | `javac` / `keytool` |
| build-tools 36 | `sdkmanager "build-tools;36.0.0"` 或 `build-tools_r36_linux.zip` | aapt2 / d8 / zipalign / apksigner |
| platform-36 | `sdkmanager "platforms;android-36"` 或 `platform-36_r02.zip` | 提供 `android.jar` |
| Xposed API 82 | `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar` | 仅 compileOnly |

流水线：`javac -source/-target 8` → `d8 --min-api 26` → `aapt2 link`（`-A assets` 带上 `xposed_init`）
→ python 塞入 `classes.dex` → `zipalign -p 4` → `apksigner`（首次运行自动生成 debug keystore，口令 `android`）。

> 为什么不用 Gradle/AGP：本模块没有 Android 组件、没有资源引用，手工四步足够，
> 且省掉 AGP/Kotlin 的大量下载。注意 aapt2/d8 要与宿主架构匹配（x86_64 宿主用官方 linux 包；
> aarch64 宿主需另找原生版本，或改用 Gradle 让 AGP 处理）。

产物：`build/ViaPageZoom-1.0.apk`，约 25 KB，v1+v2+v3 签名校验通过。

---

## 6. 踩坑记录

1. **`evaluateJavascript` 在 Via 里不生效**（最贵的坑，APK 补丁方案已踩过）——注入一律 `loadUrl`。§3.1。
2. **资源 id ≠ Drawable**：`h8.a` 构造的第 2 参是 `Drawable`，直接把 `int` 资源 id 传进去
   smali/javac 都不报错，但运行时 dex verifier 报
   `VerifyError: register v2 has type Integer but expected Reference`。必须先过 `lb.b.a`。
   （Java 里被泛型/反射掩盖，所以本模块用 `XposedHelpers.newInstance` 显式给 `Class[]` 签名。）
3. **legacy vs libxposed**：不要凭 `framework.dex` 里没有 `xposedmodule` 字符串就断定不支持 legacy。
   判断依据应是「已装模块怎么写的」——`aapt2 dump xmltree` 看它的 manifest + 查 `assets/xposed_init`。
4. **`onPageCommitVisible` 不在 `p4.j` 里**：`findAndHookMethod("p4.j", "onPageCommitVisible", ...)`
   会抛 `NoSuchMethodError`。要在 `android.webkit.WebViewClient` 基类上 `hookAllMethods` 再按
   `thisObject` 的实际类名过滤。
5. **`C9` 是 void**：before-hook 里用 `param.setResult(null)` 跳过原方法体。
   （`C9` 开头对 id != 0x15 会打点 `t8/f;->f(I)` 埋点统计，跳过它无副作用。）
6. **作用域与重启**：LSPosed 新启用模块**必须重启设备**才会注入 zygote。仅 force-stop 目标应用
   通常不够（zygote 已在启动时决定加载哪些模块）。
7. **LSPosed 管理器不是普通应用**：`pm list packages` 里可能看不到 `org.lsposed.manager`
   （被 HMA / fusehide 之类的隐藏模块藏了，或由 daemon 动态加载）。
   打开方式：拨号 `*#*#5776733#*#*`（`zygisk_lsposed/action.sh` 里的 secret code），
   或从 KernelSU/Magisk 的模块页面进。
8. **启用状态在 `modules_config.db`**：`/data/adb/lspd/config/modules_config.db`
   （SQLite，表 `modules` / `modules_state(enabled)` / `scope(module_pkg_name, app_pkg_name)`）。
   安装 APK 后 LSPosed 会自动把模块写进 `modules` 表；`enabled` 与 `scope` 由管理器 UI 控制。
   —— **注意**：直接改这个库需要 lspd 重新读，且写入前要处理 `-wal`，不建议脚本改；用 UI 最稳。
9. **签名**：模块用独立 debug 证书，与 Via 无关；模块不注入自身（作用域只有 `mark.via.gp`）。

---

## 7. 适配新版 Via（混淆名变化时）

R8 每次发版都可能重新编号，届时 Hook 全失效（日志会打 `[hookXxx]` 异常，但**不会崩原版**）。步骤：

```bash
# 1) 拉新版 APK 并反编译
adb shell pm path mark.via.gp
adb pull <base.apk>
java -jar baksmali.jar d classes.dex -o smali_new

# 2) 重新定位 5 个 Hook 点（用行为特征找，不靠名字）
#    - 工具工厂: 方法签名 (Landroid/content/Context;I)Lh8/a; 且方法体是 packed-switch 1..N
#    - 菜单默认数组: 方法体内 const-string "displayedmenus" 且返回 [I
#    - 点击分发: 主 Fragment 里 packed-switch 且 case 调 PageZoom 类逻辑的位置
#    - WebViewClient: 有 onPageStarted/onPageFinished 的类（构造参数含分发器）
#    - 对话框: 有 l(Context)/e0/y/O/U/f0 的类
# 3) 改 ViaPageZoomHook.java 顶部的常量
# 4) ./build.sh && 安装 && 重启 && ./verify.sh
```

最省事的特征：`const-string "displayedmenus"`（唯一）、`packed-switch 1..41` 且返回 `h8/a`（唯一）。

---

## 8. 文件清单

```
ViaMod-LSPosed/
├── AndroidManifest.xml                  # 模块元数据（xposedmodule/scope/minversion）
├── assets/xposed_init                   # 入口类名
├── src/io/github/zw1/viapagezoom/
│   └── ViaPageZoomHook.java             # 全部逻辑（Hook + 缩放 + 对话框）
├── build.sh                             # 一键构建（javac/d8/aapt2/zipalign/apksigner）
├── verify.sh                            # 冒烟测试（清日志→重启 Via→抓日志→判定）
├── README.md                            # 安装/启用/验证/排查
├── DEVELOPMENT.md                       # 本文档
├── .gitignore                           # 忽略构建中间产物与 debug keystore
└── build/                               # 构建输出（不入库）
    ├── ViaPageZoom-1.0.apk              # 产物
    ├── debug.keystore                   # 首次构建自动生成（口令 android）
    └── classes/ dex/ …                  # 中间产物
```
