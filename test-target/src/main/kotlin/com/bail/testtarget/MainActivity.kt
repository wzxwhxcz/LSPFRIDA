package com.bail.testtarget

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 注入测试目标主界面。
 *
 * 启动后自动各调一次 Java 与 Native（不用点按钮，脚本直接看 logcat 就行），
 * 结果同时写到屏幕 TextView。两个按钮可手动再触发一次。
 */
class MainActivity : Activity() {

    private lateinit var resultView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resultView = TextView(this).apply {
            textSize = 16f
            setPadding(32, 32, 32, 32)
        }
        val javaButton = Button(this).apply {
            text = "Call Java greet()"
            setOnClickListener { runJava() }
        }
        val nativeButton = Button(this).apply {
            text = "Call Native nativeCompute(21)"
            setOnClickListener { runNative() }
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resultView)
            addView(javaButton)
            addView(nativeButton)
        }
        setContentView(layout)

        // 启动即自动跑一次，方便脚本无交互验证。
        runJava()
        runNative()
    }

    private fun runJava() {
        val result = Target.greet("World")
        Log.i("TestTarget", "TARGET_JAVA_UI result=$result")
        appendResult("Java: $result")
    }

    private fun runNative() {
        val result = Target.nativeCompute(21)
        Log.i("TestTarget", "TARGET_NATIVE_UI result=$result")
        appendResult("Native: $result")
    }

    private fun appendResult(line: String) {
        resultView.text = (resultView.text.toString() + "\n" + line).trim()
    }
}
