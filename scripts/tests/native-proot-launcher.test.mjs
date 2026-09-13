import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const assetPath = fileURLToPath(new URL('../../app/src/main/assets/native-proot/dsh-debian', import.meta.url));
const source = readFileSync(assetPath, 'utf8');
const android = existsSync('/system/bin/sh');
const system = android ? '/system/bin' : '/bin';
const quote = value => `'${String(value).replaceAll("'", "'\\''")}'`;

// This is a launcher contract test, NOT a real PRoot/ABI execution test. On Android
// writable test payloads cannot be execve'd (the reason for APK native packaging).
// Interpret only the fake payload with system sh there; Linux executes it directly.
// System-tool paths and the sdcard source are mapped to isolated host fixtures.
function fixture(t, { status = 0, guest = false, loader32 = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'native-proot-launcher-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const write = (path, text, mode = 0o700) => {
    mkdirSync(join(path, '..'), { recursive: true });
    writeFileSync(path, text, { mode });
    chmodSync(path, mode);
  };
  const f = {
    dir, write,
    proot: join(dir, "PackageManager APK '$DIR;`false` with spaces/libproot.so"),
    loader: join(dir, "PackageManager APK '$DIR;`false` with spaces/libproot-loader.so"),
    loader32: join(dir, "PackageManager APK '$DIR;`false` with spaces/libproot-loader32.so"),
    state: join(dir, 'native-proot.env'),
    launcher: join(dir, 'dsh-debian'),
    tmp: join(dir, 'tmp/proot-native'),
    rootfs: join(dir, "files/home/.dsh/workspaces/debian-rootfs 'with\nspaces"),
    legacy: join(dir, 'usr/var/lib/proot-distro/containers/debian/rootfs'),
    shared: join(dir, 'optional shared storage'),
    count: join(dir, 'invocations'),
  };
  const fake = guest
    ? `while [ "$#" -gt 0 ] && [ "$1" != /usr/bin/env ]; do shift; done
[ "$#" -gt 0 ] || exit 98
shift
exec ${system}/env "$@"
`
    : `printf '%s\\000' "$@"
printf '\\000NATIVE_ENV\\000'
${system}/env
exit ${status}
`;
  write(f.proot, `#!${system}/sh\nprintf '1\\n' >> ${quote(f.count)}\n${fake}`);
  write(f.loader, 'loader fixture: not executed\n');
  if (loader32) write(f.loader32, 'optional loader fixture: not executed\n');
  write(join(f.rootfs, 'bin/sh'), 'guest shell fixture: not executed\n');
  f.values = {
    DSH_NATIVE_PROOT_STATE_VERSION: '1',
    DSH_PROOT_BIN: f.proot,
    PROOT_LOADER: f.loader,
    DSH_PROOT_LOADER32: loader32 ? f.loader32 : '',
    PROOT_TMP_DIR: f.tmp,
    DSH_DEBIAN_ROOTFS: f.rootfs,
    DSH_DEBIAN_LEGACY_ROOTFS: f.legacy,
  };
  f.saveState = () => write(f.state, Object.entries(f.values).map(([key, value]) => `${key}=${quote(value)}\n`).join(''), 0o600);
  f.saveState();
  let script = source.replaceAll('/system/bin', system).replaceAll('/storage/emulated/0', f.shared);
  // The production bind is one quoted shell word; avoid injecting fixture spaces
  // into the literal bind/check sites while retaining spaces in user/rootfs args.
  script = script.replace(`[ ! -d ${f.shared} ]`, `[ ! -d "${f.shared}" ]`)
    .replace(`set -- -b ${f.shared}:/sdcard`, `set -- -b "${f.shared}:/sdcard"`);
  if (android) {
    assert.equal(script.split('exec "$DSH_PROOT_BIN" "$@"').length, 2);
    script = script.replace('exec "$DSH_PROOT_BIN" "$@"', `exec ${system}/sh "$DSH_PROOT_BIN" "$@"`);
  }
  write(f.launcher, script);
  f.run = (args = [], extraEnv = {}) => {
    const result = spawnSync(`${system}/sh`, [f.launcher, ...args], {
      encoding: 'utf8', timeout: 10_000, maxBuffer: 1024 * 1024,
      env: {
        ...process.env,
        DSH_NATIVE_PROOT_STATE: f.state,
        DSH_NATIVE_PROOT_ERROR: '',
        DSH_NATIVE_PROOT_CLEAN: '1', // Must NOT bypass the clean exec stage.
        DSH_PROOT_BIN: '/inherited-and-wrong/proot',
        PROOT_LOADER: '/inherited-and-wrong/loader',
        PROOT_LOADER_32: '/inherited-and-wrong/loader32',
        PROOT_FORCE_FOREIGN_BINARY: '1',
        DSH_DEBIAN_ROOTFS: '/inherited-and-wrong/rootfs',
        API_KEY_FOR_LAUNCHER_TEST: 'must-never-leak',
        TERMUX_EXEC__EXECVE_CALL__INTERCEPT: '1',
        TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE: 'force',
        TERMUX_EXEC__TEST_UNKNOWN_FUTURE_FLAG: 'must-never-leak',
        LD_PRELOAD: process.env.LD_PRELOAD || '/missing-host-test-preload.so',
        LD_LIBRARY_PATH: '/host/library/path',
        TERM: 'screen-256color',
        ...extraEnv,
      },
    });
    assert.ifError(result.error);
    assert.equal(result.signal, null, result.stderr);
    const marker = '\0NATIVE_ENV\0';
    const separator = result.stdout.lastIndexOf(marker);
    return {
      ...result,
      argv: separator < 0 ? [] : result.stdout.slice(0, separator).split('\0').slice(0, -1),
      nativeEnv: separator < 0 ? {} : Object.fromEntries(result.stdout.slice(separator + marker.length)
        .trim().split('\n').filter(Boolean).map(line => {
          const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)];
        })),
      count: existsSync(f.count) ? readFileSync(f.count, 'utf8').trim().split('\n').length : 0,
    };
  };
  return f;
}

