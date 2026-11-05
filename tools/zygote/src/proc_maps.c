#include "proc_maps.h"

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

size_t
lsp_page_size (void)
{
  long v = sysconf (_SC_PAGESIZE);
  return v > 0 ? (size_t) v : 4096u;
}

bool
lsp_map_ends_with (const char * path, const char * suffix)
{
  size_t plen, slen;

  plen = strlen (path);
  slen = strlen (suffix);
  if (slen > plen)
    return false;

  return strcmp (path + (plen - slen), suffix) == 0;
}

int
lsp_maps_load (pid_t pid, LspMaps * out)
{
  char file_path[64];
  FILE * f;
  char line[4096];
  size_t capacity = 64;

  out->items = calloc (capacity, sizeof (LspMap));
  out->count = 0;
  if (out->items == NULL)
    return -ENOMEM;

  snprintf (file_path, sizeof (file_path), "/proc/%d/maps", (int) pid);
  f = fopen (file_path, "re");
  if (f == NULL)
  {
    int err = errno;
    free (out->items);
    out->items = NULL;
    return -err;
  }

  while (fgets (line, sizeof (line), f) != NULL)
  {
    LspMap m;
    unsigned long long start, end, offset, inode;
    unsigned int major, minor;
    char perms[8];
    int consumed = 0;
    const char * rest;
    char * newline;
    int n;

    memset (&m, 0, sizeof (m));
    n = sscanf (line, "%llx-%llx %7s %llx %x:%x %llu %n",
        &start, &end, perms, &offset, &major, &minor, &inode, &consumed);
    if (n < 7)
      continue;

    m.start = (uint64_t) start;
    m.end = (uint64_t) end;
    m.offset = (uint64_t) offset;
    m.readable = perms[0] == 'r';
    m.writable = perms[1] == 'w';
    m.executable = perms[2] == 'x';
    m.shared = perms[3] == 's';
    m.dev_major = major;
    m.dev_minor = minor;
    m.inode = (uint64_t) inode;

    rest = line + consumed;
    while (*rest == ' ' || *rest == '\t')
      rest++;
    newline = strchr ((char *) rest, '\n');
    if (newline != NULL)
      *newline = '\0';
    snprintf (m.path, sizeof (m.path), "%s", rest);

    if (out->count == capacity)
    {
      LspMap * grown;

      capacity *= 2;
      grown = realloc (out->items, capacity * sizeof (LspMap));
      if (grown == NULL)
      {
        fclose (f);
        free (out->items);
        out->items = NULL;
        return -ENOMEM;
      }
      out->items = grown;
    }

    out->items[out->count++] = m;
  }

  fclose (f);
  return 0;
}

void
lsp_maps_free (LspMaps * maps)
{
  free (maps->items);
  maps->items = NULL;
  maps->count = 0;
}

const LspMap *
lsp_maps_find_exec_suffix (const LspMaps * maps, const char * suffix)
{
  const LspMap * found = NULL;
  size_t i;

  for (i = 0; i != maps->count; i++)
  {
    const LspMap * m = &maps->items[i];

    if (m->executable && lsp_map_ends_with (m->path, suffix))
      found = m;
  }

  return found;
}

const LspMap *
lsp_maps_find_path_suffix (const LspMaps * maps, const char * suffix)
{
  size_t i;

  for (i = 0; i != maps->count; i++)
  {
    if (lsp_map_ends_with (maps->items[i].path, suffix))
      return &maps->items[i];
  }

  return NULL;
}

const LspMap *
lsp_maps_find_module_base (const LspMaps * maps, const char * path)
{
  size_t i;
  const LspMap * fallback = NULL;

  for (i = 0; i != maps->count; i++)
  {
    const LspMap * m = &maps->items[i];

    if (strcmp (m->path, path) != 0)
      continue;

    if (fallback == NULL)
      fallback = m;

    if (m->offset == 0 && m->readable)
      return m;
  }

  return fallback;
}

bool
lsp_map_is_boot_heap (const LspMap * m)
{
  if (!m->readable || !m->writable || m->executable || m->shared)
    return false;

  return strstr (m->path, "boot.art") != NULL ||
      strstr (m->path, "boot-framework.art") != NULL ||
      strstr (m->path, "boot-image-methods.art") != NULL ||
      strstr (m->path, "dalvik-LinearAlloc") != NULL;
}

bool
lsp_map_is_anon_rw (const LspMap * m)
{
  return m->readable && m->writable && !m->executable &&
      m->path[0] == '\0' && m->offset == 0;
}
