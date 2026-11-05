/*
 * lspfrifa-zygote — root injector / daemon for the LSPFRIDA zygote gate.
 *
 * Semantics ported from frida-core src/linux/linux-host-session.vala
 * (RoboLauncher, commit d5bc4840) with the LSPFRIDA adaptations frozen in
 * docs/Zygote-Injection-Design.md §3.4:
 *
 *   - proc maps snapshot, payload page = last page of the executable
 *     /libstagefright.so mapping (fallback /libmedia.so);
 *   - libc / libselinux / libandroid_runtime located by path suffix;
 *   - exports resolved through the ELF dynsym, imports through relocation
 *     slots (SHT_RELA/SHT_REL r_offset);
 *   - boot-heap + anonymous rw-p candidate scan with memmem to find the
 *     android.os.Process.setArgV0 function-pointer slot;
 *   - SIGSTOP + wait for /proc/<pid>/stat state 'T' + /proc/<pid>/mem pwrite
 *     + SIGCONT.  NO ptrace is used anywhere.
 *
 * Sandboxed-host note: this execution sandbox denies O_WRONLY on
 * /proc/<pid>/mem (syscall-level EACCES, even for /proc/self/mem).  When that
 * happens the injector falls back to process_vm_readv/process_vm_writev, which
 * the design's frozen /proc/pid/mem path is functionally equivalent to.  On a
 * rooted device /proc/pid/mem is writable and that fallback is never used.
 * This is documented in README.md and the deviation report.
 */
#define _GNU_SOURCE

#include "elf_parser.h"
#include "gate_context.h"
#include "payload.h"
#include "proc_maps.h"
#include "state.h"
#include "wire.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/uio.h>
#include <unistd.h>

#define EXIT_USAGE 2
#define EXIT_NOT_FOUND 3
#define EXIT_UNSUPPORTED 4
#define EXIT_PERMISSION 5

#define MAX_TARGETS 64
#define MAX_PENDING 64
#define MAX_HOLD_PACKAGES 32
#define SCAN_CHUNK_SIZE (4u * 1024u * 1024u)
#define SCAN_MAX_BYTES (1024ull * 1024ull * 1024ull)

static volatile sig_atomic_t g_interrupted = 0;

typedef struct
{
  char socket_name[128];
  char gate_path[PATH_MAX];
  char state_dir[PATH_MAX];
  char libc_match[256];
  char runtime_match[256];
  char selinux_match[256];
  char payload_match[256];
  char hold_packages[MAX_HOLD_PACKAGES][128];
  size_t hold_count;
  pid_t pid;
  bool all;
  bool serve;
  char positional[64];
} Options;

/* ------------------------------------------------------------------ */
/* small utilities                                                      */
/* ------------------------------------------------------------------ */

static void
on_signal (int sig)
{
  (void) sig;
  g_interrupted = 1;
}

static void
install_signal_handlers (void)
{
  struct sigaction sa;

  memset (&sa, 0, sizeof (sa));
  sa.sa_handler = on_signal;
  sigemptyset (&sa.sa_mask);
  sigaction (SIGINT, &sa, NULL);
  sigaction (SIGTERM, &sa, NULL);
}

static int
write_all (int fd, const void * data, size_t len)
{
  const unsigned char * cursor = data;
  size_t remaining = len;

  while (remaining != 0)
  {
    ssize_t n = write (fd, cursor, remaining);
    if (n < 0)
    {
      if (errno == EINTR)
        continue;
      return -errno;
    }
    cursor += n;
    remaining -= (size_t) n;
  }

  return 0;
}

static int
read_proc_stat_state (pid_t pid, char * state_out)
{
  char path[64];
  char buffer[1024];
  ssize_t n;
  int fd;
  char * close_paren;
  const char * cursor;

  snprintf (path, sizeof (path), "/proc/%d/stat", (int) pid);
  fd = open (path, O_RDONLY | O_CLOEXEC);
  if (fd == -1)
    return -errno;

  n = read (fd, buffer, sizeof (buffer) - 1);
  close (fd);
  if (n <= 0)
    return -ESRCH;

  buffer[n] = '\0';
  close_paren = strrchr (buffer, ')');
  if (close_paren == NULL || close_paren[1] != ' ')
    return -EINVAL;

  cursor = close_paren + 2;
  if (*cursor == '\0')
    return -EINVAL;

  *state_out = *cursor;
  return 0;
}

static int
wait_until_stopped (pid_t pid, int timeout_ms)
{
  int waited = 0;

  while (waited < timeout_ms)
  {
    char state;

    if (read_proc_stat_state (pid, &state) != 0)
      return -ESRCH;

    if (state == 'T' || state == 't')
      return 0;

    usleep (2000);
    waited += 2;
  }

  return -ETIMEDOUT;
}

static const char *
process_comm (pid_t pid, char * out, size_t out_size)
{
  char path[64];
  ssize_t n;
  int fd;

  snprintf (path, sizeof (path), "/proc/%d/comm", (int) pid);
  fd = open (path, O_RDONLY | O_CLOEXEC);
  if (fd == -1)
    return NULL;

  n = read (fd, out, out_size - 1);
  close (fd);
  if (n <= 0)
    return NULL;

  out[n] = '\0';
  while (n > 0 && (out[n - 1] == '\n' || out[n - 1] == '\r'))
    out[--n] = '\0';

  return out;
}

static bool
is_zygote_name (const char * name)
{
  return strcmp (name, "zygote") == 0 || strcmp (name, "zygote64") == 0 ||
      strcmp (name, "usap32") == 0 || strcmp (name, "usap64") == 0;
}

static int
enumerate_targets (pid_t ** pids, size_t * count)
{
  DIR * proc;
  struct dirent * entry;
  size_t capacity = 16;
  size_t n = 0;
  pid_t * result;
  pid_t self = getpid ();

  result = malloc (capacity * sizeof (pid_t));
  if (result == NULL)
    return -ENOMEM;

  proc = opendir ("/proc");
  if (proc == NULL)
  {
    free (result);
    return -errno;
  }

  while ((entry = readdir (proc)) != NULL)
  {
    char * end;
    long value;
    char comm[64];

    value = strtol (entry->d_name, &end, 10);
    if (*entry->d_name == '\0' || *end != '\0' || value <= 0)
      continue;
    if ((pid_t) value == self)
      continue;

    if (process_comm ((pid_t) value, comm, sizeof (comm)) == NULL)
      continue;
    if (!is_zygote_name (comm))
      continue;

    if (n == capacity)
    {
      pid_t * grown;

      capacity *= 2;
      grown = realloc (result, capacity * sizeof (pid_t));
      if (grown == NULL)
      {
        closedir (proc);
        free (result);
        return -ENOMEM;
      }
      result = grown;
    }

    result[n++] = (pid_t) value;
  }

  closedir (proc);

  *pids = result;
  *count = n;
  return 0;
}

/* ------------------------------------------------------------------ */
/* process memory access (frozen /proc/pid/mem path + sandbox fallback) */
/* ------------------------------------------------------------------ */

typedef struct
{
  pid_t pid;
  int fd;
  int writable;
} ProcMem;

static int g_sandbox_fallback_warned = 0;

