// Small, strict ELF header validator shared by the source gate and its fixtures.
export const ABI_ALIASES = Object.freeze({ arm64: 'arm64-v8a', 'arm64-v8a': 'arm64-v8a', x86_64: 'x86_64' })
export const ABI_MACHINES = Object.freeze({ 'arm64-v8a': 183, x86_64: 62 })
export function validateElf(bytes, abi, name = '') {
  const loader32 = name === 'libproot-loader32.so'
  if (!(abi in ABI_MACHINES) || (loader32 && abi !== 'arm64-v8a')) throw new Error('unsupported ELF ABI/loader32: ' + abi)
  const cls = loader32 ? 1 : 2
  const machine = loader32 ? 40 : ABI_MACHINES[abi]
  const size = cls === 1 ? 52 : 64
  if (bytes.length < size) throw new Error('truncated ELF header')
  if (!bytes.subarray(0, 4).equals(Buffer.from([0x7f, 0x45, 0x4c, 0x46]))) throw new Error('invalid ELF magic (possibly an LFS pointer)')
  if (bytes[4] !== cls) throw new Error(`ELF class: expected ELF${cls * 32}, got class ${bytes[4]}`)
  if (bytes[5] !== 1) throw new Error('ELF endianness: expected little-endian')
  if (bytes[6] !== 1 || bytes.readUInt32LE(20) !== 1) throw new Error('invalid ELF version')
  if (bytes.readUInt16LE(18) !== machine) throw new Error(`ELF e_machine: expected ${machine}, got ${bytes.readUInt16LE(18)}`)
  if (bytes.readUInt16LE(cls === 1 ? 40 : 52) !== size) throw new Error('invalid ELF header size')
  if (![2, 3].includes(bytes.readUInt16LE(16))) throw new Error('ELF is not an executable/shared object')
  return { elfClass: cls, machine }
}
