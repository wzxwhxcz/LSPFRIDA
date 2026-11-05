#include "state.h"

#include <dirent.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void
state_path (const char * dir, pid_t pid, char * out, size_t out_size)
{
  snprintf (out, out_size, "%s/%d.patch", dir, (int) pid);
}

static void
hex_encode (const unsigned char * data, size_t len, char * out)
{
  static const char digits[] = "0123456789abcdef";
  size_t i;

  for (i = 0; i != len; i++)
  {
    out[i * 2] = digits[data[i] >> 4];
    out[i * 2 + 1] = digits[data[i] & 0xf];
  }
  out[len * 2] = '\0';
}

static int
hex_decode (const char * text, unsigned char * out, size_t max_len, size_t * out_len)
{
  size_t len = strlen (text);
  size_t i;

  if ((len & 1) != 0 || len / 2 > max_len)
    return -EINVAL;

  for (i = 0; i != len / 2; i++)
  {
    unsigned int value;
    char pair[3];

    pair[0] = text[i * 2];
    pair[1] = text[i * 2 + 1];
    pair[2] = '\0';
    if (sscanf (pair, "%2x", &value) != 1)
      return -EINVAL;
    out[i] = (unsigned char) value;
  }

  *out_len = len / 2;
  return 0;
}

int
lsp_state_save (const char * dir, const LspPatchState * state)
{
  char path[LSP_STATE_PATH_MAX + 64];
  char tmp_path[LSP_STATE_PATH_MAX + 80];
  FILE * f;
  size_t i;

  mkdir (dir, 0700);
  state_path (dir, state->pid, path, sizeof (path));
  snprintf (tmp_path, sizeof (tmp_path), "%s.tmp", path);

  f = fopen (tmp_path, "we");
  if (f == NULL)
    return -errno;

  fprintf (f, "pid=%d\n", (int) state->pid);
  fprintf (f, "payload_base=0x%llx\n", (unsigned long long) state->payload_base);
  fprintf (f, "payload_size=0x%llx\n", (unsigned long long) state->payload_size);
  fprintf (f, "payload_protection=0x%llx\n", (unsigned long long) state->payload_protection);
  fprintf (f, "payload_path=%s\n", state->payload_path);
  fprintf (f, "payload_file_offset=0x%llx\n", (unsigned long long) state->payload_file_offset);

  if (state->payload_original != NULL && state->payload_original_size != 0)
  {
    size_t hex_len = state->payload_original_size * 2;
    char * hex = malloc (hex_len + 1);

    if (hex == NULL)
    {
      fclose (f);
      unlink (tmp_path);
      return -ENOMEM;
    }
    hex_encode (state->payload_original, state->payload_original_size, hex);
    fprintf (f, "payload_original=%s\n", hex);
    free (hex);
  }

  for (i = 0; i != state->slot_count; i++)
  {
    char original_hex[17];
    char replacement_hex[17];

    hex_encode (state->slots[i].original, state->slots[i].length, original_hex);
    hex_encode (state->slots[i].replacement, state->slots[i].length, replacement_hex);
    fprintf (f, "slot=0x%llx:%s:%s\n",
        (unsigned long long) state->slots[i].address, original_hex, replacement_hex);
  }

  if (fclose (f) != 0)
  {
    unlink (tmp_path);
    return -errno;
  }

  if (rename (tmp_path, path) != 0)
  {
    int err = errno;
    unlink (tmp_path);
    return -err;
  }

  return 0;
}

