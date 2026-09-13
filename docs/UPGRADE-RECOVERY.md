# Snapshot upgrade preservation and recovery contract

These changes are a development-branch contract. Passing source/JVM tests does not mean an installed application has this protection; verify the exact APK before relying on it.

## Preserve before cleaning

A runtime snapshot replaces `files/usr`, but that tree may contain local launchers, tools, package databases, and Linux root filesystems that are not part of the factory image. Treating it as disposable factory content is unsafe.

`SnapshotTransaction.finish` now moves the complete displaced tree into:

```text
files/home/.dsh/workspaces/upgrade-recovery/runtime-<unique>/previous/
```

The payload includes the previous `usr` and applicable profile backup. Recovery root/slot permissions are 0700; `receipt.txt` is 0600 and contains only fixed format metadata and creation time. Payload presence distinguishes a completed rename from an allocated but incomplete slot. Old slots are never automatically pruned, restored, or executed. A stock APK is not a backup of a customized installed `usr`.

Retention uses a same-filesystem move without copy/delete fallback. Lack of space, collision, unsafe directory boundaries or unresolved old transactions stop progression without discarding the original. Storage cleanup requires an explicit, separately reviewed user action; update automation must not delete recovery sources to make room.

## Transaction invariants

- `STAGED`: no live activation; unrecovered previous data cannot be silently discarded.
- `SWAPPING`: journal before replacement; failure restores displaced originals.
- `SWAPPED`: activation finished; commit/retention proceeds forward.
- `RETAINING`: previous may already be in its recovery slot. Rolling back from this state could delete live files and is prohibited.
- `ROLLED_BACK`: originals have been restored; cleanup can be retried without deleting them again.

The engine catch path distinguishes activated/retaining failures from failed swaps. Unreadable/unsafe markers remain in place. An unresolved marker blocks engine startup instead of launching a possibly mixed runtime.

Profile backup is copied completely before publication. Symlinks are retained as links rather than silently dropped, and merges must not traverse live links. Private retention directory ancestors are checked with NOFOLLOW; this is not a claim of protection against malicious concurrent same-UID filesystem renames.

## Linux entrypoint compatibility

The maintained `dsh-debian` defaults to the preserved `home/.dsh/workspaces/debian-rootfs`, with shared storage exposed only on `--bind-sdcard`.

`NativeProot.prepareFiles` also maintains the historical `debian` entrance:

- A private, stable wrapper delegates to the maintained launcher with `--bind-sdcard -- "$@"`, preserving the old command and argument contract.
- A missing `usr/bin/debian` is claimed atomically by a managed symlink.
- Unknown existing files, directories and links are preserved without reading/executing them.
- Exact managed links can repair their wrapper; optional alias failure does not disable the maintained launcher.
- No credential or APK installation-directory literal is embedded.

Retaining an old rootfs does not automatically select or migrate it. The actual entrypoint, rootfs, HOME, mounts, authentication mechanism and working directory must be checked independently.

## Upgrade acceptance checklist

Before installation:

1. Inventory the complete live user-modified runtime, not only the factory package list.
2. Identify all changed ownership domains: APK native code, `usr`, profiles, launchers, guest filesystems, user home, package state and credentials.
3. Preserve a complete recoverable source and validate it by readback. Record backup time and recheck drift immediately before the destructive boundary.
4. Record the exact OLD workflows: entrypoint, argv, HOME/PATH, mount visibility, tool execution, non-mutating authenticated request and relevant project access.
5. Keep a known-good APK and private rollback sources; do not treat configuration snapshots as whole-runtime backups.

After installation:

1. Verify APK identity, live snapshot fingerprint and transaction completion.
2. Repeat the same old workflows before testing new features.
3. Compare files, links and meaningful metadata in the correct filesystem namespace. Host `is_file` on a guest absolute symlink is not proof of loss.
4. Distinguish command presence, exit success, authentication and actual task success.
5. Report unverified or backup-external history explicitly; never claim complete recovery from partial evidence.

Recovery is selective: preserve new settings, models, credentials and intentionally removed features. Never bulk-copy an old profile or invent authentication files because a command is missing.

## Executable evidence

- `NativeProotTest`: missing entrance, recreation after usr replacement, idempotence, foreign-entry preservation, managed repair, stable quoting and original shared-storage/argv contract.
- `SnapshotRetentionTest`: full displaced payload preservation, repeated updates, collisions, failures/retries, rollback witnesses, marker boundaries and symlink safety.
- `NativeProotUpgradeTest`: real transaction calls preserve workspace data and retain legacy-rootfs bytes/link targets/file identity without automatic migration.
- `W3ShellContractTest`: activated/retaining failures precede rollback; unresolved marker guard precedes startup.
- The isolated Android upgrade test must install a baseline APK, seed private custom-runtime/workspace fixtures, install the new APK, and inspect the actual retained data. Compilation or a fresh-install smoke test alone cannot substitute for this test.
