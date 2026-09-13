# ADB 无线调试链路与修复验收

本规范对应无线调试修复分支和 bridge 0.2.5，尚未表示正式发布或真实设备配对验收。旧历史记录中的“配对即无线调试开启”“protocol fault 已自动重建”“回收必须删共享密钥”“猜测 5555”不再描述当前实现。

## 状态所有权与授权

- 原生 `AdbState` 是允许访问、真实配对及本机端点的唯一写入者。所有文件权限仍来自系统，自动审批不参与授权。
- 只有成功的真实配对协议应答且进程正常退出，才能写入 `paired=true`。最终写入与允许开关、撤销共享授权锁和 revision，避免撤销期间的迟到成功恢复权限。`setPaired` 仅保留清除入口。
- `startStatusMonitor(applicationContext)` 启动单一后台生产者，约每 2 秒更新 `wirelessKnown`、`wirelessOn`、`wirelessObservedAt`、`endpointReachable`。`MainActivity.onResume` 启动它；不依赖设置页 HTTP 轮询触发。
- 已知的系统 `adb_wifi_enabled` 提供开关事实；无法读取时，已验证本机连接端点的可达性只能提供正向证据。没有端口或 TCP 失败是 **unknown**，不能伪装为系统关闭。
- `stateJson` 是非阻塞快照，输出 `wirelessDebugState=on|off|unknown` 和独立 `paired`。插件拒绝缺失或过期的观测用于 ADB 能力授权；页面显示未知而不是“未开启”。
- 部署默认档位不等于当前会话档位；设备工具仍按实际会话 `sandboxPolicy.resolve({session})` 判断。无障碍通道保持独立，不需要人为置位 ADB 授权。

## 发现和端点

`AdbEndpointPolicy`、`AdbDiscoveryResults`、`AdbDiscoveryCache` 是可用普通 JVM 测试的策略；`AdbPortDiscovery` 仅适配 Android NSD。

1. 配对和连接属性各自读取、校验 1..65535；每种缺失类型独立进入 NSD。
2. 两种服务各自拥有一个 `DiscoveryListener`，一条有界解析队列避免并发 resolve 冲突。完成按服务类型计数，不按任意回调次数计数。
3. 返回带角色的 `pair`、`connect`、`pairHost`、`connectHost`、`endpoints`，并保留有限阶段诊断。前端不从候选数组下标猜角色，不用配对端口填连接框，不因缺一个类型就谎报全无端口。
4. NSD 只接受当前本机 IP；忽略远程主机、非法端口、失效或迟到结果。拒绝 DNS 名称与 shell 元字符，支持本机 IPv4、IPv6 和正确的 link-local scope。
5. 显式扫描永远强制新发现；只有后台预取可短暂复用正结果，负结果不缓存。串行扫描避免旧预取覆盖新结果。
6. 用户指定的本机 host 与两个独立端口一同进入配对。成功后保存准确的 `connectHost`、`connectPort`；不再尝试猜测的 5555。
7. 原生与插件每次实际执行前重新验证端点仍属于本机，防止 Wi-Fi 切换后向旧地址所属的其他设备发送命令。

## 非阻塞桥协议

新增方法均返回 JSON 字符串，旧桥方法保留兼容：

- `startAdbDiscovery()` → `{ok:true,requestId}`；始终新扫描。
- `startAdbPair(code,pairPort,connectPort,host)` → `{ok:true,requestId}`。
- `getAdbOperation(requestId)` → `{ok:true,state:"running"|"complete",kind:"discovery"|"pair",result?}`。
- 不可启动、繁忙、不存在或过期 → `{ok:false,message}`。

`AdbOperationRegistry` 只运行一个发现/配对任务，拒绝重复排队，不存任务参数或配对码。最多保留 8 个已完成结果、5 分钟；Activity 重建不会丢失进程内票据。票据不是配对成功凭据。页面有界轮询并忽略旧 generation 的结果；轮询超时/结果过期不表示原生任务取消，不能自动重提。旧 APK 仅可使用原有回环地址接口，明确提示同步调用限制，不能悄悄丢掉新 host 参数。

## 服务器、进程和错误

`AdbServerLifecycle` 用一把锁管理所有权、就绪验证、命令和恢复。每次操作验证本地服务器，而不是永久相信 `serverReady`。

- 本地 ADB **服务器**固定在 `127.0.0.1:5037`，它与目标 adbd 的 host/port 是不同层。
- 仅可重启自身持有的进程；等待退出与 socket 释放，有界失败返回恢复状态。不终止无法确认所有权的共享监听器，不使用全局 `kill-server`。
- `devices` 往返仅证明服务器响应，不证明配对服务或连接设备健康。配对/连接分别需要正常退出、无超时和对应正向协议应答。
- 协议故障不自动等同于本地竞态；连接拒绝不证明配对窗口关闭；超时、认证失败、服务器未就绪分别报告。
- 配对码在原始文本进入审计或 UI 之前替换；不记录完整 argv。结果携带 phase、recovery、exitCode 和 timeout，而不是靠几个英文错误子串判断成功。
- 真实 shell 命令不自动重试，避免远端副作用重复。
- 回收仅清除本应用授权与准确端点，并断开该端点；不删除共享 ADB 密钥。若要撤销系统侧信任，使用系统无线调试的已配对设备管理。

## 回归与发布边界

- 插件：在 `plugins/dsh-android-bridge` 执行 `npm ci --include=dev`、`npm run typecheck`、`npm test`。Android 宿主的 node-worker/exec shim 若阻断默认 runner，可用 `node --test --test-isolation=none test/*.test.mjs` 单独验证断言，并如实记录与标准命令的区别。
- 原生：`./gradlew :app:testDebugUnitTest`；新增 endpoint/discovery/process/operation-registry 测试，更新调用点契约。纯 Kotlin/JVM 通过不等于 Android adapter 已验证。
- `AdbBridgeInstrumentedTest` 仅在一次性 ranchu/goldfish 模拟器运行，验证真实 WebView-Java 异步票据与 NSD 监听器契约；不执行生产 ADB 命令，不创建配对授权，也不声称真实手机配对通过。
- `.github/workflows/adb-wireless-validation.yml` 构建双 ABI 开发 APK、运行测试和隔离 Android 35 仪器检查，不发布 Release。
- `scripts/adb-bridge-snapshot.py` 只把本次测试后的 bridge 包注入 SHA-256 固定的公开工厂快照，双 profile 逐字节校验包成员，保留其他配置/插件/符号链接。`scripts/tests/adb-bridge-snapshot.test.py` 包含错误输入、缺 profile、旧模块修剪和非目标不变性负控。
- 真实设备验收仍需：版本/包内容核对 → 开关状态变化 → 仅连接端口/打开配对窗口后的发现 → 真配对与指定端点连接 → 重启/切网/撤销后的收敛。每项要有独立结果，不能用另一层的成功替代。

## 防复发规则

1. 观测未知、系统开关、真实配对、TCP 可达、协议连接和命令成功必须分层。
2. 端点始终携带角色、主机、端口、来源和新鲜度；禁止猜端口和丢弃设备身份。
3. 复用异步 API 前核对监听器所有权、取消、重复回调、迟到结果和失败清理。
4. 恢复动作必须有执行回执；“尝试恢复”不能显示“已经重建”。
5. 同版本号不能证明已部署字节一致；构建包、快照和 APK 必须闭环校验。
