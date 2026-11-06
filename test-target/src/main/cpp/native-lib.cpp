#include <jni.h>
#include <android/log.h>

#define TAG "TestTarget"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

// JNI for com.bail.testtarget.Target.nativeCompute (see Target.kt).
// Hook target for the native test:
//   Interceptor.attach(Module.getExportByName(
//       "libtarget.so", "Java_com_bail_testtarget_Target_nativeCompute"), {...})
extern "C" JNIEXPORT jint JNICALL
Java_com_bail_testtarget_Target_nativeCompute(JNIEnv* /*env*/, jclass /*clazz*/, jint x) {
    jint result = x * 2;
    LOGI("TARGET_NATIVE nativeCompute(%d) -> %d", (int)x, (int)result);
    return result;
}
