/*
 * Mock zygote process for the host end-to-end test (design §5.1).
 *
 * It plays the role of the Android zygote:
 *   - dlopens mock libstagefright.so (its last text page is the payload page),
 *     mock libandroid_runtime.so (exports the mangled setArgV0 identifier,
 *     imports selinux_android_setcontext) and mock libselinux.so;
 *   - keeps the &setArgV0 function pointer in a large malloc()ed (mmap-backed,
 *     anonymous rw-p) page that the injector's heap scan finds with memmem;
 *   - prints "READY <pid>" and waits for a "go" line on stdin;
 *   - forks a child that calls setcontext through the patched GOT slot and
 *     setArgV0 through the patched heap slot, then records what happened into
 *     <result-dir>/child_result.json;
 *   - after the daemon reverts the zygote patch and is killed, the test sends
 *     "check" and the parent records <result-dir>/check_result.json.
 *
 * PR_SET_PTRACER_ANY is test scaffolding only: this sandbox denies
 * /proc/<pid>/mem writes, so the injector uses its documented
 * process_vm_writev fallback, which requires the target's ptrace grant.
 */
#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#define SETARGV0_SYMBOL "_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring"
#define RESULT_NAME_MAX 1024

typedef int (*setcontext_fn) (void);
typedef void (*setargv0_fn) (void *, void *, void *);

static volatile sig_atomic_t g_sigcont_seen = 0;

static void
on_sigcont (int sig)
{
  (void) sig;
  g_sigcont_seen = 1;
}

static void
trim_newline (char * line)
{
  size_t len = strlen (line);

  while (len != 0 && (line[len - 1] == '\n' || line[len - 1] == '\r'))
    line[--len] = '\0';
}

static size_t
page_size (void)
{
  long v = sysconf (_SC_PAGESIZE);
  return v > 0 ? (size_t) v : 4096u;
}

/* Last executable mapping ending in `suffix`; returns 0 on success. */
static int
find_payload_page (const char * suffix, uint64_t * base, char * path,
    size_t path_size, uint64_t * file_offset)
{
  FILE * f;
  char line[4096];
  int found = 0;

  f = fopen ("/proc/self/maps", "re");
  if (f == NULL)
    return -1;

  while (fgets (line, sizeof (line), f) != NULL)
  {
    unsigned long long start, end, offset, inode;
    unsigned int major, minor;
    char perms[8];
    int consumed = 0;
    const char * rest;
    size_t len;

    if (sscanf (line, "%llx-%llx %7s %llx %x:%x %llu %n", &start, &end, perms,
            &offset, &major, &minor, &inode, &consumed) < 7)
      continue;
    if (perms[2] != 'x')
      continue;

    rest = line + consumed;
    while (*rest == ' ' || *rest == '\t')
      rest++;
    trim_newline ((char *) rest);
    len = strlen (rest);
    if (len < strlen (suffix) || strcmp (rest + len - strlen (suffix), suffix) != 0)
      continue;

    *base = (uint64_t) end - page_size ();
    *file_offset = (uint64_t) offset + (*base - (uint64_t) start);
    snprintf (path, path_size, "%s", rest);
    found = 1;
  }

  fclose (f);
  return found ? 0 : -1;
}

static int
page_matches_file (uint64_t base, const char * path, uint64_t file_offset)
{
  size_t page = page_size ();
  unsigned char * memory;
  unsigned char * disk;
  int memory_fd;
  int disk_fd;
  int match;

  memory = malloc (page);
  disk = malloc (page);
  if (memory == NULL || disk == NULL)
  {
    free (memory);
    free (disk);
    return 0;
  }

  memory_fd = open ("/proc/self/mem", O_RDONLY | O_CLOEXEC);
  disk_fd = open (path, O_RDONLY | O_CLOEXEC);
  if (memory_fd == -1 || disk_fd == -1)
  {
    if (memory_fd != -1)
      close (memory_fd);
    if (disk_fd != -1)
      close (disk_fd);
    free (memory);
    free (disk);
    return 0;
  }

  match = pread (memory_fd, memory, page, (off_t) base) == (ssize_t) page &&
      pread (disk_fd, disk, page, (off_t) file_offset) == (ssize_t) page &&
      memcmp (memory, disk, page) == 0;

  close (memory_fd);
  close (disk_fd);
  free (memory);
  free (disk);
  return match;
}

