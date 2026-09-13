#!/usr/bin/env python3
"""Guard/argument tests only; these do not claim Android acceptance."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER=Path(__file__).resolve().parents[1]/"native-proot-emulator-smoke.py"

class EmulatorGuardTest(unittest.TestCase):
    def invoke(self, serial, root, env=None):
        old=root/"old.apk"; old.write_bytes(b"fixture-not-a-real-apk")
        return subprocess.run([sys.executable,str(RUNNER),"--serial",serial,
            "--old-apk",str(old),"--old-sha256",hashlib.sha256(old.read_bytes()).hexdigest(),
            "--new-apk",str(old),"--test-apk",str(old),"--snapshot-sha256","a"*64,
            "--out",str(root/"reports")],capture_output=True,text=True,env=env,timeout=10)

    def test_physical_or_network_serial_is_rejected_before_adb(self):
        for serial in ["physical-phone", "127.0.0.1:5555", "emulator-", "emulator-5554;echo x"]:
            with self.subTest(serial=serial), tempfile.TemporaryDirectory() as tmp:
                root=Path(tmp)
                r=self.invoke(serial,root)
                self.assertNotEqual(0,r.returncode)
                self.assertIn("Refusing non-emulator ADB serial",r.stderr)
                self.assertFalse((root/"reports").exists())

    def test_qemu_identity_failure_never_installs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp); fake=root/"adb"
            calls=root/"calls.txt"
            fake.write_text("#!"+sys.executable+"\nimport sys,os\n"
                "with open(os.environ['GUARD_CALLS'],'a') as f:f.write(' '.join(sys.argv[1:])+'\\n')\n"
                "print({'ro.kernel.qemu':'0','ro.hardware':'not-emulator','ro.product.cpu.abi':'x86_64'}.get(sys.argv[-1],''))\n")
            fake.chmod(0o755)
            env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ.get("PATH",""),GUARD_CALLS=str(calls))
            r=self.invoke("emulator-5554",root,env)
            self.assertNotEqual(0,r.returncode)
            self.assertIn("Target is not an isolated x86_64 Android emulator",r.stderr)
            self.assertNotIn("install",calls.read_text())
            self.assertNotIn("appops",calls.read_text())
            self.assertNotIn("run-as",calls.read_text())

    def test_helper_only_never_claims_upgrade_or_native_guest_acceptance(self):
        for abi in ["x86_64", "arm64-v8a"]:
            with self.subTest(abi=abi), tempfile.TemporaryDirectory() as tmp:
                self.check_helper_scope(Path(tmp), abi)

    def check_helper_scope(self, root, abi):
        fake=root/"adb"; calls=root/"calls.txt"
        apk=root/"test-fixture.apk"; apk.write_bytes(b"not-an-APK")
        fake.write_text("#!"+sys.executable+"\nimport sys,os\n"
            "with open(os.environ['GUARD_CALLS'],'a') as f:f.write(' '.join(sys.argv[1:])+'\\n')\n"
            "values={'ro.kernel.qemu':'1','ro.hardware':'ranchu','ro.product.cpu.abi':os.environ['GUARD_ABI'],'ro.build.version.sdk':'30','-m':'aarch64' if os.environ['GUARD_ABI']=='arm64-v8a' else 'x86_64'}\n"
            "if 'instrument' in sys.argv:print('OK (7 tests)')\n"
            "else:print(values.get(sys.argv[-1],''))\n")
        fake.chmod(0o755)
        env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ.get("PATH",""),GUARD_CALLS=str(calls),GUARD_ABI=abi)
        command=[sys.executable,str(RUNNER),"--helper-only","--expected-abi",abi,"--serial","emulator-5554",
            "--new-apk",str(apk),"--test-apk",str(apk),"--out",str(root/"reports")]
        result=subprocess.run(command,env=env,capture_output=True,text=True,timeout=10)
        self.assertEqual(0,result.returncode,result.stderr)
        summary=json.loads((root/"reports/summary.json").read_text())
        self.assertEqual("isolated-"+abi+"-helper-boundary",summary["scope"])
        self.assertFalse(summary["upgradeRequested"])
        self.assertFalse(summary["upgradeAcceptance"])
        self.assertFalse(summary["nativeArm64Acceptance"])
        self.assertEqual(abi=="arm64-v8a",summary["arm64ApkHelperVerified"])
        text=calls.read_text()
        self.assertNotIn("appops",text)
        self.assertNotIn("am start",text)
        self.assertNotIn("run-as",text)
        self.assertNotIn("NativeProotHostAcceptanceTest",text)
        calls.write_text("")
        repeated=subprocess.run(command,env=env,capture_output=True,text=True,timeout=10)
        self.assertNotEqual(0,repeated.returncode)
        self.assertIn("Refusing to reuse an existing acceptance summary",repeated.stderr)
        self.assertEqual("",calls.read_text())
        command[-1]=str(root/"wrong-abi-report")
        other="x86_64" if abi=="arm64-v8a" else "arm64-v8a"
        command[command.index("--expected-abi")+1]=other
        rejected=subprocess.run(command,env=env,capture_output=True,text=True,timeout=10)
        self.assertNotEqual(0,rejected.returncode)
        self.assertIn("Target is not an isolated "+other,rejected.stderr)
        self.assertNotIn("install",calls.read_text())

if __name__ == "__main__":
    unittest.main()