static int
proc_mem_open (ProcMem * mem, pid_t pid)
{
  char path[64];
  int primary_error;

  mem->pid = pid;
  mem->fd = -1;
  mem->writable = 0;

  snprintf (path, sizeof (path), "/proc/%d/mem", (int) pid);

  /* PRIMARY, device path: O_RDWR + pread/pwrite on /proc/<pid>/mem.
   * This is the frozen mechanism from design §3.1/§3.3 and what frida-core
   * uses on a rooted device. */
  mem->fd = open (path, O_RDWR | O_CLOEXEC);
  if (mem->fd != -1)
  {
    mem->writable = 1;
    return 0;
  }
  primary_error = errno;

  if (primary_error != EACCES && primary_error != EPERM)
    return -primary_error;

  /* SANDBOX-HOST FALLBACK ONLY.  Some hardened containers deny O_WRONLY on
   * /proc/<pid>/mem at the syscall level (EACCES even for /proc/self/mem).
   * Keep reads through the /proc fd (O_RDONLY) and send writes through
   * process_vm_writev, which has identical semantics.  On Android as root the
   * primary path above succeeds and this code is never reached. */
  mem->fd = open (path, O_RDONLY | O_CLOEXEC);
  if (mem->fd == -1)
    return -errno;

  if (!g_sandbox_fallback_warned)
  {
    fprintf (stderr,
        "WARN: /proc/%d/mem O_RDWR denied (%s); using process_vm_writev "
        "sandbox fallback (host test only; on Android the primary path is "
        "/proc/<pid>/mem pwrite)\n", (int) pid, strerror (primary_error));
    g_sandbox_fallback_warned = 1;
  }

  return 0;
}

static void
proc_mem_close (ProcMem * mem)
{
  if (mem->fd != -1)
    close (mem->fd);
  mem->fd = -1;
}

static ssize_t
proc_mem_read (ProcMem * mem, void * buffer, size_t length, uint64_t address)
{
  if (mem->fd != -1 && mem->writable)
  {
    ssize_t n = pread (mem->fd, buffer, length, (off_t) address);
    return n;
  }

  if (mem->fd != -1)
  {
    ssize_t n = pread (mem->fd, buffer, length, (off_t) address);
    if (n >= 0)
      return n;
    /* fall through to process_vm_readv when pread is refused */
  }

  {
    struct iovec local;
    struct iovec remote;

    local.iov_base = buffer;
    local.iov_len = length;
    remote.iov_base = (void *) (uintptr_t) address;
    remote.iov_len = length;
    return process_vm_readv (mem->pid, &local, 1, &remote, 1, 0);
  }
}

static ssize_t
proc_mem_write (ProcMem * mem, const void * buffer, size_t length, uint64_t address)
{
  if (mem->writable)
  {
    const unsigned char * cursor = buffer;
    size_t remaining = length;
    uint64_t where = address;

    while (remaining != 0)
    {
      ssize_t n = pwrite (mem->fd, cursor, remaining, (off_t) where);
      if (n < 0)
      {
        if (errno == EINTR)
          continue;
        return -errno;
      }
      cursor += n;
      remaining -= (size_t) n;
      where += (uint64_t) n;
    }

    return (ssize_t) length;
  }

  /* Sandboxed-host fallback.  Identical effect to /proc/pid/mem pwrite; the
   * target must grant access (root on device; PR_SET_PTRACER_ANY in tests). */
  {
    struct iovec local;
    struct iovec remote;

    local.iov_base = (void *) buffer;
    local.iov_len = length;
    remote.iov_base = (void *) (uintptr_t) address;
    remote.iov_len = length;
    return process_vm_writev (mem->pid, &local, 1, &remote, 1, 0);
  }
}

/* ------------------------------------------------------------------ */
/* gate patch preparation                                              */
/* ------------------------------------------------------------------ */

typedef struct
{
  pid_t pid;

  uint64_t payload_base;
  uint64_t payload_protection;
  char payload_path[LSP_STATE_PATH_MAX];
  uint64_t payload_file_offset;

  uint64_t original_setargv0;
  uint64_t original_setcontext;

  uint64_t setargv0_slot;
  uint64_t setcontext_slot;

  bool already_patched;

  LspPayload payload;
  LspPatchState state;
} ZygotePatch;

static void
zygote_patch_clear (ZygotePatch * patch)
{
  lsp_payload_free (&patch->payload);
  lsp_state_clear (&patch->state);
  memset (patch, 0, sizeof (*patch));
}

static const uint64_t *
scan_heap_candidates (ZygotePatch * patch, ProcMem * mem, const LspMaps * maps,
    const unsigned char * original_bytes, const unsigned char * replacement_bytes,
    size_t pointer_size, int * error)
{
  size_t i;
  uint64_t scanned = 0;

  *error = 0;

  for (i = 0; i != maps->count; i++)
  {
    const LspMap * candidate = &maps->items[i];
    uint64_t start;
    uint64_t size;

    if (!(lsp_map_is_boot_heap (candidate) || lsp_map_is_anon_rw (candidate)))
      continue;

    start = candidate->start;
    size = candidate->end - candidate->start;
    if (size == 0)
      continue;
    if (scanned + size > SCAN_MAX_BYTES)
      size = SCAN_MAX_BYTES - scanned;
    scanned += size;

    {
      unsigned char * chunk = malloc (SCAN_CHUNK_SIZE);
      uint64_t offset = 0;

      if (chunk == NULL)
      {
        *error = EXIT_UNSUPPORTED;
        return NULL;
      }

      while (offset < size)
      {
        size_t want = SCAN_CHUNK_SIZE;
        size_t overlap = pointer_size - 1;
        ssize_t n;
        size_t j;

        if (offset + want > size)
          want = (size_t) (size - offset);
        if (offset != 0)
          want = (size_t) (size - offset) > SCAN_CHUNK_SIZE ? SCAN_CHUNK_SIZE : (size_t) (size - offset);

        n = proc_mem_read (mem, chunk, want, start + offset);
        if (n <= 0)
          break;

        for (j = 0; j + pointer_size <= (size_t) n; j++)
        {
          if (memcmp (chunk + j, original_bytes, pointer_size) == 0)
          {
            patch->setargv0_slot = start + offset + j;
            patch->already_patched = false;
            free (chunk);
            return NULL;
          }
        }

        for (j = 0; j + pointer_size <= (size_t) n; j++)
        {
          if (memcmp (chunk + j, replacement_bytes, pointer_size) == 0)
          {
            patch->setargv0_slot = start + offset + j;
            patch->already_patched = true;
            free (chunk);
            return NULL;
          }
        }

        if ((size_t) n <= overlap)
          break;
        offset += (uint64_t) n - overlap;
      }

      free (chunk);
    }

    if (g_interrupted)
      break;
  }

  *error = EXIT_UNSUPPORTED;
  return NULL;
}

