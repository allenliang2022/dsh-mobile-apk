# ADB 本地监听兼容 patch（非 APK 更新）

本包只修补一个已确认的兼容问题：已安装的原生启动器传入
`ADB_SERVER_SOCKET=tcp:127.0.0.1:5037`，而随包 Android adb 的服务器监听器拒绝数字 hostname。
ELF 兼容入口将且仅将 `tcp:127.0.0.1:<有效十进制端口>` 改成 hostless `tcp:<端口>`，随后执行保留的原始 adb。
不添加 `-a`，不扩大监听接口，不伪造配对状态，也不绕过原生授权门。

## 精确变更范围

以本应用 `files` 为根：

- **唯一替换的现有文件**：`usr/bin/adb`，替换为当前 ABI 的小型 ELF 兼容入口。
- 新增同目录原件：`usr/bin/adb.dsh-original-v1`，内容和权限与原始 adb 一致。
- 新增私有恢复目录：`home/.dsh/workspaces/runtime-patches/adb-local-listener-v1/`（0700），
  包含 `original-adb`、`receipt.json`、`installer.lock`（0600）。
- 不修改 `settings.yaml`、profiles、模型路由、真实配对状态或既有密钥；不安装 APK、不解压工厂快照、不重启 DSH、不操作屏幕。
- 原始 adb 仍按正常、明确触发的配对/连接流程使用自己的密钥；兼容入口和应用工具本身不读取这些密钥。

这不是对已安装 Kotlin/Dex 代码的改写，而是启动入口兼容层。它**不修复原 APK 中独立存在的自动迁移/回滚逻辑**，也不承诺其它插件或并发操作不会写文件。

## 应用前必须验证

1. 仅使用来源和归档 SHA-256 已核对、CI 通过的包；`manifest.json` 本身不是签名。
2. 将核验后的包放在应用私有、当前 UID 拥有、不可被其它 UID 写入的目录。
   不要直接在共享存储里执行来源未经核实的脚本。
3. 先完成临时副本上的真实 ELF/linker64 启动、参数/输入输出/退出码和应用/撤销验证。
4. `preflight` 必须通过：当前 adb 必须是清单中已知原件，类型/哈希/ABI/权限/路径均匹配；遇到未知、自定义或后来更新的文件，一律停止，不能 force。
5. 真正应用前确认变更清单。应用/撤销期间不要并发替换同一个 adb 或更新整个运行时。

## 命令

以下 `PATCH_DIR` 应为已经核验并放入私有目录的补丁包。

```sh
python3 -B "$PATCH_DIR/apply_patch.py" status
python3 -B "$PATCH_DIR/apply_patch.py" preflight --manifest "$PATCH_DIR/manifest.json"
# 只有确认后才执行：
python3 -B "$PATCH_DIR/apply_patch.py" apply --manifest "$PATCH_DIR/manifest.json"
# 定点撤销，不依赖原始补丁包仍然存在：
python3 -B "$PATCH_DIR/apply_patch.py" rollback
```

`--app-files` 用于显式指定应用目录或隔离测试夹具；默认是本应用目录。
`--abi arm64-v8a|x86_64` 可显式选择，默认从当前 adb ELF 检测。
`status`、`preflight` 不创建目录/锁/文件，不执行 adb。

## 恢复与失败语义

- 替换前保存完整原件，并先写 `prepared` 收据；备份和新入口落盘后才原子替换。
- 崩溃可从 prepared/rollback-prepared 继续判定，不把 shim 误存为原件。
- 撤销只在当前入口仍与已应用 shim 的哈希及权限一致、原件备份校验通过时执行；
  若用户或包管理器后来更新了 adb，拒绝覆盖该新版本。
- 撤销后保留私有原件、收据和锁，便于审计/恢复；只删除校验通过的同目录备用入口，不递归清空目录。
- 合作式锁和提交前复核不构成防恶意同 UID 重命名竞态的安全边界。
- 强制杀进程可能留下随机 stage 文件；工具不会通过通配符清理它们，以免误删其它内容。

## 运行与验证限制

- 原生入口通过 `dladdr` 定位自身 ELF，不把 `/proc/self/exe` 中的 linker 路径当作自身位置。
- 原件必须是同目录的普通文件；缺失、符号链接、自引用或变化时失败关闭。
- 一次 `execve` 转交，不另起后台进程，保持调用进程与标准输入输出，透明转交 `argv[1..]`、非目标环境和原程序退出结果。
- **argv[0] 会成为保留原件路径**：Android linker64 不提供这里可用的 argv0 覆盖接口，不声称完全保留它。
- NDK API26 是编译目标，不代表旧版 Android 的 linker 支持这种运行方式；实际支持范围以现代 ROM 的隔离验证为准，未验证的系统不要直接应用。
- 后续工厂运行时更新可能移除该兼容层；工具不会自动覆盖新运行时重装 patch。正式 APK 应合入原生修复。

## 测试入口

- `native/tests/run-host-tests.sh`：C 端口规则、透传、退出码及失败边界；仅合成原件。
- `python3 -B -m unittest discover -s runtime-patches/adb-local-listener -p test_apply_patch.py -v`：合成目录中的应用、崩溃恢复、撤销、漂移和无关文件保留。
- `tests/android`：独立 CI 测试应用（不是 DSH 更新包），验证显式 linker 加载、错误监听参数下的真实 adb 启动、透传及缺失/符号链接原件拒绝。
- 真实手机配对成功是另一项验收；隔离启动或 CI 通过不是配对成功凭据。
