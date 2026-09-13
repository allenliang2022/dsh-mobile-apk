# AGENTS.md — dsh-mobile-apk 开发地图（索引主文件）

> **AI 主动更新条款（必须最先执行）**：本文件是唯一权威入口，采用「主文件索引 + docs/AGENTS/ 详档」结构。**任何代码变更导致描述失真时：① 主文件对应行当轮更新；② 细节写入 docs/AGENTS/ 对应详档（坑→gotchas.md 追加递增编号；版本历史→更新记录表登记，3 条之前的行滚入 changelog-archive.md）。** 若发现文档与源码不一致，以源码为准并当场修正。**查询规范：优先用 grep 在 docs/AGENTS/ 详档内定位（见下方路由表），不要凭记忆猜细节。**
>
> **过期风险声明**：代码演进可能快于文档更新；一切以源码为准。

---

## 1. 仓库概览

- **角色**：DeepSeek Harness 安卓壳应用（`com.dsharnessmobile.shell`）。职责边界 = 只保留安卓平台权能与桥（前台服务/看门狗/WebView/SAF 桥/快照解压/UndoGate/ADB 授权/审计/控制台/日志）；**AI 可见能力全部来自插件**。
- **运行时形态**：内嵌 Termux 快照（`assets/snapshot.tar.xz` → files/usr + files/home）；引擎 `@deepseek-ai/dsh` **0.1.5-rc.1**（0.13.3 起构建期 overlay；0.13.7 追上游升版，快照本轮重建）；**/api 前缀**浏览器鉴权——壳侧 EngineAuth 带 Cookie）监听 127.0.0.1:3080；WebView 加载引擎 Web UI。**例外（坑 78）**：上游路由匹配是「exact 表先于 prefix 表」，插件用 `kind:'exact'` 注册在 `/api/...` 下会**绕过**该前缀的 cookie 鉴权与 Host 校验——「/api 全前缀鉴权」不成立，鉴权必须由插件自己带（见坑 78）。
- **可选原生 Linux（本分支草稿）**：ARM64/API28+ PRoot 与 loader 从 APK nativeLibraryDir 执行；普通 Termux 引擎不变，不自带 Debian。x86_64 明确无 native PRoot。真源/ABI 门禁/源码许可与未验收项见 `docs/NATIVE-PROOT.md`；stable 命令 `dsh-debian`，默认 rootfs 在保留的 workspaces 用户区。
- **构建链**：minSdk 26 / targetSdk 34 / compileSdk 36；Kotlin 2.0.21；AGP 8.8.2；Java 17。
- **无线调试修复分支（未发布）**：bridge 0.2.5；按类型发现本机端点、独立 NSD 监听器、强制新扫描、三态后台观测、异步操作票据与有界服务器恢复；review2 修正 Android adb 监听参数为 hostless `tcp:5037`，新增随包服务器真实启动测试；当前规范和回归入口见 `docs/AGENTS/adb-chain.md`。不得把配对偏好、TCP 可达、编译成功或模拟器接口测试冒充系统开关/真实配对验收。
- **版本状态**：**0.13.7fx-1 修订构建（vc36，2026-09-11，发布资产同 tag 替换）**：发布后 issue 修复批=会话迁移 link(2) 回退（#154）+ 上传选择器统一 SAF（#160）+ 设置页「打开配置文件」接管（#152）+ 移动端 @ 菜单目录行下钻与多选（#163）；详见 §5 与坑 64-66。**0.13.7fx-1 已发布（vc35，Release v0.13.7fx-1，15 资产，2026-09-11）**：本版=**@ 文件回到上游原生**（退役注入的「引用本机文件」菜单项与整条 SAF 路径桥；`@` 只列会话工作区文件，官方语义）＋ **引擎启动目录改应用工作区根**（未分组会话不再把 `/` 当工作区，issue #150/#144）＋ **修复 0.1.5 起失效的移动端 Enter 换行守卫**＋ 退役空转的 `web-frontend-index.html` 运行时补丁 + 合入贡献者 PR #157（抽屉底部安全区）。**0.13.7 已发布（vc34，Release v0.13.7，15 资产，2026-09-10）**：本版=**追上游 dsh 0.1.5-rc.1**（上游 ui-layout 基线 + 移动适配层 0.2.0 + 原生「打开方式」PathOpen/openPathChooser + 引擎树补丁 F3/F4 + polyfill 装配与 Iterator 垫片修复 + UI 冗余清理）；认证台账 `docs/AGENTS/0.13.7-CERTIFICATION.md`（版本口径统一表在 §3）。0.13.6 已发布（vc33，Release v0.13.6，15 资产，2026-09-10）：本版=**附件持久化 Android 守卫（图片输入/read_image 全链修复，构建期+运行时补丁双写）** + 自有 WebView DOM 快速通道（`android_web_dump` + `ref=wN/css:/text:/role:`）+ `android_ui_global`（返回/主页/最近任务/通知栏）+ `android_env_prepare`/`android_app_launch`/前台真值/输入回读断言 + `phone-control` 预设 + 顶部系统 inset 通道 + 弹出面板几何守卫 + 悬浮球光环提亮；认证台账 `docs/AGENTS/0.13.6-CERTIFICATION.md`。0.13.5 已发布（vc32，Release v0.13.5，15 资产，2026-09-10 收官）：无障碍控制通道 v1（`DeviceControlService` + 控制队列 + 双通道门禁等价且无障碍优先 + 无障碍截屏 API 30+ + 完整语义树/同名消歧）+ 悬浮球跟随工作会话/新会话落临时工作区 + 授权面重构 + #126/#124 引擎补丁 + #125 能力自动补全 + 构建提速（WSL 内原生执行，172s vs ~29min）。0.13.3 已发布（vc30，Release v0.13.3 正式版，14 资产）。0.13.2 已发布（vc29，悬浮球 v2.1 全套）。0.13.6 已把 0.13.5 的残余项（#127/#128/#129/#130 主体/#133/#134/#135）全部落地；开放跟踪：#130 剩余（Android 13+ 无障碍输入法替代内嵌 IME 的自动就绪）、#115（市场 Phase2）、#108（数据备份）。arm64 真机（V2425A）链路验证已通过。
- **兄弟仓库**（协调仓子目录，本仓内含自包含副本——**坑 36 同步铁律**：协调仓改子仓源码/bump 版本后必须 robocopy 镜像到本仓，lib/ 产物一并拷）：`dsh-shell-termux`、`dsh-client-ui-responsive`（0.2.1，移动适配层）、`dsh-host-web-compat`（0.1.13，polyfill/桥）、`plugins/`（bridge 0.2.5（无线调试修复分支） / manage 0.2.3 / model-capability 0.2.1 / linux-env / file-open）、`vendor/`（marketplace、undo-savepoint、dsh-model-sync + PATCHES.md）。
- **补丁镜像面（0.13.8 起）**：本仓 `scripts/patches/**`（registry.json / apply-patches.mjs / README.md / tests/**）与 `scripts/check-patch-mirror.mjs`、`scripts/build-apk-013.ps1` 是协调仓的**逐字节镜像**（云端自包含构建检出本仓）——改补丁必须双树同批；`check-patch-mirror.mjs` 在两仓 CI 与构建链强制比对，单边演进即拒（apk #171 教训；镜像纪律详见 `docs/AGENTS/RUNTIME-PATCHES.md` §7.1）。
- **上游** deepseek-ai/deepseek-harness（协调仓 `dsh/` 只读 checkout）：**零改动**；一切适配走补丁/插件/壳侧。
- **模拟器优先于 PR 与真机请求（2026-09-10 用户定例）**：改动落地顺序 = 本地构建 → **MuMu x86_64 模拟器实测** → 再谈 PR；**不得以「等真机验证」为由推迟模拟器实测、或把模拟器实测挂在 PR 之后**。真机（arm64）验证是**发布前**的补充门禁，不是开发循环的前置条件；模拟器上验不过的改动不允许开 PR，模拟器上验过的改动也不因缺真机而搁置（release notes 标注真机待验即可）。
- **PR 流程铁律（2026-09-10 用户定例）**：**任何代码/文档改动一律走 PR，禁止直接 push 到 `main`**（人类与 AI 开发助手同等适用）。流程：建分支（`<type>/<简短描述>`）→ 提交（`<type>: <描述>`，见 pr-guidelines）→ 推送分支 → 开 PR（标题/描述按模板，标签 1-3 个）→ CI Gate 绿 → 合并 → 删分支。**例外（不改仓库内容的外部动作，可直连 API）**：Release 资产上传/发布、issue 评论与开关、标签操作。协调仓 `dsh-mobile` 同规（见其 AGENTS.md §4）。

## 2. 构建命令速查（在协调仓根执行）

```powershell
pwsh -File scripts\build-apk-013.ps1 -Suffix ""   # 一键双 ABI（门禁失败即拒打包）
pwsh -File scripts\build-apk-013.ps1 -Fast        # dev 快速档（单 ABI x86_64 + preset 1；产物禁发布）
node scripts\build-snapshot-013.mjs <arm64|x86_64> # 快照构建（Windows 需 WSL）
node scripts\smoke-bridge.mjs                      # bridge 冒烟
adb -s <serial> install -r -t out\v<版本>\...apk    # 装机（同签名 debug keystore）
# 直接 Gradle 必须核对快照 ABI：-PtargetAbi=arm64-v8a 或 x86_64（默认 x86_64）
```

门禁链：统一补丁 → 引擎 overlay 抽验（check-engine-overlay）→ 单 pass 注入 → 挂载集 → 机密 → third-party → elf-check → gradle。云端自包含构建：`.github/workflows/build-apk.yml`。

## 3. 高频雷点 TOP（一行一条；**全量坑位以 `docs/AGENTS/gotchas.md` 为准**——坑号为**历史分配**、**允许空缺**（空缺号保留占位、一律不重编号，避免破坏既有引用），新增自 72 起；本节的「全量 45 坑」类数字一律不写死，用 `grep -c '^[0-9]\+\. \*\*' docs/AGENTS/gotchas.md` 现数）

- **坑 37**：快照重解压中（~8-12 分钟）**禁 force-stop/杀进程**——唯一完成标志 = `.snapshot-fingerprint` 翻转 + `.snapshot-transaction` 消失（0.13.3 事务化后不再用 `.dsh-backup`）；中途杀 → 事务恢复会自动回滚，但仍建议等完成。
- **坑 18/30**：debug 包默认 x86_64 快照装 arm64 必崩；真机安装只用 ps1 对应 ABI 命名产物。
- **坑 19**：真机改 cordis.patch.yml 后必须冷启动 app（force-stop + start）才重装配。
- **坑 33**：壳侧所有本地引擎调用一律 `Proxy.NO_PROXY`（系统代理劫持探针）。
- **坑 38**：运行时补丁升级引擎时必须逐个核对（rc.2 锁定 asset 会抹掉新引擎代码——0.13.3 prompt 阻断实锤）。
- **坑 44**：WSL 9p 挂载 chmod 无效——归档权限归一化只在 `inject-all.py` 重打包层做（门禁校验注入后快照）。
- **坑 45**：快照含 9 个指向 `files/usr/...` 的绝对符号链接（vi/vim/nc/editor/pager 等 applet）——暂存解压必须传 `runtimeRoot=filesDir` 放行，否则静默丢链。

## 4. 详档路由表（grep 形式查询）

| 要查什么 | grep 建议 | 文档 |
|---|---|---|
| 坑 N 详情/新坑登记 | `grep -n "^38\.\|^39\." docs/AGENTS/gotchas.md` 或按关键词（`borrowSession`/`store-rehome`/`MANAGE_EXTERNAL_STORAGE`/`run-as`/`overlay`） | docs/AGENTS/gotchas.md |
| 某 .kt 文件职责/函数位置 | `grep -n "<文件名>.kt" docs/AGENTS/modules.md` | docs/AGENTS/modules.md |
| 桥方法签名/通道语义 | `grep -n "<方法名>" docs/AGENTS/BRIDGE-API.md`；0.13.3 增量 grep `pickFilePath\|remote.mux\|EngineAuth` docs/AGENTS/bridge-api.md | docs/AGENTS/bridge-api.md |
| 构建失败/门禁/环境差异 | `grep -n "门禁\|WSL\|abi" docs/AGENTS/build-and-env.md` | docs/AGENTS/build-and-env.md |
| 运行时补丁（assets/patched） | `grep -n "patched\|applyAssetPatch" docs/AGENTS/RUNTIME-PATCHES.md` | docs/AGENTS/RUNTIME-PATCHES.md |
| 35 模块地图/依赖方向 | `grep -n "模块\|依赖" docs/AGENTS/ARCHITECTURE.md` | docs/AGENTS/ARCHITECTURE.md |
| android.* API 清单/守卫点 | `grep -n "API 等级\|android\." docs/AGENTS/ANDROID-API-USAGE.md` | docs/AGENTS/ANDROID-API-USAGE.md |
| **无障碍 API/权限面全量参考** | `grep -n "CAPABILITY_\|takeScreenshot\|ACTION_SET_TEXT\|GLOBAL_ACTION" docs/AGENTS/ACCESSIBILITY-API.md` | docs/AGENTS/ACCESSIBILITY-API.md |
| gradle 依赖与升级策略 | `grep -n "依赖\|升级" docs/AGENTS/DEPENDENCIES.md` | docs/AGENTS/DEPENDENCIES.md |
| GPL 合规三形态 | `grep -n "copyright\|LICENSES" docs/AGENTS/gpl-compliance.md` | docs/AGENTS/gpl-compliance.md |
| 待办与已知缺口 | `grep -n "F[0-9]\|未实现" docs/AGENTS/known-gaps.md` | docs/AGENTS/known-gaps.md |
| 版本历史 | `grep -n "0.13.2" docs/AGENTS/changelog-archive.md` | docs/AGENTS/changelog-archive.md |

## 5. 更新记录表（最近 3 条；完整历史 docs/AGENTS/changelog-archive.md）

| 时间 | 版本 | 更新内容 | 更新者 |
|---|---|---|---|
| 2026-09-13 | native-proot（验收补充，未发布） | ARM64 APK-native PRoot/loader、原子状态/启动器与精确 ABI/source 产物门禁；新增隔离 Android instrumentation、真实 APK 升级工作区保持检查与事务回滚/恢复测试；协调布局 CLI/镜像自校验与递归负控、13 文件配套同步清单；既有源码构建已过，新增模拟器与设备验收以 NATIVE-PROOT.md 的具体证据为准，不把 synthetic peer 当真实同步；rootfs 不自动迁移 | — |
| 2026-09-12 | **0.13.8-b 批 B2 制度性门禁 + 文档对账（进行中）** | **制度性防复发**：① 状态登记制（新建两份 `.github/PULL_REQUEST_TEMPLATE.md` 四栏：持有者/写路径/外部真源/同步路径 + 证据要求）与登记表 `scripts/state-registry.json`（8 条跨层状态，逐条带可核对的 evidence）；② 桥面对称性门禁 `scripts/check-bridge-symmetry.mjs` + 基线 `bridge-symmetry-baseline.json`（壳侧 35 个 `@JavascriptInterface`（AndroidBridge 34 + ST-10 新补 `getImmersiveMode`）与独立对象 `BackGateBridge` 2 个，vs 页面类型面 15 个成员；基线只许减少，新增不对称即拒）；③ 门禁覆盖清单化 + SKIP 纪律 `check-gate-skips.mjs`（12 项声明门禁接进五位置、两条链差集 = 0、发布链 `--run --require` 即 SKIP=0）；④ 性能 §7.2 度量入口 `scripts/perf/count-compose.mjs`（自检）+ `scripts/perf/measure-steady.ps1`（`/proc/net/tcp` LISTEN 口径，禁 adb forward 假阳性）+ `check-perf-instrumentation.mjs`（A1 出厂值 P-AC-01 + 壳侧缺口台账）；⑤ A1：出厂 profile seed `scripts/lib/profile-seed.mjs`（构建期写 `patchReload: startup`，dev 档 `DSH_PROFILE_PATCH_RELOAD=live`）+ 引擎树补丁 `perf-patch-reload-N1`（web 模板默认 + 存量升级归一化，P-AC-24）+ 行为回归。**两处真缺陷顺路修掉**：registry 里 `attach-durable-F2` 的 marker 带文档后缀导致资产/快照配对**静默 SKIP**（假绿，收紧后核对组合 2→3、SKIP=0）；check-runtime-assets 三处资产级 SKIP 与 check-patch-mirror 两处 SKIP 此前不计入总数（现全部计数）。**文档对账**：RUNTIME-PATCHES §2 字节数按实测改（旧记 138,991 失真 → 重出前实测 136,136 → **已重出 138,025**，与快照 tar 逐字节同源 sha `BDAEF25C…`，`check-runtime-assets --require` 组合 3/SKIP=0）、§7.1 补齐 F5 publish 站/F7/G1/G2/N1 并去掉 F6 重复登记、新增 §7.2 重出手册；AGENTS §1 「/api 全前缀鉴权」加 exact 路由例外（坑 78）、§3 改指针式；坑 72-86 登记（含 dev-notify 提供的通知渠道/RemoteInput/尾随 lambda/onReceive 预算四条）。设备侧 `scripts/verify-state-sync.mjs`（只改真源用例集 + `--self-test` 故意失败样本）落地，**模拟器实测 10/10 PASS**（ST-01/02/10/11/12 × on/off，收敛 205ms-2221ms）；⑦ 注入链双向对齐（**修剪**源码已删的陈旧成员 + **补齐**包内新增文件，三计数器 replaced/added/pruned）+ 产物级门禁 `scripts/check-inject-completeness.mjs`（成员集合 == 源包 + 相对导入可解析，正是 P0：包内新增文件曾丢、陈旧成员曾残留）+ Kotlin 嵌套块注释门禁 `scripts/check-kotlin-comments.mjs`（字符串感知 + 自检）；坑 87-89 登记；⑧ ST-16 scripts 半边：剥离清单后置断言 `scripts/check-strip-noop.mjs`（`--stage` 构建期 / tar 产物期 + `--base` 反 no-op + `--self-test` 两向自检）接入两条链与快照构建；发布链聚合入口新增 `SKIP=` 合计并在 `--require` 下要求 0 skip（实测「缺快照面 → SKIP=2 → 发布链红」）；坑 90 登记（RemoteInput 直回后 cancel 被系统忽略，需同 (tag,id) 重投再 cancel） | AI 开发助手 |
| 2026-09-11 | **0.13.7fx-1 修订构建（vc36）** | **发布后 issue 修复批**：① **#154 会话迁移 link(2) 回退**（asset 从 0.1.5 包重出、两处站点都带 `EACCES/EPERM/ENOTSUP → rename`（用顶层 `rename`）+ 构建期补丁 `spj-migration-link-F5` + 回归 `scripts/patches/tests/spj-migration-link-f5.test.mjs`）；② **#160 上传选择器**（统一 SAF 文档选择器 + 显式 `["*/*"]`，删 `ACTION_PICK` 相册分支——此前空 MIME 数组让选择器落成「近期的图片」受限视图，无根目录抽屉、无媒体时「无任何文件」）；③ **#152-③ 打开配置文件**（注入层 `SettingsDocumentAction` 接管 + 壳桥 `settingsPath()` + `EngineManager.settingsDocumentPath()`，走系统选择器）；④ **#163 @ 菜单**（引擎树补丁 `reference-drill-F6`：移动形态下目录行点行体进子目录；注入层 `ReferenceMenuEnhancer`：每行勾选框 + 底部「添加」多选，一次触摸只动作一次）；⑤ #152-④ 浏览器访问经确认是上游鉴权收紧，不在范围（issue 说明）。文档：坑 66、RUNTIME-PATCHES §7.1/§8、BRIDGE-API（settingsPath）。**模拟器实测**：#154 老会话 v0→v3 迁移成功、#160 选择器出现根目录抽屉并成功附加 `alpha.txt`、#163 目录行保持菜单打开（下钻签名）、`verify-webview-015` 28/28 | AI 开发助手 |