static int
prepare_patch (const Options * options, pid_t pid, ZygotePatch * patch)
{
  LspMaps maps;
  ProcMem mem;
  const LspMap * payload_map;
  const LspMap * libc_path_map;
  const LspMap * runtime_path_map;
  const LspMap * selinux_path_map;
  char libc_path[512];
  char runtime_path[512];
  char selinux_path[512];
  char log_path[512];
  uint64_t libc_funcs[LSP_PAYLOAD_LIBC_COUNT];
  const char * wanted[LSP_PAYLOAD_LIBC_COUNT] = {
    "mprotect", "strdup", "free", "socket", "connect", "__errno",
    "getpid", "getppid", "sendmsg", "recv", "close", "raise"
  };
  uint64_t setenv_addr;
  uint64_t log_write_addr = 0;
  uint64_t libc_base = 0;
  uint64_t runtime_base = 0;
  size_t page_size;
  int error;
  size_t i;

  memset (patch, 0, sizeof (*patch));
  patch->pid = pid;
  libc_path[0] = runtime_path[0] = selinux_path[0] = log_path[0] = '\0';
  setenv_addr = 0;

  error = lsp_maps_load (pid, &maps);
  if (error != 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot read /proc/%d/maps: %s\n",
        (int) pid, strerror (-error));
    return error == -ENOENT ? EXIT_NOT_FOUND : EXIT_PERMISSION;
  }

  page_size = lsp_page_size ();

  payload_map = lsp_maps_find_exec_suffix (&maps, options->payload_match);
  if (payload_map == NULL && strcmp (options->payload_match, "/libstagefright.so") == 0)
    payload_map = lsp_maps_find_exec_suffix (&maps, "/libmedia.so");
  if (payload_map == NULL)
  {
    fprintf (stderr, "lspfrifa-zygote: no executable mapping matching %s (pid %d)\n",
        options->payload_match, (int) pid);
    lsp_maps_free (&maps);
    return EXIT_UNSUPPORTED;
  }

  patch->payload_base = (payload_map->end - page_size);
  patch->payload_protection = 1 /* PROT_READ */ | 4 /* PROT_EXEC */;
  if (payload_map->writable)
    patch->payload_protection |= 2 /* PROT_WRITE */;
  {
    size_t plen;

    snprintf (patch->payload_path, sizeof (patch->payload_path), "%s", payload_map->path);
    plen = strlen (patch->payload_path);
    if (plen > 10 && strcmp (patch->payload_path + plen - 10, " (deleted)") == 0)
      patch->payload_path[plen - 10] = '\0';
  }
  patch->payload_file_offset =
      payload_map->offset + (patch->payload_base - payload_map->start);

  libc_path_map = lsp_maps_find_path_suffix (&maps, options->libc_match);
  runtime_path_map = lsp_maps_find_path_suffix (&maps, options->runtime_match);
  selinux_path_map = lsp_maps_find_path_suffix (&maps, options->selinux_match);

  if (libc_path_map == NULL || runtime_path_map == NULL)
  {
    fprintf (stderr,
        "lspfrifa-zygote: layout unsupported (libc=%s runtime=%s) in pid %d\n",
        libc_path_map == NULL ? "missing" : "ok",
        runtime_path_map == NULL ? "missing" : "ok", (int) pid);
    lsp_maps_free (&maps);
    return EXIT_UNSUPPORTED;
  }

  snprintf (libc_path, sizeof (libc_path), "%s", libc_path_map->path);
  snprintf (runtime_path, sizeof (runtime_path), "%s", runtime_path_map->path);
  if (selinux_path_map != NULL)
    snprintf (selinux_path, sizeof (selinux_path), "%s", selinux_path_map->path);

  /* libc: 12 frozen-order entry points */
  {
    LspElf libc_elf;
    const LspMap * libc_base_map;

    libc_base_map = lsp_maps_find_module_base (&maps, libc_path);
    if (libc_base_map == NULL || lsp_elf_open (libc_path, &libc_elf) != 0)
    {
      lsp_maps_free (&maps);
      return EXIT_UNSUPPORTED;
    }
    libc_base = libc_base_map->start;

    for (i = 0; i != LSP_PAYLOAD_LIBC_COUNT; i++)
    {
      uint64_t value = 0;
      bool found = lsp_elf_find_export (&libc_elf, wanted[i], &value);

      if (!found && strcmp (wanted[i], "__errno") == 0)
        found = lsp_elf_find_export (&libc_elf, "__errno_location", &value);

      if (!found)
      {
        fprintf (stderr,
            "lspfrifa-zygote: libc %s missing export %s\n", libc_path, wanted[i]);
        lsp_elf_close (&libc_elf);
        lsp_maps_free (&maps);
        return EXIT_UNSUPPORTED;
      }

      libc_funcs[i] = libc_base + value;
    }

    {
      uint64_t setenv_value = 0;
      if (lsp_elf_find_export (&libc_elf, "setenv", &setenv_value))
        setenv_addr = libc_base + setenv_value;
    }

    {
      const char * log_wanted[] = { "liblog.so", "__android_log_write" };
      const LspMap * log_path_map = lsp_maps_find_path_suffix (&maps, log_wanted[0]);

      if (log_path_map != NULL)
      {
        LspElf log_elf;
        const LspMap * log_base_map;

        snprintf (log_path, sizeof (log_path), "%s", log_path_map->path);
        log_base_map = lsp_maps_find_module_base (&maps, log_path);
        if (log_base_map != NULL && lsp_elf_open (log_path, &log_elf) == 0)
        {
          uint64_t value = 0;
          if (lsp_elf_find_export (&log_elf, log_wanted[1], &value))
            log_write_addr = log_base_map->start + value;
          lsp_elf_close (&log_elf);
        }
      }
    }

    lsp_elf_close (&libc_elf);
  }

  /* libandroid_runtime: setArgV0 export + selinux import slot */
  {
    LspElf runtime_elf;
    const LspMap * runtime_base_map;

    runtime_base_map = lsp_maps_find_module_base (&maps, runtime_path);
    if (runtime_base_map == NULL || lsp_elf_open (runtime_path, &runtime_elf) != 0)
    {
      fprintf (stderr, "lspfrifa-zygote: cannot parse %s\n", runtime_path);
      lsp_maps_free (&maps);
      return EXIT_UNSUPPORTED;
    }
    runtime_base = runtime_base_map->start;

    {
      uint64_t value = 0;
      if (!lsp_elf_find_export (&runtime_elf,
              "_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring", &value))
      {
        fprintf (stderr,
            "lspfrifa-zygote: unable to locate android.os.Process.setArgV0(); "
            "please file a bug (pid %d)\n", (int) pid);
        lsp_elf_close (&runtime_elf);
        lsp_maps_free (&maps);
        return EXIT_UNSUPPORTED;
      }
      patch->original_setargv0 = runtime_base + value;
    }

    if (selinux_path[0] != '\0')
    {
      const LspMap * selinux_base_map = lsp_maps_find_module_base (&maps, selinux_path);
      LspElf selinux_elf;

      if (selinux_base_map != NULL && lsp_elf_open (selinux_path, &selinux_elf) == 0)
      {
        uint64_t value = 0;
        if (lsp_elf_find_export (&selinux_elf, "selinux_android_setcontext", &value))
          patch->original_setcontext = selinux_base_map->start + value;
        lsp_elf_close (&selinux_elf);
      }

      {
        uint64_t slot = 0;
        if (lsp_elf_find_import_slot (&runtime_elf,
                "selinux_android_setcontext", &slot))
          patch->setcontext_slot = runtime_base + slot;
      }
    }

    lsp_elf_close (&runtime_elf);
  }

  /* payload template */
  error = lsp_payload_build (options->gate_path, options->socket_name,
      patch->payload_base, patch->payload_protection,
      patch->original_setcontext, patch->original_setargv0,
      libc_funcs, LSP_PAYLOAD_LIBC_COUNT, setenv_addr, log_write_addr,
      &patch->payload);
  if (error != 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot build payload from %s: %s\n",
        options->gate_path, strerror (-error));
    lsp_maps_free (&maps);
    return EXIT_UNSUPPORTED;
  }

  if (patch->payload.size > page_size)
  {
    fprintf (stderr,
        "lspfrifa-zygote: gate payload is %zu bytes, larger than page size %zu\n",
        patch->payload.size, page_size);
    zygote_patch_clear (patch);
    lsp_maps_free (&maps);
    return EXIT_UNSUPPORTED;
  }

  error = proc_mem_open (&mem, pid);
  if (error != 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot open /proc/%d/mem: %s\n",
        (int) pid, strerror (-error));
    zygote_patch_clear (patch);
    lsp_maps_free (&maps);
    return error == -EACCES || error == -EPERM ? EXIT_PERMISSION : EXIT_NOT_FOUND;
  }

  /* locate the setArgV0 function-pointer slot in the heap */
  {
    unsigned char original_bytes[8];
    unsigned char replacement_bytes[8];
    uint64_t original_value = patch->original_setargv0;
    uint64_t replacement_value = patch->payload.entry_setargv0;

    memcpy (original_bytes, &original_value, sizeof (original_bytes));
    memcpy (replacement_bytes, &replacement_value, sizeof (replacement_bytes));

    scan_heap_candidates (patch, &mem, &maps, original_bytes, replacement_bytes,
        sizeof (uint64_t), &error);
    if (patch->setargv0_slot == 0)
    {
      fprintf (stderr,
          "lspfrifa-zygote: unable to locate android.os.Process.setArgV0() slot "
          "in pid %d; please file a bug\n", (int) pid);
      proc_mem_close (&mem);
      zygote_patch_clear (patch);
      lsp_maps_free (&maps);
      return EXIT_UNSUPPORTED;
    }
  }

  /* fill state: original payload page bytes + slot originals */
  patch->state.pid = pid;
  patch->state.payload_base = patch->payload_base;
  patch->state.payload_size = patch->payload.size;
  patch->state.payload_protection = patch->payload_protection;
  snprintf (patch->state.payload_path, sizeof (patch->state.payload_path), "%s",
      patch->payload_path);
  patch->state.payload_file_offset = patch->payload_file_offset;

  if (!patch->already_patched)
  {
    patch->state.payload_original = malloc (patch->payload.size);
    if (patch->state.payload_original == NULL)
    {
      proc_mem_close (&mem);
      zygote_patch_clear (patch);
      lsp_maps_free (&maps);
      return EXIT_UNSUPPORTED;
    }
    patch->state.payload_original_size = patch->payload.size;
    if (proc_mem_read (&mem, patch->state.payload_original, patch->payload.size,
            patch->payload_base) != (ssize_t) patch->payload.size)
    {
      fprintf (stderr, "lspfrifa-zygote: cannot snapshot payload page in pid %d\n",
          (int) pid);
      proc_mem_close (&mem);
      zygote_patch_clear (patch);
      lsp_maps_free (&maps);
      return EXIT_PERMISSION;
    }
  }
  else
  {
    /* already patched: recover the true original page bytes from the state
     * file, else from the backing file at the mapping's file offset. */
    LspPatchState previous;

    if (lsp_state_load (options->state_dir, pid, &previous) == 0 &&
        previous.payload_original != NULL &&
        previous.payload_original_size == patch->payload.size)
    {
      patch->state.payload_original = previous.payload_original;
      previous.payload_original = NULL;
      patch->state.payload_original_size = patch->payload.size;
      lsp_state_clear (&previous);
    }
    else
    {
      int fd = open (patch->payload_path, O_RDONLY | O_CLOEXEC);

      patch->state.payload_original = malloc (patch->payload.size);
      if (fd == -1 || patch->state.payload_original == NULL)
      {
        fprintf (stderr,
            "lspfrifa-zygote: already patched but original payload bytes are "
            "unavailable (state/backing file) for pid %d\n", (int) pid);
        if (fd != -1)
          close (fd);
        proc_mem_close (&mem);
        zygote_patch_clear (patch);
        lsp_maps_free (&maps);
        return EXIT_UNSUPPORTED;
      }
      if (pread (fd, patch->state.payload_original, patch->payload.size,
              (off_t) patch->payload_file_offset) != (ssize_t) patch->payload.size)
      {
        fprintf (stderr,
            "lspfrifa-zygote: cannot read original payload page from %s\n",
            patch->payload_path);
        close (fd);
        proc_mem_close (&mem);
        zygote_patch_clear (patch);
        lsp_maps_free (&maps);
        return EXIT_UNSUPPORTED;
      }
      close (fd);
      patch->state.payload_original_size = patch->payload.size;
    }
  }

  /* slot records */
  {
    LspPatchSlot * slot = &patch->state.slots[patch->state.slot_count++];
    uint64_t original_value = patch->original_setargv0;
    uint64_t replacement_value = patch->payload.entry_setargv0;

    slot->address = patch->setargv0_slot;
    slot->length = sizeof (uint64_t);
    memcpy (slot->original, &original_value, slot->length);
    memcpy (slot->replacement, &replacement_value, slot->length);

    if (patch->setcontext_slot != 0 && patch->original_setcontext != 0)
    {
      slot = &patch->state.slots[patch->state.slot_count++];
      slot->address = patch->setcontext_slot;
      slot->length = sizeof (uint64_t);
      memcpy (slot->original, &patch->original_setcontext, slot->length);
      memcpy (slot->replacement, &patch->payload.entry_setcontext, slot->length);
    }
  }

  proc_mem_close (&mem);
  lsp_maps_free (&maps);
  return 0;
}

