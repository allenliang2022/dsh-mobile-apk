#!/usr/bin/env python3
"""Guard/argument tests only; these do not claim Android acceptance."""
import hashlib
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

if __name__ == "__main__":
    unittest.main()
