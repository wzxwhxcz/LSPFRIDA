/*
 * Mock libandroid_runtime.so for the host E2E.
 *
 *  - exports the exact mangled android.os.Process.setArgV0 identifier the
 *    injector looks up, sets mock_original_called when invoked;
 *  - imports selinux_android_setcontext from mock libselinux.so and exposes
 *    mock_call_setcontext(), so the test child can call "through the patched
 *    GOT slot";
 *  - mock_original_called is the flag the child asserts on.
 */
extern int selinux_android_setcontext (unsigned int uid, int is_system_server,
    const char * seinfo, const char * name);

int mock_original_called = 0;

/* The real signature is (JNIEnv *, jobject, jstring); the injector only needs
 * the identifier, and the gate reads process_name from setcontext() first, so
 * the dummy ABI is enough on the host. */
void
_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring (void * env,
    void * clazz, void * name)
{
  (void) env;
  (void) clazz;
  (void) name;

  mock_original_called = 1;
}

int
mock_call_setcontext (void)
{
  return selinux_android_setcontext (0, 0, "mock_seinfo", "com.test.app");
}
