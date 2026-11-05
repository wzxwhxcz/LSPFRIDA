#ifndef LSP_PAYLOAD_H
#define LSP_PAYLOAD_H

#include <stddef.h>
#include <stdint.h>

typedef struct
{
  unsigned char * bytes;
  size_t size;

  uint64_t entry_setcontext;
  uint64_t entry_setargv0;

  uint64_t text_vm_address;
  size_t socket_field_offset; /* offset of socket_path[64] inside bytes */
} LspPayload;

/*
 * Build the payload template that gets pwritten at payload_base.
 *
 * gate_path              path to lspfrifa-gate.elf
 * socket_name            abstract socket name (<= 63 bytes)
 * payload_base           runtime address the template will be copied to
 * original_protection    mprotect value the gate restores
 * original_setcontext    runtime address of selinux_android_setcontext
 * original_set_argv0     runtime address of android_os_Process_setArgV0
 * libc_funcs             frozen-order libc addresses (12 entries, see below)
 * setenv_addr, log_write_addr   LSPFRIDA additions (log_write may be 0)
 *
 * Frozen libc order (design §3.1):
 *   mprotect, strdup, free, socket, connect, __errno, getpid, getppid,
 *   sendmsg, recv, close, raise
 */
#define LSP_PAYLOAD_LIBC_COUNT 12

int lsp_payload_build (const char * gate_path, const char * socket_name,
    uint64_t payload_base, uint64_t original_protection,
    uint64_t original_setcontext, uint64_t original_set_argv0,
    const uint64_t * libc_funcs, size_t libc_func_count,
    uint64_t setenv_addr, uint64_t log_write_addr,
    LspPayload * out);
void lsp_payload_free (LspPayload * payload);

#endif