static void
write_child_result (const char * directory, void * runtime_handle,
    void * selinux_handle, void ** slot, void * original_setargv0)
{
  char path[RESULT_NAME_MAX];
  FILE * f;
  const char * gate = getenv ("LSPFRIDA_ZYGOTE_GATE");
  const char * name = getenv ("LSPFRIDA_ZYGOTE_GATE_NAME");
  const char * ppid_text = getenv ("LSPFRIDA_ZYGOTE_GATE_PPID");
  const char * proc = getenv ("LSPFRIDA_ZYGOTE_GATE_PROC");
  int * original_called = dlsym (runtime_handle, "mock_original_called");
  int * selinux_seen = dlsym (selinux_handle, "mock_selinux_seen");
  uint64_t base = 0;
  uint64_t file_offset = 0;
  char payload_path[RESULT_NAME_MAX] = "";
  int payload_restored;
  int pointer_restored;

  payload_restored = find_payload_page ("/libstagefright.so", &base, payload_path,
      sizeof (payload_path), &file_offset) == 0 &&
      page_matches_file (base, payload_path, file_offset);
  pointer_restored = *slot == original_setargv0;

  snprintf (path, sizeof (path), "%s/child_result.json", directory);
  f = fopen (path, "we");
  if (f == NULL)
    return;

  fprintf (f, "{\n");
  fprintf (f, "  \"pid\": %d,\n", (int) getpid ());
  fprintf (f, "  \"ppid\": %d,\n", (int) getppid ());
  fprintf (f, "  \"env_gate\": %s,\n", gate != NULL ? "\"1\"" : "null");
  fprintf (f, "  \"env_name\": \"%s\",\n", name != NULL ? name : "");
  fprintf (f, "  \"env_ppid\": \"%s\",\n", ppid_text != NULL ? ppid_text : "");
  fprintf (f, "  \"env_proc\": \"%s\",\n", proc != NULL ? proc : "");
  fprintf (f, "  \"original_called\": %s,\n",
      original_called != NULL && *original_called ? "true" : "false");
  fprintf (f, "  \"selinux_seen\": %s,\n",
      selinux_seen != NULL && *selinux_seen ? "true" : "false");
  fprintf (f, "  \"pointer_restored\": %s,\n", pointer_restored ? "true" : "false");
  fprintf (f, "  \"sigstop_seen\": %s,\n", g_sigcont_seen ? "true" : "false");
  fprintf (f, "  \"payload_restored\": %s\n", payload_restored ? "true" : "false");
  fprintf (f, "}\n");
  fclose (f);
}

static void
write_check_result (const char * directory, void ** slot, void * original_setargv0)
{
  char path[RESULT_NAME_MAX];
  FILE * f;
  uint64_t base = 0;
  uint64_t file_offset = 0;
  char payload_path[RESULT_NAME_MAX] = "";
  int payload_restored;

  payload_restored = find_payload_page ("/libstagefright.so", &base, payload_path,
      sizeof (payload_path), &file_offset) == 0 &&
      page_matches_file (base, payload_path, file_offset);

  snprintf (path, sizeof (path), "%s/check_result.json", directory);
  f = fopen (path, "we");
  if (f == NULL)
    return;

  fprintf (f, "{\n");
  fprintf (f, "  \"pid\": %d,\n", (int) getpid ());
  fprintf (f, "  \"pointer_restored\": %s,\n",
      *slot == original_setargv0 ? "true" : "false");
  fprintf (f, "  \"payload_restored\": %s\n", payload_restored ? "true" : "false");
  fprintf (f, "}\n");
  fclose (f);
}

