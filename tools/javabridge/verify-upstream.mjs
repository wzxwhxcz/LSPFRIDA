/*
 * 上游一致性验证（第一阶段"bundle 生死"判定的证据脚本）。
 *
 * 做什么：用**未打补丁**的 node_modules/frida-java-bridge@7.0.13 + buffer@6.0.3，
 * 以 esbuild-wasm 0.28.2 / iife / target=es2022 / legalComments=eof 重新打出
 * 上游原版 bundle，与两个金标准比对 sha256：
 *
 *   GOLDEN_PRISTINE  —— 本次复算应得到的字节（= 上游 npm 包的真实产物）
 *   GOLDEN_HISTORIC  —— 仓库基线 a0aaf09 里 JavaBridgeBundle.kt 内联的 base64 解码字节
 *
 * 两者相同 ⇒ 历史内联 bundle 确实是 7.0.13 原版（"版本号没写错"），
 * 那么审查报告 §1.6 "bundle 是死的" 就只能是**装配层**（Object.assign 只复制自有字段）
 * 的问题，而不是"包里没有/版本不对"。这正是第一阶段要推翻的前提假设。
 *
 * 用法：cd tools/javabridge && node verify-upstream.mjs
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild-wasm';

const here = path.dirname(fileURLToPath(import.meta.url));

// 上游 npm 包的真实产物（frida-java-bridge@7.0.13 gitHead 6ab2a63e…）
const GOLDEN_PRISTINE = {
  bytes: 475575,
  sha256: 'cca1a5c523f2d6012dad3638fba360952d3fc71757d41ad258f5ee2a56ec700c',
};
// 仓库基线 a0aaf09 的 JavaBridgeBundle.kt 解码产物（若上游有任何变化，这里会先红）
const GOLDEN_HISTORIC = {
  bytes: 475575,
  sha256: 'cca1a5c523f2d6012dad3638fba360952d3fc71757d41ad258f5ee2a56ec700c',
};

const PACKAGE_DIRS = ['frida-java-bridge', 'buffer', 'base64-js', 'ieee754'];

function sha256 (text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

function prepare () {
  const dir = path.join(here, '.cache', 'verify');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(path.join(dir, 'node_modules'), { recursive: true });
  for (const name of PACKAGE_DIRS) {
    fs.cpSync(path.join(here, 'node_modules', name), path.join(dir, 'node_modules', name),
      { recursive: true, dereference: true });
  }
  fs.copyFileSync(path.join(here, 'entry-upstream.js'), path.join(dir, 'entry.js'));
  return dir;
}

async function main () {
  const pkgPath = path.join(here, 'node_modules', 'frida-java-bridge', 'package.json');
  const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
  console.log(`[verify] frida-java-bridge@${pkg.version} (期望 7.0.13)`);

  const dir = prepare();
  const result = await esbuild.build({
    absWorkingDir: dir,
    entryPoints: ['entry.js'],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'es2022',
    legalComments: 'eof',
    write: false,
    logLevel: 'warning',
  });
  const text = result.outputFiles[0].text;
  const bytes = Buffer.byteLength(text, 'utf8');
  const digest = sha256(text);

  console.log(`[verify] pristine rebuild: bytes=${bytes} sha256=${digest}`);
  console.log(`[verify] golden  pristine: bytes=${GOLDEN_PRISTINE.bytes} sha256=${GOLDEN_PRISTINE.sha256}`);
  console.log(`[verify] golden  historic: bytes=${GOLDEN_HISTORIC.bytes} sha256=${GOLDEN_HISTORIC.sha256}`);

  const pristineOk = bytes === GOLDEN_PRISTINE.bytes && digest === GOLDEN_PRISTINE.sha256;
  const historicOk = bytes === GOLDEN_HISTORIC.bytes && digest === GOLDEN_HISTORIC.sha256;

  if (pkg.version !== '7.0.13') {
    console.error('[verify] FAIL: 版本不是 7.0.13');
    process.exitCode = 1;
    return;
  }
  if (!pristineOk) {
    console.error('[verify] FAIL: 复算产物与上游金标准不一致（esbuild 版本/打包参数漂移？）');
    process.exitCode = 1;
    return;
  }
  if (!historicOk) {
    console.error('[verify] FAIL: 与仓库基线内联 bundle 不一致（历史 bundle 不是 7.0.13 原版？）');
    process.exitCode = 1;
    return;
  }

  console.log('[verify] PASS: 上游 7.0.13 原版可逐字节复现，且与基线内联 bundle 完全一致');
}

main().catch((e) => {
  console.error('[verify] FAILED:', e.message);
  process.exitCode = 1;
});