static int
apply_patch (const Options * options, ZygotePatch * patch)
{
  ProcMem mem;
  int waited;
  int error;
  size_t i;

  if (kill (patch->pid, SIGSTOP) != 0)
    return EXIT_NOT_FOUND;

  waited = wait_until_stopped (patch->pid, 2000);
  if (waited != 0)
  {
    kill (patch->pid, SIGCONT);
    fprintf (stderr, "lspfrifa-zygote: pid %d did not stop (%s)\n",
        (int) patch->pid, strerror (-waited));
    return EXIT_NOT_FOUND;
  }

  error = proc_mem_open (&mem, patch->pid);
  if (error != 0)
  {
    kill (patch->pid, SIGCONT);
    return error == -EACCES || error == -EPERM ? EXIT_PERMISSION : EXIT_NOT_FOUND;
  }

  if (proc_mem_write (&mem, patch->payload.bytes, patch->payload.size,
          patch->payload_base) != (ssize_t) patch->payload.size)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot write payload to pid %d: %s\n",
        (int) patch->pid, strerror (errno));
    proc_mem_close (&mem);
    kill (patch->pid, SIGCONT);
    return EXIT_PERMISSION;
  }

  for (i = 0; i != patch->state.slot_count; i++)
  {
    LspPatchSlot * slot = &patch->state.slots[i];

    if (proc_mem_write (&mem, slot->replacement, slot->length, slot->address) !=
        (ssize_t) slot->length)
    {
      fprintf (stderr, "lspfrifa-zygote: cannot write slot 0x%llx in pid %d: %s\n",
          (unsigned long long) slot->address, (int) patch->pid, strerror (errno));
      proc_mem_close (&mem);
      kill (patch->pid, SIGCONT);
      return EXIT_PERMISSION;
    }
  }

  proc_mem_close (&mem);

  if (lsp_state_save (options->state_dir, &patch->state) != 0)
    fprintf (stderr, "lspfrifa-zygote: warning: cannot persist state for pid %d\n",
        (int) patch->pid);

  kill (patch->pid, SIGCONT);
  return 0;
}

/* revert slots + payload page; caller must ensure the target is stopped when
 * required (we stop/continue it ourselves unless already stopped). */
static int
revert_state_on_pid (pid_t pid, const LspPatchState * state, bool stop_target)
{
  ProcMem mem;
  bool stopped = false;
  size_t i;
  int rc = 0;

  if (stop_target)
  {
    char current;
    if (read_proc_stat_state (pid, &current) == 0 && (current == 'T' || current == 't'))
      stopped = true;
    else
    {
      if (kill (pid, SIGSTOP) != 0)
        return -ESRCH;
      if (wait_until_stopped (pid, 2000) == 0)
        stopped = true;
    }
  }

  if (proc_mem_open (&mem, pid) != 0)
  {
    if (stopped)
      kill (pid, SIGCONT);
    return -EPERM;
  }

  if (state->payload_original != NULL && state->payload_size != 0)
  {
    if (proc_mem_write (&mem, state->payload_original, (size_t) state->payload_size,
            state->payload_base) != (ssize_t) state->payload_size)
      rc = -EIO;
  }
  else if (state->payload_path[0] != '\0' && state->payload_size != 0)
  {
    int fd = open (state->payload_path, O_RDONLY | O_CLOEXEC);

    if (fd == -1)
      rc = -errno;
    else
    {
      unsigned char * page = malloc ((size_t) state->payload_size);

      if (page == NULL)
        rc = -ENOMEM;
      else
      {
        if (pread (fd, page, (size_t) state->payload_size,
                (off_t) state->payload_file_offset) != (ssize_t) state->payload_size)
          rc = -EIO;
        else if (proc_mem_write (&mem, page, (size_t) state->payload_size,
                state->payload_base) != (ssize_t) state->payload_size)
          rc = -EIO;
        free (page);
      }
      close (fd);
    }
  }

  for (i = 0; i != state->slot_count; i++)
  {
    const LspPatchSlot * slot = &state->slots[i];

    if (proc_mem_write (&mem, slot->original, slot->length, slot->address) !=
        (ssize_t) slot->length)
      rc = -EIO;
  }

  proc_mem_close (&mem);

  if (stopped)
    kill (pid, SIGCONT);

  return rc;
}

