// Verify an independent rebuild without substituting it for the pinned payload.
import {readFileSync, writeFileSync} from 'node:fs';
import {resolve, dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {validateElf} from '../../scripts/lib/native-proot-elf.mjs';

const root=resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const [buildDir, reportPath]=process.argv.slice(2);
if(!buildDir||!reportPath)throw new Error('Usage: node verify-rebuild.mjs BUILD_DIR REPORT_JSON');
const metadata=JSON.parse(readFileSync(join(root,'scripts/native-proot.json'),'utf8')).artifacts['arm64-v8a'];
const reports={};
for(const name of ['libproot.so','libproot-loader.so','libproot-loader32.so']){
  const bytes=readFileSync(join(buildDir,name));
  const header=validateElf(bytes,'arm64-v8a',name);
  const is64=header.elfClass===2;
  const number64=(at)=>{
    if(at<0||at+8>bytes.length)throw new Error(name+': truncated ELF field');
    const v=bytes.readBigUInt64LE(at);
    if(v>BigInt(Number.MAX_SAFE_INTEGER))throw new Error(name+': oversized ELF field');
    return Number(v);
  };
  const phoff=is64?number64(32):bytes.readUInt32LE(28);
  const phentsize=bytes.readUInt16LE(is64?54:42);
  const phnum=bytes.readUInt16LE(is64?56:44);
  if(phentsize!==(is64?56:32)||phnum<1||phnum>128||phoff+phentsize*phnum>bytes.length)
    throw new Error(name+': invalid program header table');
  const aligns=[];
  let interpreter=null;
  for(let i=0;i<phnum;i++){
    const at=phoff+i*phentsize;
    const type=bytes.readUInt32LE(at);
    const offset=is64?number64(at+8):bytes.readUInt32LE(at+4);
    const size=is64?number64(at+32):bytes.readUInt32LE(at+16);
    if(offset+size>bytes.length)throw new Error(name+': segment beyond file');
    if(type===1){
      const alignment=is64?number64(at+48):bytes.readUInt32LE(at+28);
      if(alignment<(is64?16384:4096))throw new Error(name+': insufficient PT_LOAD alignment');
      const a=BigInt(alignment);
      if((a & (a-1n))!==0n)throw new Error(name+': PT_LOAD alignment is not a power of two');
      const address=is64?number64(at+16):bytes.readUInt32LE(at+8);
      if(offset%alignment!==address%alignment)throw new Error(name+': misaligned PT_LOAD file/address offsets');
      aligns.push(alignment);
    }
    if(type===3){
      if(interpreter!==null||size<2||size>256||bytes[offset+size-1]!==0)throw new Error(name+': invalid interpreter');
      interpreter=bytes.subarray(offset,offset+size-1).toString('utf8');
    }
  }
  if(!aligns.length)throw new Error(name+': no load segments');
  const expectedInterpreter=name==='libproot.so'?'/system/bin/linker64':null;
  if(interpreter!==expectedInterpreter)throw new Error(name+': wrong native interpreter');
  const sha256=createHash('sha256').update(bytes).digest('hex');
  reports[name]={...header,sha256,interpreter,loadAlignments:aligns,
    byteIdenticalToPinnedArtifact:sha256===metadata.files[name]};
}
const report={scope:'rebuilt-elf-structure',sourceCommit:metadata.sourceCommit,
  sourceArchiveSha256:metadata.sourceArchiveSha256,elfStructureVerified:true,
  sourceBuildMustBeEstablishedByCI:true,nativeExecutionVerified:false,
  allBytesReproduced:Object.values(reports).every(x=>x.byteIdenticalToPinnedArtifact),files:reports};
writeFileSync(reportPath,JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(report,null,2));
