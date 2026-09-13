# Native PRoot corresponding source

The ARM64 executable/loader bytes are copied without modification from
[DroidRunner v1.0.0](https://github.com/m96-chan/DroidRunner/releases/tag/v1.0.0),
commit `28bec00176724fd132f21bf0f21cd708d4811c57`. Artifact SHA-256 values,
archive hashes, Android API and the scope of testing are in `scripts/native-proot.json`.

The accompanying `droidrunner-v1.0.0-source.tgz` contains Termux PRoot at
`7266fb3e8516535682f5a9c8f3a7e70f6506eddb`, talloc 2.4.3 source, the original build
script and `string-header.patch`. We additionally supply `talloc-answers.txt`
from that exact DroidRunner commit: the original source archive omitted it although
its build script requires it. `rebuild-arm64.sh` uses these local sources without
fetching moving upstream branches. Requires a Linux/macOS NDK r29 toolchain,
Python 3, make, patch, tar and SHA-256 tools. The offline recipe has compiled
successfully in CI. The resulting files passed ELF/ABI/interpreter/alignment checks
but were not byte-identical to the supplied donor binaries; no replacement of the
shipped payload or native runtime acceptance follows from that build result.

Example (run from this directory):

```sh
ANDROID_NDK_ROOT=/path/to/ndk bash rebuild-arm64.sh /tmp/proot-new-build
```

PRoot source headers permit GPL-2.0-or-later; talloc is statically linked and its
source headers permit LGPL-3.0-or-later. The combined executable is distributed
under GPL-3.0-or-later. See COPYING.proot, GPL-3.0.txt and LGPL-3.0.txt; original
source copyright notices remain in the archive. The DroidRunner build-script
adaptation keeps its GPL licensing; it cites the MIT-licensed Godot GABE recipe.
The DSH shell invokes PRoot as a separate process, not as a JNI-linked library.

This directory is included under APK assets/native-proot-source/ so recipients
receive the source, licence texts and rebuild inputs alongside the binary. It must
also be retained when redistributing the patch. No Debian rootfs, user data, QEMU
or GitHub credential is included. x86_64 has no native PRoot payload in this change.
