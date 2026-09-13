// Structural verifier fixtures, not independent compilation or native execution.
import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,copyFileSync,readFileSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {resolve,dirname,join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const here=dirname(fileURLToPath(import.meta.url));
const root=resolve(here,'../..');
function fixture(fn){
 const dir=mkdtempSync(join(tmpdir(),'native-elf-check-'));
 try{
  for(const f of ['libproot.so','libproot-loader.so','libproot-loader32.so'])
   copyFileSync(join(root,'app/src/main/jniLibs/arm64-v8a',f),join(dir,f));
  const run=()=>spawnSync(process.env.NODE||'node',[join(here,'verify-rebuild.mjs'),dir,join(dir,'report.json')],{encoding:'utf8',timeout:10000,maxBuffer:128*1024});
  fn(dir,run);
 }finally{rmSync(dir,{recursive:true,force:true});}
}
test('pinned-byte control validates structure but does not claim compilation or execution',()=>fixture((dir,run)=>{
 const r=run();assert.equal(r.status,0,r.stderr);
 const report=JSON.parse(readFileSync(join(dir,'report.json'),'utf8'));
 assert.equal(report.elfStructureVerified,true);
 assert.equal(report.sourceBuildMustBeEstablishedByCI,true);
 assert.equal(report.nativeExecutionVerified,false);
 assert.equal(report.allBytesReproduced,true);
 assert.equal(report.files['libproot-loader32.so'].elfClass,1);
}));
test('wrong ELF machine rejected',()=>fixture((dir,run)=>{
 const p=join(dir,'libproot.so');const b=readFileSync(p);b.writeUInt16LE(62,18);writeFileSync(p,b);
 const r=run();assert.notEqual(r.status,0);assert.match(r.stderr,/e_machine/);
}));
test('wrong Bionic interpreter rejected',()=>fixture((dir,run)=>{
 const p=join(dir,'libproot.so');const b=readFileSync(p);const i=b.indexOf(Buffer.from('/system/bin/linker64'));
 assert.ok(i>=0);b[i+1]='x'.charCodeAt(0);writeFileSync(p,b);
 const r=run();assert.notEqual(r.status,0);assert.match(r.stderr,/wrong native interpreter/);
}));
test('insufficient ARM64 load alignment rejected',()=>fixture((dir,run)=>{
 const p=join(dir,'libproot.so');const b=readFileSync(p);const off=Number(b.readBigUInt64LE(32));const size=b.readUInt16LE(54);const n=b.readUInt16LE(56);
 let changed=false;
 for(let i=0;i<n;i++){const at=off+i*size;if(b.readUInt32LE(at)===1){b.writeBigUInt64LE(4096n,at+48);changed=true;break;}}
 assert.ok(changed);writeFileSync(p,b);
 const r=run();assert.notEqual(r.status,0);assert.match(r.stderr,/insufficient PT_LOAD alignment/);
}));