/* The sandbox this suite runs in denies /proc/<pid>/mem writes, so the
 * injector's documented fallback (process_vm_writev) is used.  That syscall
 * refuses to write a read-only MAP_PRIVATE file page (EFAULT), unlike
 * /proc/pid/mem pwrite on a device.  Make the fixture's payload page RWX so
 * the sandbox fallback exercises the same patch semantics; on Android the real
 * libstagefright text page stays RX and is written through /proc/<pid>/mem. */
static void
make_payload_page_writable (void)
{
  uint64_t base = 0;
  uint64_t file_offset = 0;
  char path[RESULT_NAME_MAX] = "";

  if (find_payload_page ("/libstagefright.so", &base, path, sizeof (path),
          &file_offset) == 0)
    mprotect ((void *) (uintptr_t) base, page_size (),
        PROT_READ | PROT_WRITE | PROT_EXEC);
}

int
main (int argc, char ** argv)
{
  const char * directory;
  void * stagefright_handle;
  void * runtime_handle;
  void * selinux_handle;
  setargv0_fn original_setargv0;
  setcontext_fn call_setcontext;
  void ** slot;
  char line[256];
  pid_t child;
  int status;

  if (argc < 2)
  {
    fprintf (stderr, "usage: mock_zygote <result-dir>\n");
    return 2;
  }
  directory = argv[1];

  /* test scaffolding: allow the injector (a sibling process) to write memory */
  prctl (PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);

  stagefright_handle = dlopen ("libstagefright.so", RTLD_NOW | RTLD_GLOBAL);
  selinux_handle = dlopen ("libselinux.so", RTLD_NOW | RTLD_GLOBAL);
  runtime_handle = dlopen ("libandroid_runtime.so", RTLD_NOW | RTLD_GLOBAL);
  if (stagefright_handle == NULL || runtime_handle == NULL || selinux_handle == NULL)
  {
    fprintf (stderr, "mock_zygote: dlopen failed: %s\n", dlerror ());
    return 1;
  }

  original_setargv0 = (setargv0_fn) dlsym (runtime_handle, SETARGV0_SYMBOL);
  call_setcontext = (setcontext_fn) dlsym (runtime_handle, "mock_call_setcontext");
  if (original_setargv0 == NULL || call_setcontext == NULL)
  {
    fprintf (stderr, "mock_zygote: dlsym failed: %s\n", dlerror ());
    return 1;
  }

  make_payload_page_writable ();

  /* large malloc => mmap-backed anonymous rw-p page, found by the heap scan */
  slot = malloc (1024 * 1024);
  if (slot == NULL)
    return 1;
  slot[0] = (void *) original_setargv0;

  printf ("READY %d slot=%p\n", (int) getpid (), (void *) slot);
  fflush (stdout);

  while (fgets (line, sizeof (line), stdin) != NULL)
  {
    trim_newline (line);
    if (strcmp (line, "go") == 0)
      break;
  }

  child = fork ();
  if (child == -1)
    return 1;

  if (child == 0)
  {
    /* child: any tracer may write our memory (sandbox fallback grant) */
    prctl (PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);
    signal (SIGCONT, on_sigcont);
    g_sigcont_seen = 0;

    /* first setcontext (sets the gate's process_name), then setArgV0 */
    call_setcontext ();
    ((setargv0_fn) slot[0]) (NULL, NULL, (void *) "com.test.app");

    write_child_result (directory, runtime_handle, selinux_handle, slot,
        (void *) original_setargv0);
    _exit (0);
  }

  waitpid (child, &status, 0);
  if (WIFSIGNALED (status))
    fprintf (stderr, "mock_parent: child killed by signal %d\n", WTERMSIG (status));
  printf ("CHILD_EXIT %d\n", WIFEXITED (status) ? WEXITSTATUS (status) : -1);
  fflush (stdout);

  while (fgets (line, sizeof (line), stdin) != NULL)
  {
    trim_newline (line);
    if (strcmp (line, "check") == 0)
    {
      write_check_result (directory, slot, (void *) original_setargv0);
      printf ("CHECK_DONE\n");
      fflush (stdout);
    }
    else if (strcmp (line, "quit") == 0)
      break;
  }

  return 0;
}
