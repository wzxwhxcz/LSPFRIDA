#ifndef LSP_WIRE_H
#define LSP_WIRE_H

/*
 * Zymbiote wire protocol, LSPFRIDA form (design §3.2):
 *   abstract unix socket; hello is 12 bytes host-endian
 *   {u32 pid; u32 ppid; u32 name_len} followed by name_len name bytes,
 *   daemon replies with the single ack byte 0x42.
 */
#include <stddef.h>
#include <stdint.h>

#define LSP_WIRE_HEADER_SIZE 12
#define LSP_WIRE_ACK_BYTE 0x42
#define LSP_WIRE_NAME_MAX 512

typedef struct
{
  uint32_t pid;
  uint32_t ppid;
  uint32_t name_len;
  char name[LSP_WIRE_NAME_MAX];
} LspHello;

int lsp_wire_read_hello (int fd, LspHello * out);
int lsp_wire_write_hello (int fd, uint32_t pid, uint32_t ppid, const char * name);
int lsp_wire_send_ack (int fd);

/* Abstract-socket helpers.  name is used verbatim after the leading NUL. */
int lsp_wire_listen (const char * name);
int lsp_wire_connect (const char * name);
int lsp_wire_connect_timeout (const char * name, int timeout_ms);

/* JSON string escaping into out (always NUL terminated). */
char * lsp_wire_json_escape (const char * in, char * out, size_t out_size);

#endif