static int
revert_pid_from_state (const Options * options, pid_t pid)
{
  LspPatchState state;
  int error;

  error = lsp_state_load (options->state_dir, pid, &state);
  if (error != 0)
  {
    fprintf (stderr, "lspfrifa-zygote: no state for pid %d in %s\n",
        (int) pid, options->state_dir);
    return EXIT_NOT_FOUND;
  }

  error = revert_state_on_pid (pid, &state, true);
  if (error == 0)
  {
    lsp_state_remove (options->state_dir, pid);
    printf ("{\"event\":\"revert\",\"pid\":%d,\"ok\":true}\n", (int) pid);
  }
  else
    fprintf (stderr, "lspfrifa-zygote: revert of pid %d failed: %s\n",
        (int) pid, strerror (-error));

  lsp_state_clear (&state);
  fflush (stdout);
  return error == 0 ? 0 : EXIT_PERMISSION;
}

/* ------------------------------------------------------------------ */
/* daemon                                                              */
/* ------------------------------------------------------------------ */

typedef struct
{
  ZygotePatch patch;
  bool used;
} PatchEntry;

static PatchEntry g_patches[MAX_TARGETS];

typedef struct
{
  pid_t pid;
  pid_t ppid;
  char name[LSP_WIRE_NAME_MAX];
  int fd;
} PendingGate;

static PendingGate g_pending[MAX_PENDING];
static size_t g_pending_count = 0;

static const LspPatchState *
find_patch_for_zygote (const Options * options, pid_t zygote_pid)
{
  size_t i;
  static LspPatchState loaded;
  static pid_t loaded_pid = 0;

  for (i = 0; i != MAX_TARGETS; i++)
  {
    if (g_patches[i].used && g_patches[i].patch.pid == zygote_pid)
      return &g_patches[i].patch.state;
  }

  if (loaded_pid != 0 && loaded_pid != zygote_pid)
    lsp_state_clear (&loaded);

  if (loaded_pid != zygote_pid)
  {
    if (lsp_state_load (options->state_dir, zygote_pid, &loaded) != 0)
      return NULL;
    loaded_pid = zygote_pid;
  }

  return &loaded;
}

static bool
package_is_held (const Options * options, const char * process_name)
{
  char package[256];
  const char * colon;
  size_t i;

  snprintf (package, sizeof (package), "%.*s", (int) sizeof (package) - 1, process_name);
  colon = strchr (package, ':');
  if (colon != NULL)
    ((char *) colon)[0] = '\0';

  for (i = 0; i != options->hold_count; i++)
  {
    if (strcmp (options->hold_packages[i], package) == 0)
      return true;
  }

  return false;
}

static void
print_gate_hit (const LspHello * hello, bool ack, bool held, bool reverted)
{
  char escaped[LSP_WIRE_NAME_MAX * 2];

  lsp_wire_json_escape (hello->name, escaped, sizeof (escaped));
  printf ("{\"event\":\"gate_hit\",\"pid\":%u,\"ppid\":%u,\"name\":\"%s\","
      "\"ack\":%s,\"held\":%s,\"reverted\":%s}\n",
      hello->pid, hello->ppid, escaped,
      ack ? "true" : "false",
      held ? "true" : "false",
      reverted ? "true" : "false");
  fflush (stdout);
}

/* Revert the *child* patches (inherited from the zygote) and SIGCONT it. */
static bool
release_child (const Options * options, const LspHello * hello, bool acked)
{
  const LspPatchState * zygote_state;
  bool reverted = false;

  zygote_state = find_patch_for_zygote (options, (pid_t) hello->ppid);
  if (zygote_state != NULL)
  {
    int waited = wait_until_stopped ((pid_t) hello->pid, 5000);
    if (acked && waited == 0)
    {
      if (revert_state_on_pid ((pid_t) hello->pid, zygote_state, false) == 0)
        reverted = true;
    }
    if (waited == 0)
      kill ((pid_t) hello->pid, SIGCONT);
  }
  else if (acked)
  {
    if (wait_until_stopped ((pid_t) hello->pid, 5000) == 0)
      kill ((pid_t) hello->pid, SIGCONT);
  }

  return reverted;
}

static int
handle_hello (const Options * options, int fd)
{
  LspHello hello;
  bool held;
  bool acknowledged = false;
  bool reverted = false;
  int error;

  error = lsp_wire_read_hello (fd, &hello);
  if (error != 0)
  {
    close (fd);
    return error;
  }

  held = package_is_held (options, hello.name);

  if (!held)
  {
    if (lsp_wire_send_ack (fd) == 0)
      acknowledged = true;
    reverted = release_child (options, &hello, acknowledged);
    close (fd);
    print_gate_hit (&hello, acknowledged, false, reverted);
    return 0;
  }

  print_gate_hit (&hello, false, true, false);

  if (g_pending_count == MAX_PENDING)
  {
    close (fd);
    return -ENOSPC;
  }

  g_pending[g_pending_count].pid = (pid_t) hello.pid;
  g_pending[g_pending_count].ppid = (pid_t) hello.ppid;
  snprintf (g_pending[g_pending_count].name, sizeof (g_pending[g_pending_count].name),
      "%s", hello.name);
  g_pending[g_pending_count].fd = fd;
  g_pending_count++;

  return 0;
}

static int
handle_control_command (const Options * options, int fd)
{
  char line[256];
  size_t got = 0;
  ssize_t n;
  int result;

  while (got + 1 < sizeof (line))
  {
    n = read (fd, line + got, sizeof (line) - 1 - got);
    if (n <= 0)
      break;
    got += (size_t) n;
    if (memchr (line, '\n', got) != NULL)
      break;
  }
  line[got] = '\0';

  if (strncmp (line, "resume ", 7) == 0)
  {
    long pid = strtol (line + 7, NULL, 10);
    size_t i;
    bool found = false;

    for (i = 0; i != g_pending_count; i++)
    {
      PendingGate * pending = &g_pending[i];

      if (pending->pid != (pid_t) pid)
        continue;

      {
        LspHello hello;
        bool acknowledged;
        bool reverted;

        memset (&hello, 0, sizeof (hello));
        hello.pid = (uint32_t) pending->pid;
        hello.ppid = (uint32_t) pending->ppid;
        hello.name_len = (uint32_t) strlen (pending->name);
        snprintf (hello.name, sizeof (hello.name), "%s", pending->name);

        acknowledged = lsp_wire_send_ack (pending->fd) == 0;
        reverted = release_child (options, &hello, acknowledged);
        close (pending->fd);

        printf ("{\"event\":\"gate_resume\",\"pid\":%d,\"ppid\":%d,\"name\":\"%s\","
            "\"ack\":%s,\"held\":true,\"reverted\":%s}\n",
            (int) hello.pid, (int) hello.ppid, hello.name,
            acknowledged ? "true" : "false", reverted ? "true" : "false");
        fflush (stdout);
      }

      g_pending[i] = g_pending[g_pending_count - 1];
      g_pending_count--;
      found = true;
      break;
    }

    result = found ? 0 : EXIT_NOT_FOUND;
    if (found)
      write_all (fd, "ok\n", 3);
    else
      write_all (fd, "err not-found\n", 14);
  }
  else
  {
    write_all (fd, "err usage\n", 10);
    result = EXIT_USAGE;
  }

  close (fd);
  return result;
}

