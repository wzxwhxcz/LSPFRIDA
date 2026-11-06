package com.bail.testtarget

import android.util.Log

/**
 * LSPFRIDA 注入测试目标。
 *
 * Java 侧：[greet] —— 供 frida-java-bridge `Java.use(...).greet.implementation` hook。
 * Native 侧：[nativeCompute] —— JNI，位于 libtarget.so，
 * 供 `Interceptor.attach(Module.getExportByName("libtarget.so",
 * "Java_com_bail_testtarget_Target_nativeCompute"))` hook。
 *
 * 两个方法都会打一条 `TestTarget` tag 的 log，测试脚本用 logcat grep 它们。
 */
class Target {
    companion object {
        private const val TAG = "TestTarget"

        init {
            System.loadLibrary("target")
        }

        @JvmStatic
        fun greet(name: String): String {
            val result = "Hello, $name from Java"
            Log.i(TAG, "TARGET_JAVA greet($name) -> $result")
            return result
        }

        @JvmStatic
        external fun nativeCompute(x: Int): Int
    }
}