function commandArgs(result) {
  const i = result.argv.indexOf('/usr/bin/env');
  assert.ok(i > 0, result.stdout);
  assert.equal(result.argv[i + 1], '-i');
  let command = i + 2;
  while (command < result.argv.length && /^[A-Z_]+=/.test(result.argv[command])) command++;
  return result.argv.slice(command);
}

function assertLaunched(result) { assert.equal(result.status, 0, result.stderr); assert.equal(result.count, 1); }

test('asset is POSIX shell and documents compatibility not isolation', () => {
  assert.match(source, /^#!\/system\/bin\/sh\n/);
  assert.match(source, /not a security sandbox/i);
  const result = spawnSync(`${system}/sh`, ['-n', assetPath], { encoding: 'utf8' });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stderr);
});

test('default rootfs, required mounts and guest login shell; sdcard is NOT bound', t => {
  const f = fixture(t);
  const result = f.run();
  assertLaunched(result);
  assert.deepEqual(result.argv.slice(0, 11), ['--kill-on-exit', '--link2symlink', '-0', '-r', f.rootfs,
    '-b', '/dev', '-b', '/proc', '-b', '/sys']);
  assert.deepEqual(commandArgs(result), ['/bin/bash', '--login']);
  assert.ok(!result.argv.some(arg => arg.endsWith(':/sdcard')));
  assert.equal(existsSync(f.shared), false);
});

test('argument boundaries survive spaces quotes empty arguments newlines and shell metacharacters', t => {
  const f = fixture(t);
  const command = ['/bin/printf', '%s', '', 'two words', "a'b", '"quoted"', '$HOME;`id`', 'a\nb', '*.txt', '--rootfs'];
  const result = f.run(['--', ...command]);
  assertLaunched(result);
  assert.deepEqual(commandArgs(result), command);
});