static void
revert_all_patches (const Options * options)
{
  size_t i;

  for (i = 0; i != MAX_TARGETS; i++)
  {
    PatchEntry * entry = &g_patches[i];

    if (!entry->used)
      continue;

    if (revert_state_on_pid (entry->patch.pid, &entry->patch.state, true) == 0)
    {
      lsp_state_remove (options->state_dir, entry->patch.pid);
      printf ("{\"event\":\"revert\",\"pid\":%d,\"ok\":true}\n",
          (int) entry->patch.pid);
    }
    else
      fprintf (stderr, "lspfrifa-zygote: revert of zygote pid %d failed\n",
          (int) entry->patch.pid);

    zygote_patch_clear (&entry->patch);
    entry->used = false;
  }

  while (g_pending_count != 0)
  {
    g_pending_count--;
    kill (g_pending[g_pending_count].pid, SIGCONT);
    close (g_pending[g_pending_count].fd);
  }

  fflush (stdout);
}

static int
serve_loop (const Options * options)
{
  int listener;
  int control_listener;
  char control_name[160];
  struct pollfd fds[2 + MAX_PENDING];
  size_t indexed_pending[MAX_PENDING];
  int result = 0;

  listener = lsp_wire_listen (options->socket_name);
  if (listener < 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot listen on abstract socket %s: %s\n",
        options->socket_name, strerror (-listener));
    return EXIT_PERMISSION;
  }

  snprintf (control_name, sizeof (control_name), "%s-ctl", options->socket_name);
  control_listener = lsp_wire_listen (control_name);
  if (control_listener < 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot listen on control socket %s: %s\n",
        control_name, strerror (-control_listener));
    close (listener);
    return EXIT_PERMISSION;
  }

  printf ("{\"event\":\"serve\",\"socket\":\"%s\",\"control\":\"%s\","
      "\"state\":\"%s\"}\n", options->socket_name, control_name,
      options->state_dir);
  fflush (stdout);

  while (!g_interrupted)
  {
    size_t i;
    size_t count;

    fds[0].fd = listener;
    fds[0].events = POLLIN;
    fds[0].revents = 0;
    fds[1].fd = control_listener;
    fds[1].events = POLLIN;
    fds[1].revents = 0;

    count = 2;
    for (i = 0; i != g_pending_count; i++)
    {
      fds[count].fd = g_pending[i].fd;
      fds[count].events = POLLIN;
      fds[count].revents = 0;
      indexed_pending[i] = count;
      count++;
    }

    if (poll (fds, count, 250) < 0)
    {
      if (errno == EINTR)
        continue;
      result = EXIT_PERMISSION;
      break;
    }

    if (fds[0].revents & POLLIN)
    {
      int conn = accept4 (listener, NULL, NULL, SOCK_CLOEXEC);
      if (conn != -1)
        handle_hello (options, conn);
      /* handle_hello() may add a pending entry; the poll arrays were built for
       * the old table, so rebuild them before touching pending fds. */
      continue;
    }

    if (fds[1].revents & POLLIN)
    {
      int conn = accept4 (control_listener, NULL, NULL, SOCK_CLOEXEC);
      if (conn != -1)
        handle_control_command (options, conn);
      /* the pending table may have shrunk; rebuild poll state next round */
      continue;
    }

    for (i = 0; i != g_pending_count; i++)
    {
      int slot = (int) indexed_pending[i];
      if (slot < (int) count && (fds[slot].revents & (POLLIN | POLLHUP | POLLERR)))
      {
        /* gate side closed without resume: drop the pending entry */
        close (g_pending[i].fd);
        g_pending[i] = g_pending[g_pending_count - 1];
        indexed_pending[i] = indexed_pending[g_pending_count - 1];
        g_pending_count--;
        i--;
      }
    }
  }

  close (listener);
  close (control_listener);
  return result;
}

/* ------------------------------------------------------------------ */
/* commands                                                            */
/* ------------------------------------------------------------------ */

static void
print_targets (void)
{
  pid_t * pids;
  size_t count;
  size_t i;

  if (enumerate_targets (&pids, &count) != 0)
    return;

  for (i = 0; i != count; i++)
  {
    char comm[64];
    if (process_comm (pids[i], comm, sizeof (comm)) != NULL)
      printf ("%d %s\n", (int) pids[i], comm);
  }

  free (pids);
}

static int
inject_one (const Options * options, pid_t pid)
{
  size_t i;
  ZygotePatch patch;
  int error;

  for (i = 0; i != MAX_TARGETS; i++)
  {
    if (g_patches[i].used && g_patches[i].patch.pid == pid)
    {
      fprintf (stderr, "lspfrifa-zygote: pid %d already patched by this process\n",
          (int) pid);
      return 0;
    }
  }

  error = prepare_patch (options, pid, &patch);
  if (error != 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot prepare injection for pid %d\n",
        (int) pid);
    return error;
  }

  error = apply_patch (options, &patch);
  if (error != 0)
  {
    zygote_patch_clear (&patch);
    return error;
  }

  for (i = 0; i != MAX_TARGETS; i++)
  {
    if (!g_patches[i].used)
    {
      g_patches[i].used = true;
      g_patches[i].patch = patch;
      break;
    }
  }

  printf ("{\"event\":\"patch\",\"pid\":%d,\"payload_base\":\"0x%llx\","
      "\"payload_size\":%zu,\"setargv0_slot\":\"0x%llx\","
      "\"setcontext_slot\":\"0x%llx\",\"already_patched\":%s}\n",
      (int) pid, (unsigned long long) patch.payload_base, patch.payload.size,
      (unsigned long long) patch.setargv0_slot,
      (unsigned long long) patch.setcontext_slot,
      patch.already_patched ? "true" : "false");
  fflush (stdout);

  return 0;
}

static int
command_inject (const Options * options)
{
  pid_t targets[MAX_TARGETS];
  size_t target_count = 0;
  size_t i;
  int exit_code = 0;
  int injected = 0;

  if (options->pid > 0)
  {
    targets[0] = options->pid;
    target_count = 1;
  }
  else if (options->all)
  {
    pid_t * found = NULL;

    if (enumerate_targets (&found, &target_count) != 0)
      return EXIT_NOT_FOUND;
    if (target_count > MAX_TARGETS)
      target_count = MAX_TARGETS;
    memcpy (targets, found, target_count * sizeof (pid_t));
    free (found);
  }
  else
  {
    fprintf (stderr, "lspfrifa-zygote: inject requires --pid N or --all\n");
    return EXIT_USAGE;
  }

  if (target_count == 0)
  {
    fprintf (stderr, "lspfrifa-zygote: no zygote process found\n");
    return EXIT_NOT_FOUND;
  }

  for (i = 0; i != target_count; i++)
  {
    int result = inject_one (options, targets[i]);

    if (result == 0)
      injected++;
    else
      exit_code = result;
  }

  if (injected == 0 && exit_code == 0)
    exit_code = EXIT_NOT_FOUND;

  if (options->serve)
  {
    install_signal_handlers ();
    {
      int serve_result = serve_loop (options);
      revert_all_patches (options);
      if (serve_result != 0 && exit_code == 0)
        exit_code = serve_result;
    }
  }

  return exit_code;
}

