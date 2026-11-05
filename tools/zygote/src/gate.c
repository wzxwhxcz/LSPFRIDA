/*
 * LSPFRIDA zygote gate payload.
 *
 * Port of frida-core src/linux/helpers/zymbiote.c (commit d5bc4840) to the
 * LSPFRIDA form frozen in docs/Zygote-Injection-Design.md §3.1-§3.3.
 *
 * Deliberate differences from frida (all recorded in README/deviation table):
 *   - socket name placeholder is "/lspfrifa-zygote-" + 32 '0', ack byte 0x42;
 *   - env markers LSPFRIDA_ZYGOTE_GATE{,_NAME,_PPID,_PROC} are written
 *     unconditionally, even when the daemon is absent (proof the gate ran);
 *   - optional android log_write() line "GATE_HIT ... ack=0|1";
 *   - the SIGSTOP exists via a TRUE TAIL CALL (`musttail` on clang): the
 *     daemon reverts this payload page while the child is stopped, so the
 *     resume return address must not point into reverted payload memory.
 *     On compilers without `musttail` (host gcc 13) a plain call is emitted;
 *     it is functionally a fallback only and MUST NOT be used for a shipped
 *     gate (host E2E showed SIGSEGV when the payload page was reverted).
 *
 * Freestanding: no libc symbols are referenced.  Every syscall/libc call goes
 * through the frozen LspGateContext function pointer table.  The payload is
 * linked with -nostdlib -shared -fPIC and a custom linker script (gate.lds).
 */
#include "gate_context.h"

#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>

#if defined (__clang__)
# define LSP_MUSTTAIL __attribute__ ((musttail))
#else
# define LSP_MUSTTAIL
#endif

LspGateContext lsp_gate =
{
  .socket_path = LSP_GATE_SOCKET_TEMPLATE,
};

int lsp_gate_replacement_setargv0 (JNIEnv * env, jobject clazz, jstring name);
int lsp_gate_replacement_setcontext (uid_t uid, bool is_system_server, const char * seinfo, const char * name);

static void lsp_gate_wait_for_permission_to_resume (const char * process_name, bool * revert_now);
static void lsp_gate_publish_markers (const char * process_name);
static void lsp_gate_log (const char * process_name, bool ack);

static int lsp_gate_stop_and_return_from_setargv0 (JNIEnv * env, jobject clazz, jstring name);

static int lsp_gate_get_errno (void);
static int lsp_gate_connect (int sockfd, const struct sockaddr * addr, socklen_t addrlen);
static ssize_t lsp_gate_sendmsg (int sockfd, const struct msghdr * msg, int flags);
static bool lsp_gate_sendmsg_all (int sockfd, struct iovec * iov, size_t iovlen, int flags);
static ssize_t lsp_gate_recv (int sockfd, void * buf, size_t len, int flags);

/* ------------------------------------------------------------------ */
/* Entry point 1: libandroid_runtime's import of selinux_android_setcontext */
/* ------------------------------------------------------------------ */

__attribute__ ((section (".text.entrypoint")))
__attribute__ ((visibility ("default")))
int
lsp_gate_replacement_setcontext (uid_t uid, bool is_system_server, const char * seinfo, const char * name)
{
  int res;

  res = lsp_gate.original_setcontext (uid, is_system_server, seinfo, name);
  if (res == -1)
    return -1;

  if (lsp_gate.process_name == NULL)
  {
    lsp_gate.mprotect (lsp_gate.payload_base, lsp_gate.payload_size, PROT_READ | PROT_WRITE | PROT_EXEC);
    lsp_gate.process_name = lsp_gate.strdup (name);
  }

  return res;
}

/* ------------------------------------------------------------------ */
/* Entry point 2: android.os.Process.setArgV0()                          */
/* ------------------------------------------------------------------ */

__attribute__ ((section (".text.entrypoint")))
__attribute__ ((visibility ("default")))
int
lsp_gate_replacement_setargv0 (JNIEnv * env, jobject clazz, jstring name)
{
  const char * name_utf8;
  bool revert_now;

  /* 1. run the original logic first (frida ordering, design §3.3) */
  lsp_gate.original_set_argv0 (env, clazz, name);

  /* 2. resolve the process name: the strdup'd copy from setcontext() when
   *    available (always on Android, libselinux is present), otherwise the
   *    Java string. */
  if (lsp_gate.process_name != NULL)
  {
    name_utf8 = lsp_gate.process_name;
  }
  else if (env != NULL && name != NULL)
  {
    name_utf8 = (*env)->GetStringUTFChars (env, name, NULL);
    if (name_utf8 == NULL)
      name_utf8 = "";
  }
  else
  {
    name_utf8 = "";
  }

  /* 3. publish markers unconditionally (proof of execution without a daemon) */
  lsp_gate_publish_markers (name_utf8);

  /* 4. best-effort handshake; failure => no SIGSTOP, normal return (fail-open) */
  lsp_gate_wait_for_permission_to_resume (name_utf8, &revert_now);

  lsp_gate_log (name_utf8, revert_now);

  /* restore the payload page protection and release the name copy */
  if (lsp_gate.process_name != NULL)
  {
    lsp_gate.free (lsp_gate.process_name);
    lsp_gate.process_name = NULL;
    lsp_gate.mprotect (lsp_gate.payload_base, lsp_gate.payload_size, lsp_gate.payload_original_protection);
  }
  else if (env != NULL && name != NULL && name_utf8 != NULL && name_utf8[0] != '\0')
  {
    (*env)->ReleaseStringUTFChars (env, name, name_utf8);
  }

  if (revert_now)
  {
    /* TRUE TAIL CALL into raise(SIGSTOP): the daemon reverts our payload page
     * while we are stopped, so when SIGCONT resumes us the return address must
     * NOT be inside the (now reverted) payload.  musttail makes raise() return
     * straight to setArgV0's original caller.  This is exactly why frida-core
     * uses musttail here.  Compilers without musttail fall back to a plain
     * call (host gcc 13); the shipped host/Android gates are built with clang,
     * which supports it. */
    LSP_MUSTTAIL return lsp_gate_stop_and_return_from_setargv0 (env, clazz, name);
  }

  return 0;
}

