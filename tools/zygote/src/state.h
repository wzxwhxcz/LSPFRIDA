#ifndef LSP_STATE_H
#define LSP_STATE_H

#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

#define LSP_STATE_MAX_SLOTS 8
#define LSP_STATE_PATH_MAX 512

/*
 * On-disk record for one patched process, written under <dir>/<pid>.patch as
 * plain text (design §3.4).  It exists so a later `revert` works even after
 * the daemon was SIGKILLed, and so already-patched re-injection can restore
 * the original payload page.
 */
typedef struct
{
  uint64_t address;
  size_t length;
  unsigned char original[8];
  unsigned char replacement[8];
} LspPatchSlot;

typedef struct
{
  pid_t pid;
  uint64_t payload_base;
  uint64_t payload_size;
  uint64_t payload_protection;
  char payload_path[LSP_STATE_PATH_MAX];
  uint64_t payload_file_offset;

  /* original bytes of the payload page (frida restores these on revert) */
  unsigned char * payload_original;
  size_t payload_original_size;

  LspPatchSlot slots[LSP_STATE_MAX_SLOTS];
  size_t slot_count;
} LspPatchState;

int lsp_state_save (const char * dir, const LspPatchState * state);
int lsp_state_load (const char * dir, pid_t pid, LspPatchState * out);
int lsp_state_remove (const char * dir, pid_t pid);
void lsp_state_clear (LspPatchState * state);
int lsp_state_list (const char * dir, pid_t ** pids, size_t * count);

#endif