static int
command_serve (const Options * options)
{
  int result;

  install_signal_handlers ();
  result = serve_loop (options);
  revert_all_patches (options);
  return result;
}

static int
command_revert (const Options * options)
{
  pid_t * pids = NULL;
  size_t count = 0;
  size_t i;
  int result = 0;
  int reverted = 0;

  if (options->pid > 0)
  {
    pid_t one = options->pid;
    pids = malloc (sizeof (pid_t));
    if (pids != NULL)
    {
      pids[0] = one;
      count = 1;
    }
  }
  else if (options->all)
  {
    if (lsp_state_list (options->state_dir, &pids, &count) != 0)
      return EXIT_NOT_FOUND;
  }
  else
  {
    fprintf (stderr, "lspfrifa-zygote: revert requires --pid N or --all\n");
    return EXIT_USAGE;
  }

  for (i = 0; i != count; i++)
  {
    int current = revert_pid_from_state (options, pids[i]);
    if (current == 0)
      reverted++;
    else if (result == 0)
      result = current;
  }

  free (pids);

  if (options->all && reverted == 0)
  {
    fprintf (stderr, "lspfrifa-zygote: no state files in %s\n",
        options->state_dir);
    return EXIT_NOT_FOUND;
  }

  return result;
}

static int
command_resume (const Options * options, const char * pid_text)
{
  char control_name[160];
  int fd;
  int error;
  char reply[64];
  ssize_t n;

  if (pid_text == NULL)
    return EXIT_USAGE;

  snprintf (control_name, sizeof (control_name), "%s-ctl", options->socket_name);
  fd = lsp_wire_connect_timeout (control_name, 1000);
  if (fd < 0)
  {
    fprintf (stderr, "lspfrifa-zygote: cannot reach daemon on %s: %s\n",
        control_name, strerror (-fd));
    return EXIT_NOT_FOUND;
  }

  {
    char command[64];
    int len = snprintf (command, sizeof (command), "resume %s\n", pid_text);
    if (write_all (fd, command, (size_t) len) != 0)
    {
      close (fd);
      return EXIT_PERMISSION;
    }
  }

  n = read (fd, reply, sizeof (reply) - 1);
  close (fd);
  if (n <= 0)
    return EXIT_NOT_FOUND;
  reply[n] = '\0';

  if (strncmp (reply, "ok", 2) == 0)
  {
    printf ("{\"event\":\"resume\",\"pid\":%s,\"ok\":true}\n", pid_text);
    return 0;
  }

  error = EXIT_NOT_FOUND;
  fprintf (stderr, "lspfrifa-zygote: resume failed: %s", reply);
  return error;
}

