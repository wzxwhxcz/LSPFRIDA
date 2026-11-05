#include "payload.h"

#include "elf_parser.h"
#include "gate_context.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

static void * lsp_memmem (const unsigned char * haystack, size_t haystack_len,
    const unsigned char * needle, size_t needle_len)
{
  size_t i;

  if (needle_len == 0 || needle_len > haystack_len)
    return NULL;

  for (i = 0; i + needle_len <= haystack_len; i++)
  {
    if (haystack[i] == needle[0] &&
        memcmp (haystack + i, needle, needle_len) == 0)
      return (void *) (haystack + i);
  }

  return NULL;
}

static void
write_pointer (unsigned char * bytes, size_t * cursor, uint64_t value)
{
  memcpy (bytes + *cursor, &value, sizeof (value));
  *cursor += sizeof (value);
}

int
lsp_payload_build (const char * gate_path, const char * socket_name,
    uint64_t payload_base, uint64_t original_protection,
    uint64_t original_setcontext, uint64_t original_set_argv0,
    const uint64_t * libc_funcs, size_t libc_func_count,
    uint64_t setenv_addr, uint64_t log_write_addr,
    LspPayload * out)
{
  LspElf elf;
  uint64_t text_vmaddr, text_offset, text_size;
  unsigned char * template_bytes;
  size_t socket_offset;
  size_t cursor;
  uint64_t setcontext_vaddr, setargv0_vaddr;
  size_t name_len;
  size_t i;
  int error;

  memset (out, 0, sizeof (*out));

  if (libc_func_count != LSP_PAYLOAD_LIBC_COUNT)
    return -EINVAL;

  error = lsp_elf_open (gate_path, &elf);
  if (error != 0)
    return error;

  error = lsp_elf_text_segment (&elf, &text_vmaddr, &text_offset, &text_size);
  if (error != 0)
  {
    lsp_elf_close (&elf);
    return error;
  }

  if (text_offset + text_size > elf.size)
  {
    lsp_elf_close (&elf);
    return -ENOEXEC;
  }

  template_bytes = malloc ((size_t) text_size);
  if (template_bytes == NULL)
  {
    lsp_elf_close (&elf);
    return -ENOMEM;
  }
  memcpy (template_bytes, elf.data + text_offset, (size_t) text_size);

  if (!lsp_elf_find_export (&elf, "lsp_gate_replacement_setcontext", &setcontext_vaddr) ||
      !lsp_elf_find_export (&elf, "lsp_gate_replacement_setargv0", &setargv0_vaddr))
  {
    free (template_bytes);
    lsp_elf_close (&elf);
    return -ENOEXEC;
  }

  {
    const unsigned char * needle = (const unsigned char *) LSP_GATE_SOCKET_TEMPLATE;
    size_t needle_len = sizeof (LSP_GATE_SOCKET_TEMPLATE) - 1;
    void * found = lsp_memmem (template_bytes, (size_t) text_size, needle, needle_len);

    if (found == NULL)
    {
      free (template_bytes);
      lsp_elf_close (&elf);
      return -ENOEXEC;
    }
    socket_offset = (size_t) ((unsigned char *) found - template_bytes);
  }

  name_len = strlen (socket_name);
  if (name_len >= LSP_GATE_SOCKET_FIELD_SIZE)
  {
    free (template_bytes);
    lsp_elf_close (&elf);
    return -EINVAL;
  }

  cursor = socket_offset;
  memset (template_bytes + cursor, 0, LSP_GATE_SOCKET_FIELD_SIZE);
  memcpy (template_bytes + cursor, socket_name, name_len);
  cursor += LSP_GATE_SOCKET_FIELD_SIZE;

  write_pointer (template_bytes, &cursor, payload_base);
  write_pointer (template_bytes, &cursor, (uint64_t) text_size);
  write_pointer (template_bytes, &cursor, original_protection);

  /* process_name is filled by the payload itself; leave the slot zero */
  cursor += sizeof (uint64_t);

  write_pointer (template_bytes, &cursor, original_setcontext);
  write_pointer (template_bytes, &cursor, original_set_argv0);

  for (i = 0; i != libc_func_count; i++)
    write_pointer (template_bytes, &cursor, libc_funcs[i]);

  write_pointer (template_bytes, &cursor, setenv_addr);
  write_pointer (template_bytes, &cursor, log_write_addr);

  if (cursor > (size_t) text_size)
  {
    free (template_bytes);
    lsp_elf_close (&elf);
    return -ENOEXEC;
  }

  out->bytes = template_bytes;
  out->size = (size_t) text_size;
  out->entry_setcontext = payload_base + (setcontext_vaddr - text_vmaddr);
  out->entry_setargv0 = payload_base + (setargv0_vaddr - text_vmaddr);
  out->text_vm_address = text_vmaddr;
  out->socket_field_offset = socket_offset;

  lsp_elf_close (&elf);
  return 0;
}

void
lsp_payload_free (LspPayload * payload)
{
  free (payload->bytes);
  memset (payload, 0, sizeof (*payload));
}
