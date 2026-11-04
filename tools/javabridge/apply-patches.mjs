/*
 * 对 frida-java-bridge@7.0.13 源码做**构建期**最小补丁（不手改 node_modules）。
 *
 * 全部补丁都按「精确锚点 + 恰好命中 1 次」断言；任何漂移直接抛错终止构建，
 * 避免"静默少打一个补丁"这类最难发现的问题。
 *
 * 补丁清单（与 DONE.md / BRIDGE-CONTRACT.md 对应）：
 *   P0001 lib/android.js      方法替换落点改道外部 backend（LSPlant），原生路径保留
 *   P0002 lib/class-factory.js 把替换元数据（类/方法/签名/handler）交给 backend
 *   P0003 lib/class-factory.js 标注 $init（构造器）方法实例，供 backend 识别 ctor
 *   P0004 lib/android.js      JVMTI 短路（沿用仓库既有 ART35/HyperOS 稳定性补丁，从运行时
 *                             正则 hack 改为构建期确定补丁）
 */

import fs from 'node:fs';
import path from 'node:path';

const PATCH_MARK = 'LSPFRIDA PATCH';

function replaceOnce (file, oldText, newText, label, expected = 1) {
  const source = fs.readFileSync(file, 'utf8');
  const count = source.split(oldText).length - 1;
  if (count !== expected) {
    throw new Error(
      `[apply-patches] ${label}: 锚点命中 ${count} 次（期望 ${expected}）→ 上游版本漂移？file=${path.basename(file)}`);
  }
  fs.writeFileSync(file, source.replace(oldText, newText));
  console.log(`[apply-patches] ${label}: OK`);
}

