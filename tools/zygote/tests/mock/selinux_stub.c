/*
 * Mock libselinux.so for the host E2E: exports selinux_android_setcontext so
 * the injector can resolve it as libandroid_runtime's import target.  The
 * gate's "original_setcontext" must call into this and set mock_selinux_seen.
 */
int mock_selinux_seen = 0;

int
selinux_android_setcontext (unsigned int uid, int is_system_server,
    const char * seinfo, const char * name)
{
  (void) uid;
  (void) is_system_server;
  (void) seinfo;
  (void) name;

  mock_selinux_seen = 1;
  return 0;
}