int
lsp_state_load (const char * dir, pid_t pid, LspPatchState * out)
{
  char path[LSP_STATE_PATH_MAX + 64];
  char line[16384];
  FILE * f;

  memset (out, 0, sizeof (*out));
  out->pid = pid;

  state_path (dir, pid, path, sizeof (path));
  f = fopen (path, "re");
  if (f == NULL)
    return -errno;

  while (fgets (line, sizeof (line), f) != NULL)
  {
    char * newline = strchr (line, '\n');

    if (newline != NULL)
      *newline = '\0';

    if (strncmp (line, "payload_base=", 13) == 0)
      out->payload_base = strtoull (line + 13, NULL, 0);
    else if (strncmp (line, "payload_size=", 13) == 0)
      out->payload_size = strtoull (line + 13, NULL, 0);
    else if (strncmp (line, "payload_protection=", 19) == 0)
      out->payload_protection = strtoull (line + 19, NULL, 0);
    else if (strncmp (line, "payload_path=", 13) == 0)
      snprintf (out->payload_path, sizeof (out->payload_path), "%.*s",
          (int) sizeof (out->payload_path) - 1, line + 13);
    else if (strncmp (line, "payload_file_offset=", 20) == 0)
      out->payload_file_offset = strtoull (line + 20, NULL, 0);
    else if (strncmp (line, "payload_original=", 17) == 0)
    {
      size_t hex_len = strlen (line + 17);
      size_t size = hex_len / 2;

      out->payload_original = malloc (size == 0 ? 1 : size);
      if (out->payload_original == NULL)
      {
        fclose (f);
        lsp_state_clear (out);
        return -ENOMEM;
      }
      if (hex_decode (line + 17, out->payload_original, size, &out->payload_original_size) != 0)
      {
        fclose (f);
        lsp_state_clear (out);
        return -EINVAL;
      }
    }
    else if (strncmp (line, "slot=", 5) == 0)
    {
      char * first_colon;
      char * second_colon;
      LspPatchSlot * slot;

      if (out->slot_count >= LSP_STATE_MAX_SLOTS)
        continue;

      slot = &out->slots[out->slot_count];
      first_colon = strchr (line + 5, ':');
      if (first_colon == NULL)
        continue;
      second_colon = strchr (first_colon + 1, ':');
      if (second_colon == NULL)
        continue;

      *first_colon = '\0';
      *second_colon = '\0';

      slot->address = strtoull (line + 5, NULL, 0);
      if (hex_decode (first_colon + 1, slot->original, sizeof (slot->original), &slot->length) != 0)
        continue;
      {
        size_t replacement_len = 0;

        if (hex_decode (second_colon + 1, slot->replacement,
                sizeof (slot->replacement), &replacement_len) != 0)
          continue;
        if (replacement_len != slot->length)
          continue;
      }
      out->slot_count++;
    }
  }

  fclose (f);

  if (out->payload_base == 0)
    return -EINVAL;

  return 0;
}

int
lsp_state_remove (const char * dir, pid_t pid)
{
  char path[LSP_STATE_PATH_MAX + 64];

  state_path (dir, pid, path, sizeof (path));
  if (unlink (path) != 0 && errno != ENOENT)
    return -errno;

  return 0;
}

void
lsp_state_clear (LspPatchState * state)
{
  free (state->payload_original);
  state->payload_original = NULL;
  state->payload_original_size = 0;
  state->slot_count = 0;
}

int
lsp_state_list (const char * dir, pid_t ** pids, size_t * count)
{
  DIR * d;
  struct dirent * entry;
  size_t capacity = 16;

  *pids = malloc (capacity * sizeof (pid_t));
  *count = 0;
  if (*pids == NULL)
    return -ENOMEM;

  d = opendir (dir);
  if (d == NULL)
  {
    if (errno == ENOENT)
      return 0;
    free (*pids);
    *pids = NULL;
    return -errno;
  }

  while ((entry = readdir (d)) != NULL)
  {
    char * dot;
    long pid;

    dot = strrchr (entry->d_name, '.');
    if (dot == NULL || strcmp (dot, ".patch") != 0)
      continue;

    pid = strtol (entry->d_name, NULL, 10);
    if (pid <= 0)
      continue;

    if (*count == capacity)
    {
      pid_t * grown;

      capacity *= 2;
      grown = realloc (*pids, capacity * sizeof (pid_t));
      if (grown == NULL)
      {
        closedir (d);
        free (*pids);
        *pids = NULL;
        return -ENOMEM;
      }
      *pids = grown;
    }

    (*pids)[(*count)++] = (pid_t) pid;
  }

  closedir (d);
  return 0;
}