export function applyAll (rootDir) {
  const lib = path.join(rootDir, 'node_modules', 'frida-java-bridge', 'lib');
  const androidJs = path.join(lib, 'android.js');
  const classFactoryJs = path.join(lib, 'class-factory.js');

  // ---- P0001: 方法替换 backend 注入点 -------------------------------------
  replaceOnce(
    androidJs,
    '\nclass ArtMethodMangler {',
    `
// ==== ${PATCH_MARK} 0001 BEGIN ====
// 方法替换落点改道：replacementBackend 非 null 时由外部（LSPFRIDA adapter → LSPlant）
// 接管 replace/revert/resolveTarget；null 时保持上游原生 ArtMethod entry 改写路径
// （回退对照，禁止删除）。契约：tools/javabridge/BRIDGE-CONTRACT.md §0。
let replacementBackend = null;

export function setReplacementBackend (backend) {
  replacementBackend = backend;
}
// ==== ${PATCH_MARK} 0001 END ====

class ArtMethodMangler {`,
    'P0001.1 backend 注入点',
  );

  replaceOnce(
    androidJs,
    `  replace (impl, isInstanceMethod, argTypes, vm, api) {
    const { kAccCompileDontBother, artNterpEntryPoint } = api;`,
    `  replace (impl, isInstanceMethod, argTypes, vm, api) {
    if (replacementBackend !== null) {
      replacementBackend.replace(this, impl, isInstanceMethod, argTypes, vm, api);
      return;
    }

    const { kAccCompileDontBother, artNterpEntryPoint } = api;`,
    'P0001.2 ArtMethodMangler.replace 改道',
  );

  replaceOnce(
    androidJs,
    `  revert (vm) {
    const { hookedMethodId, interceptor } = this;`,
    `  revert (vm) {
    if (replacementBackend !== null) {
      replacementBackend.revert(this, vm);
      return;
    }

    const { hookedMethodId, interceptor } = this;`,
    'P0001.3 ArtMethodMangler.revert 改道',
  );

  replaceOnce(
    androidJs,
    `  resolveTarget (wrapper, isInstanceMethod, env, api) {
    return this.hookedMethodId;
  }`,
    `  resolveTarget (wrapper, isInstanceMethod, env, api) {
    if (replacementBackend !== null) {
      return replacementBackend.resolveTarget(this, wrapper, isInstanceMethod, env, api);
    }

    return this.hookedMethodId;
  }`,
    'P0001.4 ArtMethodMangler.resolveTarget 改道',
  );

  // Dalvik 分支（Android 5 以下才用；保留对称改道，避免"换设备就绕过 LSPlant"）
  replaceOnce(
    androidJs,
    `  replace (impl, isInstanceMethod, argTypes, vm, api) {
    const { methodId } = this;`,
    `  replace (impl, isInstanceMethod, argTypes, vm, api) {
    if (replacementBackend !== null) {
      replacementBackend.replace(this, impl, isInstanceMethod, argTypes, vm, api);
      return;
    }

    const { methodId } = this;`,
    'P0001.5 DalvikMethodMangler.replace 改道',
  );

  replaceOnce(
    androidJs,
    `  revert (vm) {
    Memory.copy(this.methodId, this.originalMethod, DVM_METHOD_SIZE);
  }`,
    `  revert (vm) {
    if (replacementBackend !== null) {
      replacementBackend.revert(this, vm);
      return;
    }

    Memory.copy(this.methodId, this.originalMethod, DVM_METHOD_SIZE);
  }`,
    'P0001.6 DalvikMethodMangler.revert 改道',
  );

  replaceOnce(
    androidJs,
    `  resolveTarget (wrapper, isInstanceMethod, env, api) {
    const thread = env.handle.add(DVM_JNI_ENV_OFFSET_SELF).readPointer();`,
    `  resolveTarget (wrapper, isInstanceMethod, env, api) {
    if (replacementBackend !== null) {
      return replacementBackend.resolveTarget(this, wrapper, isInstanceMethod, env, api);
    }

    const thread = env.handle.add(DVM_JNI_ENV_OFFSET_SELF).readPointer();`,
    'P0001.7 DalvikMethodMangler.resolveTarget 改道',
  );

  // ---- P0002: 把替换元数据交给 backend ------------------------------------
  replaceOnce(
    classFactoryJs,
    `        const replacement = implement(methodName, classWrapper, type, retType, argTypes, fn, this);
        const mangler = makeMethodMangler(methodId);
        replacement._m = mangler;`,
    `        const replacement = implement(methodName, classWrapper, type, retType, argTypes, fn, this);
        const mangler = makeMethodMangler(methodId);
        replacement._m = mangler;
        // ==== ${PATCH_MARK} 0002: backend 需要类名/方法名/签名/handler（LSPlant 侧按 tag 路由）
        replacement._lspMeta = {
          methodName: methodName,
          holder: classWrapper,
          type: type,
          retType: retType,
          argTypes: argTypes,
          handler: fn,
          ctorInit: this.$lspCtorInit === true
        };
        // ==== ${PATCH_MARK} 0002 END ====`,
    'P0002 replacement 元数据',
  );

  // ---- P0003: 标注 $init 方法实例 -----------------------------------------
  replaceOnce(
    classFactoryJs,
    `  if (jsInitMethods.length === 0) {
    throw new Error('no supported overloads');
  }

  return {
    allocAndInit: makeMethodDispatcher(jsCtorMethods),
    initOnly: makeMethodDispatcher(jsInitMethods)
  };`,
    `  if (jsInitMethods.length === 0) {
    throw new Error('no supported overloads');
  }

  // ==== ${PATCH_MARK} 0003: $init 的 method wrapper 名 = 类 simpleName，backend 需要
  // 明确区分「构造器 init」与「恰好同名的普通方法」，否则 Kotlin 侧可能挂错 Executable。
  jsInitMethods.forEach(method => {
    method.$lspCtorInit = true;
  });
  // ==== ${PATCH_MARK} 0003 END ====

  return {
    allocAndInit: makeMethodDispatcher(jsCtorMethods),
    initOnly: makeMethodDispatcher(jsInitMethods)
  };`,
    'P0003 $init 标注',
  );

  // ---- P0004: JVMTI 短路（沿用既有稳定性补丁，构建期生效）------------------
  replaceOnce(
    androidJs,
    'cachedJvmti = [tryGetEnvJvmti(vm, this.artRuntime)];',
    `// ==== ${PATCH_MARK} 0004: 短路 JVMTI env 探测（cachedJvmti 恒 null）。
        // 沿用仓库既有 "ART35/HyperOS 稳定性补丁"（此前由 Kotlin 运行时正则替换实现），
        // 改到构建期是为了让发布的 JS 字节确定、可 diff；ART flavor 的 choose/enumerate
        // 走 art::gc::Heap / ClassLinker，不依赖 JVMTI（jvm flavor 才依赖）。
        cachedJvmti = [null]; // ==== ${PATCH_MARK} 0004 END ====`,
    'P0004 JVMTI 短路',
  );

  console.log('[apply-patches] all patches applied');
}

export function isApplied (rootDir) {
  const androidJs = path.join(rootDir, 'node_modules', 'frida-java-bridge', 'lib', 'android.js');
  try {
    return fs.readFileSync(androidJs, 'utf8').includes(`${PATCH_MARK} 0001`);
  } catch (e) {
    return false;
  }
}