test('state paths override inherited native paths and clear host preload plus all Termux hooks', t => {
  const f = fixture(t);
  const result = f.run(['true']);
  assertLaunched(result);
  assert.equal(result.nativeEnv.PROOT_LOADER, f.loader);
  assert.equal(result.nativeEnv.PROOT_TMP_DIR, f.tmp);
  for (const key of ['LD_PRELOAD', 'LD_LIBRARY_PATH', 'PROOT_FORCE_FOREIGN_BINARY', 'PROOT_LOADER_32',
    'TERMUX_EXEC__EXECVE_CALL__INTERCEPT', 'TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE',
    'TERMUX_EXEC__TEST_UNKNOWN_FUTURE_FLAG', 'API_KEY_FOR_LAUNCHER_TEST', 'DSH_NATIVE_PROOT_CLEAN']) {
    assert.equal(result.nativeEnv[key], undefined, key);
  }
  assert.ok(!readFileSync(f.state, 'utf8').includes('must-never-leak'));
});

test('guest env -i actually removes inherited credentials and host state', t => {
  const f = fixture(t, { guest: true });
  const result = f.run([`${system}/env`]);
  assertLaunched(result);
  const env = Object.fromEntries(result.stdout.trim().split('\n').map(line => {
    const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)];
  }));
  assert.deepEqual(env, {
    HOME: '/root', USER: 'root', LOGNAME: 'root', TERM: 'screen-256color', LANG: 'C.UTF-8',
    PATH: '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
  });
});

test('matching optional loader32 is exported, missing one is not promised or inherited', t => {
  const f = fixture(t, { loader32: true });
  const result = f.run(['true']);
  assertLaunched(result);
  assert.equal(result.nativeEnv.PROOT_LOADER_32, f.loader32);
  rmSync(f.loader32);
  const second = f.run(['true']);
  assert.equal(second.status, 0, second.stderr);
  assert.equal(second.nativeEnv.PROOT_LOADER_32, undefined);
});

test('missing state and failed refresh fail closed without executing stale payload', t => {
  const f = fixture(t);
  rmSync(f.state);
  const missing = f.run(['true']);
  assert.equal(missing.status, 126);
  assert.match(missing.stderr, /state is missing/);
  assert.equal(missing.count, 0);
  f.saveState();
  const failed = f.run(['true'], { DSH_NATIVE_PROOT_ERROR: 'IOException' });
  assert.equal(failed.status, 126);
  assert.match(failed.stderr, /stale state will not be used/);
  assert.equal(failed.count, 0);
});

test('temporary directory mode is restricted and symlinks are rejected', t => {
  const f = fixture(t);
  mkdirSync(f.tmp, { recursive: true, mode: 0o777 });
  chmodSync(f.tmp, 0o777);
  assertLaunched(f.run(['true']));
  assert.equal(statSync(f.tmp).mode & 0o777, 0o700);
  rmSync(f.tmp, { recursive: true });
  const other = join(f.dir, 'not-a-temp-target');
  mkdirSync(other, { mode: 0o755 });
  chmodSync(other, 0o755); // Establish the referent mode independently of host umask.
  symlinkSync(other, f.tmp);
  const denied = f.run(['true']);
  assert.equal(denied.status, 126);
  assert.equal(denied.count, 1); // Still only the first invocation.
  assert.match(denied.stderr, /must not be a symlink/);
  assert.equal(statSync(other).mode & 0o777, 0o755);
});

test('relative payload and tmp paths in malformed state are rejected', t => {
  const f = fixture(t);
  for (const key of ['DSH_PROOT_BIN', 'PROOT_LOADER', 'PROOT_TMP_DIR']) {
    const original = f.values[key];
    f.values[key] = 'relative/path';
    f.saveState();
    const result = f.run(['true']);
    assert.equal(result.status, 126);
    assert.equal(result.count, 0);
    assert.match(result.stderr, /absolute/);
    f.values[key] = original;
  }
});

