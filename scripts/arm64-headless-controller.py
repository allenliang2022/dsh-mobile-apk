#!/usr/bin/env python3
"""CI-only headless ARM64 probe: wait for services, never inject UI keys.

A helper pass is not full Linux guest acceptance. No physical devices, paid
runner assumptions, screenshots or full system log uploads are used.
"""
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys
import tempfile
import time

if os.environ.get('GITHUB_ACTIONS') != 'true' or platform.system() != 'Darwin' or platform.machine() != 'arm64':
    raise SystemExit('Requires an isolated standard ARM64 macOS CI runner')
SDK = Path(os.environ['ANDROID_HOME'])
ADB = str(SDK / 'platform-tools/adb')
EMU = str(SDK / 'emulator/emulator')
SERIAL = 'emulator-5554'
OUT = Path('arm64-probe-reports')
OUT.mkdir(exist_ok=True)
report = {'scope': 'headless-arm64-system-feasibility', 'nativeGuestAcceptance': False,
          'bootCompleted': False, 'servicesReady': False, 'helperVerified': False,
          'image': 'system-images;android-30;default;arm64-v8a'}
phase = 'setup'
emulator = None
emulator_log = None


def command(argv, *, timeout=60, input_text=None, required=True):
    with tempfile.TemporaryFile() as log:
        p = subprocess.run(argv, input=input_text, text=True, stdout=log,
                           stderr=subprocess.STDOUT, timeout=timeout)
        log.seek(0, 2)
        size = log.tell()
        if size > 1024 * 1024:
            raise RuntimeError('Command diagnostics exceed 1MiB')
        log.seek(0)
        text = log.read(1024 * 1024).decode('utf-8', 'replace')
    if required and p.returncode:
        raise RuntimeError('Command failed: ' + Path(argv[0]).name + '\n' + text[-4000:])
    return p.returncode, text.strip()


def adb(*argv, timeout=30, required=False):
    return command([ADB, '-s', SERIAL, *argv], timeout=timeout, required=required)


try:
    command(['sdkmanager', report['image']], timeout=600)
    command(['avdmanager', 'create', 'avd', '--force', '--name', 'proot-arm64-headless',
             '--package', report['image']], timeout=120, input_text='no\n')
    command([ADB, 'start-server'], timeout=30)
    if adb('get-state')[0] == 0:
        raise RuntimeError('Expected a fresh CI emulator port')
    phase = 'boot-and-service-readiness'
    emulator_log = tempfile.TemporaryFile()
    emulator = subprocess.Popen([EMU, '-avd', 'proot-arm64-headless', '-port', '5554',
        '-no-window', '-no-snapshot', '-gpu', 'swiftshader_indirect', '-noaudio',
        '-no-boot-anim', '-camera-back', 'none', '-accel', 'off', '-feature', '-HVF',
        '-memory', '2048', '-cores', '2', '-skin', '360x640', '-verbose'], stdout=emulator_log,
        stderr=subprocess.STDOUT, start_new_session=True)
    start = time.monotonic()
    deadline = start + 1200  # Real wall-clock limit, including slow ADB calls.
    consecutive = 0
    last_progress = -60
    def remaining_budget():
        seconds = deadline - time.monotonic()
        if seconds <= 0:
            raise TimeoutError('Wall-clock readiness budget expired')
        return min(20, seconds)
    while time.monotonic() < deadline:
        if emulator.poll() is not None:
            raise RuntimeError('Emulator exited before service readiness')
        try:
            code, boot = adb('shell', 'getprop', 'sys.boot_completed', timeout=remaining_budget())
            report['bootCompleted'] = code == 0 and boot == '1'
            ready = False
            if report['bootCompleted']:
                checks = [adb('shell', 'service', 'check', service, timeout=remaining_budget())
                          for service in ('package', 'activity')]
                path_code, package_path = adb('shell', 'cmd', 'package', 'path', 'android', timeout=remaining_budget())
                ready = all(code == 0 and text.endswith('found') and 'not found' not in text
                            for code, text in checks) and path_code == 0 and 'package:' in package_path
            consecutive = consecutive + 1 if ready else 0
            if consecutive >= 2:
                report['servicesReady'] = True
                break
        except subprocess.TimeoutExpired:
            consecutive = 0
        elapsed = int(time.monotonic() - start)
        if elapsed - last_progress >= 60:
            print(json.dumps({'phase': phase, 'elapsedSeconds': elapsed,
                              'bootCompleted': report['bootCompleted'], 'stableServiceChecks': consecutive}), flush=True)
            last_progress = elapsed
        time.sleep(min(5, max(0, deadline - time.monotonic())))
    report['readinessSeconds'] = round(time.monotonic() - start, 2)
    if not report['servicesReady']:
        raise RuntimeError('PackageManager and ActivityManager did not become stable within the wall-clock budget')
    phase = 'apk-helper-instrumentation'
    code, output = command([sys.executable, 'scripts/native-proot-emulator-smoke.py',
        '--helper-only', '--expected-abi', 'arm64-v8a', '--serial', SERIAL,
        '--new-apk', 'arm64-probe-input/app/app-debug.apk',
        '--test-apk', 'arm64-probe-input/tests/app-debug-androidTest.apk',
        '--out', str(OUT)], timeout=900)
    print(output)
    helper = json.loads((OUT / 'summary.json').read_text())
    if helper.get('status') != 'passed' or not helper.get('arm64ApkHelperVerified'):
        raise RuntimeError('Missing ARM64 source APK helper evidence')
    report['helperVerified'] = True
    report['status'] = 'passed'
except Exception as exc:
    report['status'] = 'failed'
    report['failedPhase'] = phase
    report['reason'] = str(exc)
    raise
finally:
    if emulator is not None:
        try:
            adb('emu', 'kill', timeout=10)
        except (subprocess.TimeoutExpired, OSError):
            pass
        if emulator.poll() is None:
            try:
                os.killpg(emulator.pid, signal.SIGTERM)
                emulator.wait(timeout=15)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                if emulator.poll() is None:
                    os.killpg(emulator.pid, signal.SIGKILL)
                    emulator.wait(timeout=10)
    if emulator_log is not None:
        emulator_log.seek(0)
        selected = []
        scanned = 0
        for raw in iter(lambda: emulator_log.readline(32769), b''):
            scanned += len(raw)
            if scanned > 2 * 1024 * 1024:
                break
            if len(raw) > 32768:
                continue
            line = raw.decode('utf-8', 'replace').strip()
            if "Feature 'HVF'" in line or 'CPU Accel' in line or 'PANIC' in line or 'FATAL' in line:
                selected.append(line[:1000])
            if len(selected) >= 30:
                break
        (OUT / 'emulator-selected.log').write_text('\n'.join(selected) + '\n')
        emulator_log.close()
    (OUT / 'headless-summary.json').write_text(json.dumps(report, indent=2) + '\n')
