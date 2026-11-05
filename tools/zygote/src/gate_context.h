/*
 * LSPFRIDA zygote gate — frozen shared ABI between the payload and the injector.
 *
 * Authoritative definition: docs/Zygote-Injection-Design.md §3.1.
 * The field order/width below is ABI and MUST NOT be reordered, added to, or
 * removed.  Both gate.c (freestanding payload) and the injector include this
 * header, so the payload template cursor offsets in inject.c are derived from
 * the very same layout the gate was compiled with.
 *
 * Pointer width: 64-bit only (arm64 / x86_64), per design §3.1.
 */
#ifndef LSP_GATE_CONTEXT_H
#define LSP_GATE_CONTEXT_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <jni.h>

typedef struct _LspGateContext LspGateContext;

struct _LspGateContext
{
  /* 0x00: abstract socket name, NUL padded.  The template holds
   * "/lspfrifa-zygote-" followed by 32 '0' characters, which the injector
   * locates with memmem() and then overwrites. */
  char      socket_path[64];

  /* 0x40 */
  void *    payload_base;
  size_t    payload_size;
  size_t    payload_original_protection;

  /* 0x58: filled in the zygote by setcontext(); not written by the injector */
  char *    process_name;

  /* 0x60 */
  int    (* original_setcontext) (uid_t uid, bool is_system_server,
                                  const char * seinfo, const char * name);
  void   (* original_set_argv0) (JNIEnv * env, jobject clazz, jstring name);

  /* 0x70: 12 libc entry points, frozen order */
  int     (* mprotect) (void * addr, size_t len, int prot);
  char *  (* strdup) (const char * s);
  void    (* free) (void * ptr);
  int     (* socket) (int domain, int type, int protocol);
  int     (* connect) (int sockfd, const struct sockaddr * addr, socklen_t addrlen);
  int *   (* __errno) (void);
  pid_t   (* getpid) (void);
  pid_t   (* getppid) (void);
  ssize_t (* sendmsg) (int sockfd, const struct msghdr * msg, int flags);
  ssize_t (* recv) (int sockfd, void * buf, size_t len, int flags);
  int     (* close) (int fd);
  int     (* raise) (int sig);

  /* 0xd0: LSPFRIDA additions (design §3.1 "12+2") */
  int     (* setenv) (const char * name, const char * value, int overwrite);
  int     (* log_write) (int prio, const char * tag, const char * text); /* may be NULL */
};

extern LspGateContext lsp_gate;

/* The payload is only supported on 64-bit ABIs (design §3.1). */
_Static_assert (sizeof (void *) == 8, "LSPFRIDA gate requires a 64-bit ABI");

/* Static asserts on every field offset the injector writes. */
#define LSP_GATE_OFF(name, value) \
  _Static_assert (offsetof (LspGateContext, name) == (value), \
      "frozen ABI drift: " #name)
LSP_GATE_OFF (socket_path,                 0);
LSP_GATE_OFF (payload_base,                64);
LSP_GATE_OFF (payload_size,                72);
LSP_GATE_OFF (payload_original_protection, 80);
LSP_GATE_OFF (process_name,                88);
LSP_GATE_OFF (original_setcontext,         96);
LSP_GATE_OFF (original_set_argv0,          104);
LSP_GATE_OFF (mprotect,                    112);
LSP_GATE_OFF (strdup,                      120);
LSP_GATE_OFF (free,                        128);
LSP_GATE_OFF (socket,                      136);
LSP_GATE_OFF (connect,                     144);
LSP_GATE_OFF (__errno,                     152);
LSP_GATE_OFF (getpid,                      160);
LSP_GATE_OFF (getppid,                     168);
LSP_GATE_OFF (sendmsg,                     176);
LSP_GATE_OFF (recv,                        184);
LSP_GATE_OFF (close,                       192);
LSP_GATE_OFF (raise,                       200);
LSP_GATE_OFF (setenv,                      208);
LSP_GATE_OFF (log_write,                   216);
#undef LSP_GATE_OFF

_Static_assert (sizeof (LspGateContext) == 224, "frozen ABI drift: struct size");

/* The template placeholder the injector searches for (see design §3.1/§3.4). */
#define LSP_GATE_SOCKET_TEMPLATE "/lspfrifa-zygote-00000000000000000000000000000000"
#define LSP_GATE_SOCKET_FIELD_SIZE 64

#endif /* LSP_GATE_CONTEXT_H */