/* ------------------------------------------------------------------ */
/* Marker publication: env (always) + optional logcat                  */
/* ------------------------------------------------------------------ */

static char *
lsp_gate_append (char * cursor, const char * text)
{
  while (*text != '\0')
    *cursor++ = *text++;
  return cursor;
}

static char *
lsp_gate_append_dec (char * cursor, unsigned int value)
{
  char tmp[12];
  unsigned int i = 0;

  if (value == 0)
  {
    *cursor++ = '0';
    return cursor;
  }

  while (value != 0 && i < sizeof (tmp))
  {
    tmp[i++] = (char) ('0' + (value % 10));
    value /= 10;
  }

  while (i != 0)
    *cursor++ = tmp[--i];

  return cursor;
}

static void
lsp_gate_publish_markers (const char * process_name)
{
  char ppid_text[12];

  if (lsp_gate.setenv == NULL)
    return;

  lsp_gate.setenv ("LSPFRIDA_ZYGOTE_GATE", "1", 1);
  lsp_gate.setenv ("LSPFRIDA_ZYGOTE_GATE_NAME", lsp_gate.socket_path, 1);

  {
    char * cursor = lsp_gate_append_dec (ppid_text, (unsigned int) lsp_gate.getppid ());
    *cursor = '\0';
  }
  lsp_gate.setenv ("LSPFRIDA_ZYGOTE_GATE_PPID", ppid_text, 1);
  lsp_gate.setenv ("LSPFRIDA_ZYGOTE_GATE_PROC", process_name, 1);
}

static void
lsp_gate_log (const char * process_name, bool ack)
{
  char text[256];
  char * cursor;

  if (lsp_gate.log_write == NULL)
    return;

  cursor = text;
  cursor = lsp_gate_append (cursor, "GATE_HIT pid=");
  cursor = lsp_gate_append_dec (cursor, (unsigned int) lsp_gate.getpid ());
  cursor = lsp_gate_append (cursor, " ppid=");
  cursor = lsp_gate_append_dec (cursor, (unsigned int) lsp_gate.getppid ());
  cursor = lsp_gate_append (cursor, " name=");
  cursor = lsp_gate_append (cursor, process_name);
  cursor = lsp_gate_append (cursor, " host=");
  cursor = lsp_gate_append (cursor, lsp_gate.socket_path);
  cursor = lsp_gate_append (cursor, ack ? " ack=1" : " ack=0");
  *cursor = '\0';

  /* ANDROID_LOG_INFO == 4.  Tag is the literal frozen in design §3.3. */
  lsp_gate.log_write (4, "LSPFRIFA-Zygote", text);
}

/* ------------------------------------------------------------------ */
/* Wire protocol: abstract socket hello {pid, ppid, name_len} + name,  */
/* then wait for a single ack byte 0x42 (design §3.2)                  */
/* ------------------------------------------------------------------ */

static void
lsp_gate_wait_for_permission_to_resume (const char * process_name, bool * revert_now)
{
  int fd;
  struct sockaddr_un addr;
  socklen_t addrlen;
  unsigned int name_len;

  *revert_now = false;

  fd = lsp_gate.socket (AF_UNIX, SOCK_STREAM, 0);
  if (fd == -1)
    goto beach;

  addr.sun_family = AF_UNIX;
  addr.sun_path[0] = '\0';

  name_len = 0;
  for (unsigned int i = 0; i != sizeof (lsp_gate.socket_path); i++)
  {
    if (lsp_gate.socket_path[i] == '\0')
      break;

    if (1u + i >= sizeof (addr.sun_path))
      break;

    addr.sun_path[1u + i] = lsp_gate.socket_path[i];
    name_len++;
  }

  addrlen = (socklen_t) (offsetof (struct sockaddr_un, sun_path) + 1u + name_len);

  if (lsp_gate_connect (fd, (const struct sockaddr *) &addr, addrlen) == -1)
    goto beach;

  {
    struct
    {
      uint32_t pid;
      uint32_t ppid;
      uint32_t process_name_len;
    } header;
    struct iovec iov[2];

    header.pid = (uint32_t) lsp_gate.getpid ();
    header.ppid = (uint32_t) lsp_gate.getppid ();

    header.process_name_len = 0;
    while (process_name[header.process_name_len] != '\0')
      header.process_name_len++;

    iov[0].iov_base = &header;
    iov[0].iov_len = sizeof (header);

    iov[1].iov_base = (void *) process_name;
    iov[1].iov_len = header.process_name_len;

    if (!lsp_gate_sendmsg_all (fd, iov, 2, MSG_NOSIGNAL))
      goto beach;
  }

  {
    uint8_t rx;

    if (lsp_gate_recv (fd, &rx, 1, 0) != 1)
      goto beach;

    if (rx != 0x42)
      goto beach;
  }

  *revert_now = true;

beach:
  if (fd != -1)
    lsp_gate.close (fd);
}