test('outdated state schema is rejected clearly', t => {
  const f = fixture(t);
  delete f.values.DSH_NATIVE_PROOT_STATE_VERSION;
  f.saveState();
  const result = f.run(['true']);
  assert.equal(result.status, 126);
  assert.match(result.stderr, /state is outdated/);
  assert.equal(result.count, 0);
});

for (const missing of ['proot', 'loader']) {
  test(`missing ${missing} payload fails before executing a guest command`, t => {
    const f = fixture(t);
    rmSync(f[missing]);
    const result = f.run(['true']);
    assert.equal(result.status, 126);
    assert.match(result.stderr, /payload is unavailable/);
    assert.equal(result.count, 0);
  });
}

test('missing rootfs has explicit legacy guidance but never auto-falls back or migrates', t => {
  const f = fixture(t);
  rmSync(f.rootfs, { recursive: true });
  f.write(join(f.legacy, 'bin/sh'), 'legacy guest shell\n');
  const sentinel = join(f.legacy, 'user-file');
  f.write(sentinel, 'untouched');
  const missing = f.run(['true'], { DSH_DEBIAN_ROOTFS: f.legacy });
  assert.equal(missing.status, 1);
  assert.equal(missing.count, 0);
  assert.match(missing.stderr, /rootfs not found/);
  assert.ok(missing.stderr.includes(f.legacy));
  assert.match(missing.stderr, /--rootfs PATH/);
  assert.equal(existsSync(f.rootfs), false);
  assert.equal(readFileSync(sentinel, 'utf8'), 'untouched');
  const explicit = f.run(['--rootfs', f.legacy, 'true']);
  assertLaunched(explicit);
  assert.equal(explicit.argv[4], f.legacy);
  assert.equal(readFileSync(sentinel, 'utf8'), 'untouched');
});

test('guest-absolute shell symlink is not mistaken for missing rootfs on the host', t => {
  const f = fixture(t);
  rmSync(join(f.rootfs, 'bin/sh'));
  symlinkSync('/guest-only/usr/bin/dash', join(f.rootfs, 'bin/sh'));
  assertLaunched(f.run(['true']));
});

test('--bind-sdcard requires available source and only binds after explicit opt-in', t => {
  const f = fixture(t);
  const missing = f.run(['--bind-sdcard', 'true']);
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /--bind-sdcard requested/);
  assert.equal(missing.count, 0);
  mkdirSync(f.shared);
  const explicit = f.run(['--bind-sdcard', 'true']);
  assertLaunched(explicit);
  assert.ok(explicit.argv.includes(`${f.shared}:/sdcard`));
});

test('invalid launcher options fail without payload execution; --help works without state', t => {
  const f = fixture(t);
  for (const args of [['--rootfs'], ['--rootfs', 'relative/path'], ['--rootfs', ''], ['--unknown']]) {
    const result = f.run(args);
    assert.equal(result.status, 2, result.stderr);
    assert.equal(result.count, 0);
  }
  rmSync(f.state);
  const help = f.run(['--help']);
  assert.equal(help.status, 0, help.stderr);
  assert.match(help.stdout, /--bind-sdcard/);
  assert.equal(help.count, 0);
});

test('-- terminates launcher option parsing without consuming user command options', t => {
  const f = fixture(t);
  const result = f.run(['--', '--rootfs', 'user-argument', '']);
  assertLaunched(result);
  assert.deepEqual(commandArgs(result), ['--rootfs', 'user-argument', '']);
});

for (const status of [1, 37, 126, 139]) {
  test(`nonzero exit ${status} is returned unchanged and the user command is NEVER rerun`, t => {
    const f = fixture(t, { status });
    const result = f.run(['side-effect-command', 'one invocation']);
    assert.equal(result.status, status, result.stderr);
    assert.equal(result.count, 1);
    assert.deepEqual(commandArgs(result), ['side-effect-command', 'one invocation']);
    assert.ok(!result.argv.some(arg => /qemu/.test(arg)));
  });
}
