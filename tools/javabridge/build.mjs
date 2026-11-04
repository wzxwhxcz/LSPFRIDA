/*
 * 把 frida-java-bridge 7.0.13（含 LSPFRIDA 构建期补丁 + adapter）打成单文件 IIFE，
 * 并重写 Kotlin 的 JavaBridgeBundle.kt（base64 分块，规避 DEX 单字符串 64KB 限制）。
 *
 * 用法：cd tools/javabridge && npm install && node build.mjs [--emit <JavaBridgeBundle.kt 路径>]
 *
 * 可复现性：node_modules 保持 pristine；补丁打在 .cache/work 下的副本上，
 * 所以 verify-upstream.mjs 可以随时用同一 node_modules 复算上游原版字节。
 */

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import * as esbuild from 'esbuild-wasm';
import { applyAll } from './apply-patches.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const cacheDir = path.join(here, '.cache');
const workDir = path.join(cacheDir, 'work');
const defaultKotlinOut = path.join(
  repoRoot, 'app', 'src', 'main', 'kotlin', 'com', 'bail', 'lspfrifa', 'xposed', 'JavaBridgeBundle.kt');

const PACKAGE_DIRS = ['frida-java-bridge', 'buffer', 'base64-js', 'ieee754'];
const CHUNK_SIZE = 16000;

function rmrf (dir) {
  fs.rmSync(dir, { recursive: true, force: true });
}

function copyDir (src, dst) {
  fs.cpSync(src, dst, { recursive: true, dereference: true });
}

function prepareWorkDir () {
  rmrf(workDir);
  fs.mkdirSync(path.join(workDir, 'node_modules'), { recursive: true });
  for (const name of PACKAGE_DIRS) {
    copyDir(path.join(here, 'node_modules', name), path.join(workDir, 'node_modules', name));
  }
  for (const file of ['entry.js', 'entry-upstream.js', 'adapter.js']) {
    fs.copyFileSync(path.join(here, file), path.join(workDir, file));
  }
  applyAll(workDir);
}

async function bundle (entry, outFile) {
  const result = await esbuild.build({
    absWorkingDir: workDir,
    entryPoints: [entry],
    bundle: true,
    format: 'iife',
    platform: 'browser',
    target: 'es2022',
    legalComments: 'eof',
    write: false,
    logLevel: 'warning',
  });
  const text = result.outputFiles[0].text;
  fs.writeFileSync(outFile, text);
  return text;
}

function sha256 (text) {
  return crypto.createHash('sha256').update(text, 'utf8').digest('hex');
}

function escapeForKotlinString (chunk) {
  // base64 只含 A-Za-z0-9+/=，无需转义
  return chunk;
}

function emitKotlin (script, outFile, meta) {
  // 先整体 base64（运行期一次性解码），再按 DEX 单字符串上限切块存储。
  const b64 = Buffer.from(script, 'utf8').toString('base64');
  const chunks = [];
  for (let i = 0; i < b64.length; i += CHUNK_SIZE) {
    chunks.push(b64.slice(i, i + CHUNK_SIZE));
  }
  const chunkLines = chunks
    .map((c, i) => `        "${escapeForKotlinString(c)}"${i === chunks.length - 1 ? '' : ','}`)
    .join('\n');

  const source = `package com.bail.lspfrifa.xposed

/**
 * frida-java-bridge 7.0.13 内联 bundle（LSPFRIDA 第一阶段：接通上游 Java 桥）
 *
 * ⚠ 本文件由构建脚本生成，不要手改。改动流程：
 *   cd tools/javabridge && npm install && node build.mjs
 *   生成器与补丁见 tools/javabridge/（BRIDGE-CONTRACT.md 是跨语言契约）。
 *
 * 组成：
 *   - npm frida-java-bridge@7.0.13（gitHead ${meta.gitHead}），buffer@6.0.3 内联
 *   - 构建期补丁 ${meta.patchCount} 处（apply-patches.mjs）：
 *       P0001 方法替换落点改道外部 backend（LSPlant），原生 ArtMethod 路径保留为回退
 *       P0002 替换元数据交给 backend / P0003 $init 标注 / P0004 JVMTI 短路（沿用既有稳定性补丁）
 *   - LSP 适配层 adapter.js：implementation 同步反馈、NativeCallback 同步 hook 回调、真实 this
 *
 * 打包参数：esbuild-wasm@${meta.esbuildVersion}，format=iife，target=es2022，legalComments=eof
 * 产物：${meta.bytes} 字节，sha256=${meta.sha256}
 * base64 分块：单字符串超 DEX 64KB 限制，运行时解码为 UTF-8 JS 源码。
 */
internal object JavaBridgeBundle {
    private val chunks = arrayOf(
${chunkLines}
    )

    internal val SCRIPT: String by lazy {
        val b64 = chunks.joinToString("")
        String(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP), Charsets.UTF_8)
    }
}
`;
  fs.writeFileSync(outFile, source);
  console.log(`[build] Kotlin 输出: ${path.relative(repoRoot, outFile)}（${chunks.length} chunks）`);
}

async function main () {
  const args = process.argv.slice(2);
  const emitIdx = args.indexOf('--emit');
  const kotlinOut = emitIdx >= 0 ? path.resolve(args[emitIdx + 1]) : defaultKotlinOut;

  prepareWorkDir();

  const outFile = path.join(cacheDir, 'javabridge.js');
  const script = await bundle('entry.js', outFile);

  // 自我检查：补丁与适配层确实进了产物
  // 注意：esbuild 会剥掉普通注释，所以这里校验的是**代码形状**而不是补丁注释标记。
  const mustContain = [
    'setReplacementBackend: () => setReplacementBackend',   // P0001 导出存在
    'replacementBackend.replace(this, impl, isInstanceMethod, argTypes', // P0001 改道
    'replacement._lspMeta',                                  // P0002 元数据
    'method.$lspCtorInit = true',                            // P0003 $init 标注
    'cachedJvmti = [null]',                                  // P0004 JVMTI 短路
    '[lsp] Java bridge adapter',                             // adapter 已装入
    'new NativeCallback',                                    // 同步 hook 回调机制
    'globalThis.Java =',                                     // 全局注入
  ];
  for (const needle of mustContain) {
    if (!script.includes(needle)) {
      throw new Error(`[build] bundle 缺少预期内容: ${needle}`);
    }
  }

  const pkg = JSON.parse(fs.readFileSync(
    path.join(here, 'node_modules', 'frida-java-bridge', 'package.json'), 'utf8'));
  const esbuildPkg = JSON.parse(fs.readFileSync(
    path.join(here, 'node_modules', 'esbuild-wasm', 'package.json'), 'utf8'));

  const meta = {
    gitHead: '6ab2a63e4b02ab881f11ea06632cebea4b1b435e',
    patchCount: 10,
    esbuildVersion: esbuildPkg.version,
    bytes: Buffer.byteLength(script, 'utf8'),
    sha256: sha256(script),
  };
  if (pkg.version !== '7.0.13') {
    throw new Error(`[build] node_modules 里是 frida-java-bridge ${pkg.version}，期望 7.0.13`);
  }

  console.log(`[build] upstream=${pkg.version} target=es2022 bytes=${meta.bytes} sha256=${meta.sha256}`);
  emitKotlin(script, kotlinOut, meta);
  console.log('[build] done');
}

main().catch((e) => {
  console.error('[build] FAILED:', e.message);
  process.exitCode = 1;
});