/* ------------------------------------------------------------------ */
/* Tail call to raise(SIGSTOP), compiler-independent asm               */
/* ------------------------------------------------------------------ */

#if defined (__x86_64__)

# define LSP_TAILCALL_TO_RAISE_SIGSTOP()                               \
  __asm__ __volatile__ (                                               \
      "mov    $%c[sig], %%edi\n"                                       \
                                                                       \
      "leaq   lsp_gate(%%rip), %%r11\n"                                \
      "movq   %c[raise_off](%%r11), %%r11\n"                           \
                                                                       \
      "jmp    *%%r11\n"                                                \
    :                                                                  \
    : [sig] "i" (SIGSTOP),                                             \
      [raise_off] "i" (offsetof (LspGateContext, raise))               \
    : "r11", "rdi", "memory"                                           \
  )

#elif defined (__aarch64__)

# define LSP_TAILCALL_TO_RAISE_SIGSTOP()                               \
  __asm__ __volatile__ (                                               \
      "mov    w0, #%[sig]\n"                                           \
                                                                       \
      "adrp   x16, lsp_gate\n"                                         \
      "add    x16, x16, :lo12:lsp_gate\n"                              \
      "ldr    x16, [x16, %[raise_off]]\n"                              \
                                                                       \
      "br     x16\n"                                                   \
    :                                                                  \
    : [sig] "i" (SIGSTOP),                                             \
      [raise_off] "i" (offsetof (LspGateContext, raise))               \
    : "x16", "memory"                                                  \
  )

#else
# error Unsupported architecture
#endif

__attribute__ ((naked, noinline))
static int
lsp_gate_stop_and_return_from_setargv0 (JNIEnv * env, jobject clazz, jstring name)
{
  LSP_TAILCALL_TO_RAISE_SIGSTOP ();
}

/* ------------------------------------------------------------------ */
/* libc wrappers, all through the context table (EINTR retry)          */
/* ------------------------------------------------------------------ */

static int
lsp_gate_get_errno (void)
{
  return *lsp_gate.__errno ();
}

static int
lsp_gate_connect (int sockfd, const struct sockaddr * addr, socklen_t addrlen)
{
  for (;;)
  {
    if (lsp_gate.connect (sockfd, addr, addrlen) == 0)
      return 0;

    if (lsp_gate_get_errno () == EINTR)
      continue;

    return -1;
  }
}

static ssize_t
lsp_gate_sendmsg (int sockfd, const struct msghdr * msg, int flags)
{
  for (;;)
  {
    ssize_t n = lsp_gate.sendmsg (sockfd, msg, flags);
    if (n != -1)
      return n;

    if (lsp_gate_get_errno () == EINTR)
      continue;

    return -1;
  }
}

static bool
lsp_gate_sendmsg_all (int sockfd, struct iovec * iov, size_t iovlen, int flags)
{
  size_t idx = 0;
  size_t off = 0;

  while (idx != iovlen)
  {
    struct msghdr m;

    m.msg_name = NULL;
    m.msg_namelen = 0;
    m.msg_iov = &iov[idx];
    m.msg_iovlen = iovlen - idx;
    m.msg_control = NULL;
    m.msg_controllen = 0;
    m.msg_flags = 0;

    ssize_t n = lsp_gate_sendmsg (sockfd, &m, flags);
    if (n == -1)
      return false;

    size_t remaining = n;

    while (remaining != 0)
    {
      size_t avail = iov[idx].iov_len - off;

      if (remaining < avail)
      {
        iov[idx].iov_base = (char *) iov[idx].iov_base + remaining;
        iov[idx].iov_len -= remaining;
        remaining = 0;
      }
      else
      {
        remaining -= avail;
        idx++;
        off = 0;
        if (idx == iovlen)
          break;
      }
    }
  }

  return true;
}

static ssize_t
lsp_gate_recv (int sockfd, void * buf, size_t len, int flags)
{
  for (;;)
  {
    ssize_t n = lsp_gate.recv (sockfd, buf, len, flags);
    if (n != -1)
      return n;

    if (lsp_gate_get_errno () == EINTR)
      continue;

    return -1;
  }
}