static int
command_self_test (const Options * options)
{
  int failures = 0;

#define SELF_CHECK(name, condition) \
  do { \
    if (condition) \
      printf ("PASS self-test/%s\n", name); \
    else \
    { \
      printf ("FAIL self-test/%s\n", name); \
      failures++; \
    } \
  } while (0)

  {
    LspMaps maps;
    SELF_CHECK ("proc_maps", lsp_maps_load (getpid (), &maps) == 0 && maps.count > 0);
    if (maps.items != NULL)
    {
      size_t i;
      bool found_libc = false;
      for (i = 0; i != maps.count; i++)
      {
        if (lsp_map_ends_with (maps.items[i].path, "libc.so.6") ||
            lsp_map_ends_with (maps.items[i].path, "/libc.so"))
          found_libc = true;
      }
      SELF_CHECK ("proc_maps/libc", found_libc);
      lsp_maps_free (&maps);
    }
  }

  {
    LspElf elf;
    const char * candidates[] = {
      "/lib/x86_64-linux-gnu/libc.so.6", "/usr/lib/x86_64-linux-gnu/libc.so.6",
      "/lib64/libc.so.6", "/lib/libc.so.6", NULL
    };
    int opened = 0;
    int i;

    for (i = 0; candidates[i] != NULL; i++)
    {
      if (lsp_elf_open (candidates[i], &elf) == 0)
      {
        uint64_t value = 0;

        opened = 1;
        SELF_CHECK ("elf_export/mprotect",
            lsp_elf_find_export (&elf, "mprotect", &value));
        SELF_CHECK ("elf_export/setenv",
            lsp_elf_find_export (&elf, "setenv", &value));
        SELF_CHECK ("elf_symbol_count", lsp_elf_symbol_count (&elf) > 100);
        lsp_elf_close (&elf);
        break;
      }
    }
    SELF_CHECK ("elf_open/libc", opened);
  }

  {
    LspPayload payload;
    uint64_t base = 0x10000000;
    uint64_t libc_funcs[LSP_PAYLOAD_LIBC_COUNT];
    size_t i;
    int error;

    for (i = 0; i != LSP_PAYLOAD_LIBC_COUNT; i++)
      libc_funcs[i] = 0x20000000 + i * 0x1000;

    error = lsp_payload_build (options->gate_path, "lspfrifa-zygote-selftest",
        base, 5, 0x30000000, 0x30001000, libc_funcs,
        LSP_PAYLOAD_LIBC_COUNT, 0x30002000, 0, &payload);
    SELF_CHECK ("payload_build", error == 0 && payload.bytes != NULL);
    if (error == 0)
    {
      unsigned char * field = payload.bytes + payload.socket_field_offset;
      SELF_CHECK ("payload_socket_field",
          memcmp (field, "lspfrifa-zygote-selftest", 24) == 0);
      SELF_CHECK ("payload_base_field", *(uint64_t *) (field + 64) == base);
      SELF_CHECK ("payload_size_field", *(uint64_t *) (field + 72) == payload.size);
      SELF_CHECK ("payload_prot_field", *(uint64_t *) (field + 80) == 5);
      SELF_CHECK ("payload_setcontext_field",
          *(uint64_t *) (field + 96) == 0x30000000);
      SELF_CHECK ("payload_setargv0_field",
          *(uint64_t *) (field + 104) == 0x30001000);
      SELF_CHECK ("payload_libc_first", *(uint64_t *) (field + 112) == libc_funcs[0]);
      SELF_CHECK ("payload_libc_last", *(uint64_t *) (field + 200) == libc_funcs[11]);
      SELF_CHECK ("payload_setenv_field", *(uint64_t *) (field + 208) == 0x30002000);
      lsp_payload_free (&payload);
    }
  }

  {
    int pair[2];
    LspHello hello;

    SELF_CHECK ("socketpair", socketpair (AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    if (pair[0] != -1)
    {
      SELF_CHECK ("wire_write_hello",
          lsp_wire_write_hello (pair[0], 1234, 42, "com.test:suffix") == 0);
      SELF_CHECK ("wire_read_hello",
          lsp_wire_read_hello (pair[1], &hello) == 0 &&
          hello.pid == 1234 && hello.ppid == 42 &&
          strcmp (hello.name, "com.test:suffix") == 0);
      close (pair[0]);
      close (pair[1]);
    }
  }

  {
    LspPatchState state;
    LspPatchState loaded;
    unsigned char page[16] = { 1, 2, 3 };
    unsigned char original[8] = { 9, 8, 7 };
    unsigned char replacement[8] = { 6, 5, 4 };

    memset (&state, 0, sizeof (state));
    state.pid = 4242;
    state.payload_base = 0x1000;
    state.payload_size = 16;
    state.payload_protection = 5;
    snprintf (state.payload_path, sizeof (state.payload_path), "/tmp/x.so");
    state.payload_file_offset = 0x2000;
    state.payload_original = page;
    state.payload_original_size = sizeof (page);
    state.slots[0].address = 0x3000;
    state.slots[0].length = 8;
    memcpy (state.slots[0].original, original, 8);
    memcpy (state.slots[0].replacement, replacement, 8);
    state.slot_count = 1;

    SELF_CHECK ("state_save", lsp_state_save (options->state_dir, &state) == 0);
    SELF_CHECK ("state_load",
        lsp_state_load (options->state_dir, 4242, &loaded) == 0 &&
        loaded.payload_base == 0x1000 &&
        loaded.payload_original_size == sizeof (page) &&
        memcmp (loaded.payload_original, page, sizeof (page)) == 0 &&
        loaded.slot_count == 1 &&
        loaded.slots[0].address == 0x3000 &&
        memcmp (loaded.slots[0].original, original, 8) == 0);
    lsp_state_clear (&loaded);
    SELF_CHECK ("state_remove", lsp_state_remove (options->state_dir, 4242) == 0);
  }

#undef SELF_CHECK

  return failures == 0 ? 0 : 1;
}

/* ------------------------------------------------------------------ */
/* CLI                                                                 */
/* ------------------------------------------------------------------ */

static void
usage (const char * program)
{
  fprintf (stderr,
      "usage:\n"
      "  %s targets\n"
      "  %s inject [--pid N | --all] [--socket NAME] [--gate PATH] [--serve]\n"
      "            [--hold PKG,...]\n"
      "            [--libc-match PAT] [--runtime-match PAT] [--selinux-match PAT]\n"
      "            [--payload-match PAT] [--state DIR]\n"
      "  %s serve [--socket NAME] [--hold PKG,...] [--state DIR]\n"
      "  %s revert [--pid N | --all] [--state DIR]\n"
      "  %s resume <pid> [--socket NAME]\n"
      "  %s self-test [--gate PATH] [--state DIR]\n",
      program, program, program, program, program, program);
}

static void
default_state_dir (char * out, size_t out_size)
{
  const char * env = getenv ("LSPFRIFA_ZYGOTE_STATE");

  if (env != NULL && env[0] != '\0')
  {
    snprintf (out, out_size, "%s", env);
    return;
  }

  if (access ("/data/local/tmp", W_OK) == 0)
  {
    snprintf (out, out_size, "/data/local/tmp/lspfrifa-zygote");
    return;
  }

  snprintf (out, out_size, "/tmp/lspfrifa-zygote");
}

static void
default_gate_path (const char * argv0, char * out, size_t out_size)
{
  char exe[PATH_MAX];
  ssize_t n;
  char * slash;

  n = readlink ("/proc/self/exe", exe, sizeof (exe) - 1);
  if (n > 0)
  {
    exe[n] = '\0';
    slash = strrchr (exe, '/');
    if (slash != NULL)
    {
      *slash = '\0';
      snprintf (out, out_size, "%.*s/lspfrifa-gate.elf",
          (int) (out_size > 32 ? out_size - 32 : 0), exe);
      return;
    }
  }

  snprintf (out, out_size, "lspfrifa-gate.elf");
  (void) argv0;
}

static int
parse_options (int argc, char ** argv, Options * options)
{
  int i;

  memset (options, 0, sizeof (*options));
  snprintf (options->socket_name, sizeof (options->socket_name),
      "lspfrifa-zygote-gate");
  snprintf (options->libc_match, sizeof (options->libc_match), "/libc.so");
  snprintf (options->runtime_match, sizeof (options->runtime_match),
      "/libandroid_runtime.so");
  snprintf (options->selinux_match, sizeof (options->selinux_match),
      "/libselinux.so");
  snprintf (options->payload_match, sizeof (options->payload_match),
      "/libstagefright.so");
  default_state_dir (options->state_dir, sizeof (options->state_dir));
  default_gate_path (argv[0], options->gate_path, sizeof (options->gate_path));

  for (i = 2; i < argc; i++)
  {
    const char * arg = argv[i];

#define NEED_VALUE() \
    do { \
      if (i + 1 >= argc) { \
        fprintf (stderr, "lspfrifa-zygote: %s requires a value\n", arg); \
        return EXIT_USAGE; \
      } \
      i++; \
    } while (0)

    if (strcmp (arg, "--pid") == 0)
    {
      NEED_VALUE ();
      options->pid = (pid_t) strtol (argv[i], NULL, 0);
    }
    else if (strcmp (arg, "--all") == 0)
      options->all = true;
    else if (strcmp (arg, "--serve") == 0)
      options->serve = true;
    else if (strcmp (arg, "--socket") == 0)
    {
      NEED_VALUE ();
      snprintf (options->socket_name, sizeof (options->socket_name), "%s", argv[i]);
    }
    else if (strcmp (arg, "--gate") == 0)
    {
      NEED_VALUE ();
      snprintf (options->gate_path, sizeof (options->gate_path), "%s", argv[i]);
    }
    else if (strcmp (arg, "--state") == 0)
    {
      NEED_VALUE ();
      snprintf (options->state_dir, sizeof (options->state_dir), "%s", argv[i]);
    }
    else if (strcmp (arg, "--libc-match") == 0)
    {
      NEED_VALUE ();
      snprintf (options->libc_match, sizeof (options->libc_match), "%s", argv[i]);
    }
    else if (strcmp (arg, "--runtime-match") == 0)
    {
      NEED_VALUE ();
      snprintf (options->runtime_match, sizeof (options->runtime_match), "%s", argv[i]);
    }
    else if (strcmp (arg, "--selinux-match") == 0)
    {
      NEED_VALUE ();
      snprintf (options->selinux_match, sizeof (options->selinux_match), "%s", argv[i]);
    }
    else if (strcmp (arg, "--payload-match") == 0)
    {
      NEED_VALUE ();
      snprintf (options->payload_match, sizeof (options->payload_match), "%s", argv[i]);
    }
    else if (strcmp (arg, "--hold") == 0)
    {
      char * save = NULL;
      char * token;

      NEED_VALUE ();
      token = strtok_r (argv[i], ",", &save);
      while (token != NULL && options->hold_count < MAX_HOLD_PACKAGES)
      {
        snprintf (options->hold_packages[options->hold_count],
            sizeof (options->hold_packages[options->hold_count]), "%s", token);
        options->hold_count++;
        token = strtok_r (NULL, ",", &save);
      }
    }
    else if (arg[0] != '-')
    {
      snprintf (options->positional, sizeof (options->positional), "%s", arg);
    }
    else if (strcmp (arg, "-h") == 0 || strcmp (arg, "--help") == 0)
    {
      usage (argv[0]);
      return -1;
    }
    else
    {
      fprintf (stderr, "lspfrifa-zygote: unknown option %s\n", arg);
      return EXIT_USAGE;
    }

#undef NEED_VALUE
  }

  return 0;
}

int
main (int argc, char ** argv)
{
  Options options;
  const char * command;
  int result;

  if (argc < 2)
  {
    usage (argv[0]);
    return EXIT_USAGE;
  }

  command = argv[1];
  result = parse_options (argc, argv, &options);
  if (result == -1)
    return 0;
  if (result != 0)
    return result;

  mkdir (options.state_dir, 0700);

  if (strcmp (command, "targets") == 0)
  {
    print_targets ();
    return 0;
  }
  if (strcmp (command, "inject") == 0)
    return command_inject (&options);
  if (strcmp (command, "serve") == 0)
    return command_serve (&options);
  if (strcmp (command, "revert") == 0)
    return command_revert (&options);
  if (strcmp (command, "resume") == 0)
    return command_resume (&options, options.positional[0] != '\0' ? options.positional : NULL);
  if (strcmp (command, "self-test") == 0)
    return command_self_test (&options);

  fprintf (stderr, "lspfrifa-zygote: unknown command %s\n", command);
  usage (argv[0]);
  return EXIT_USAGE;
}
