#ifndef LSP_PROC_MAPS_H
#define LSP_PROC_MAPS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/types.h>

typedef struct
{
  uint64_t start;
  uint64_t end;
  uint64_t offset;
  bool readable;
  bool writable;
  bool executable;
  bool shared;
  unsigned int dev_major;
  unsigned int dev_minor;
  uint64_t inode;
  char path[512];
} LspMap;

typedef struct
{
  LspMap *items;
  size_t count;
} LspMaps;

size_t lsp_page_size (void);
bool lsp_map_ends_with (const char * path, const char * suffix);

int lsp_maps_load (pid_t pid, LspMaps * out);
void lsp_maps_free (LspMaps * maps);

/* Last executable mapping whose path ends in `suffix`, or NULL. */
const LspMap * lsp_maps_find_exec_suffix (const LspMaps * maps, const char * suffix);

/* First mapping (any protection) whose path ends in `suffix`, or NULL. */
const LspMap * lsp_maps_find_path_suffix (const LspMaps * maps, const char * suffix);

/* Module base mapping: path matches and file offset == 0. */
const LspMap * lsp_maps_find_module_base (const LspMaps * maps, const char * path);

/* Boot heap (frida is_boot_heap): rw, non-exec, non-shared, ART image path. */
bool lsp_map_is_boot_heap (const LspMap * m);

/* Anonymous private rw-p mapping with offset 0 and no path (frida regex). */
bool lsp_map_is_anon_rw (const LspMap * m);

#endif
