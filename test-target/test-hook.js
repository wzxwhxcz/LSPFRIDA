// LSPFRIDA 注入测试脚本 —— 目标包 com.bail.testtarget。
//
// 用法：在 LSPFRIDA 应用里，给 com.bail.testtarget 设置这份脚本，
// 然后在 LSPosed 里启用模块并勾选目标包，重启目标应用。
// 也可以直接把文件推到手机上再按 README 里的方法设置。
//
// 预期 logcat（tag TestTarget / lsp-test）：
//   [lsp-test] HOOK_JAVA_INSTALLED
//   [lsp-test] HOOK_JAVA_FIRED greet(World)
//   [lsp-test] HOOK_NATIVE_INSTALLED
//   [lsp-test] HOOK_NATIVE_FIRED nativeCompute(21)
//   [lsp-test] HOOK_NATIVE_RET 42
// 目标自身的 log（不受 hook 影响，基线对照）：
//   TARGET_JAVA greet(World) -> Hello, World from Java
//   TARGET_NATIVE nativeCompute(21) -> 42

Java.perform(function () {
    var Target = Java.use("com.bail.testtarget.Target");
    Target.greet.implementation = function (name) {
        console.log("[lsp-test] HOOK_JAVA_FIRED greet(" + name + ")");
        var orig = this.greet(name);
        return orig + " [hooked]";
    };
    console.log("[lsp-test] HOOK_JAVA_INSTALLED");
});

// Native：libtarget.so 在 Target 类初始化时才加载（应用启动后），
// 脚本跑在包就绪阶段时它可能还没加载，用轮询等它出现。
function hookNative() {
    var m = Process.findModuleByName("libtarget.so");
    if (!m) {
        setTimeout(hookNative, 200);
        return;
    }
    var addr = m.getExportByName("Java_com_bail_testtarget_Target_nativeCompute");
    Interceptor.attach(addr, {
        onEnter: function (args) {
            // JNI: (JNIEnv*, jclass, jint x) —— x 是 args[2]。
            console.log("[lsp-test] HOOK_NATIVE_FIRED nativeCompute(" + args[2].toInt32() + ")");
        },
        onLeave: function (retval) {
            console.log("[lsp-test] HOOK_NATIVE_RET " + retval.toInt32());
        }
    });
    console.log("[lsp-test] HOOK_NATIVE_INSTALLED");
}
hookNative();
