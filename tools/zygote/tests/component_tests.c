/*
 * Host component tests for the LSPFRIDA zygote gate/injector modules.
 *
 *   component_tests <mock-dir> <gate-elf> <tmp-dir>
 *
 * Covers the parts of the design that are testable without a second process:
 *   - /proc/<pid>/maps parsing + payload-page/boot-heap/anon-rw predicates;
 *   - ELF export resolution against the host libc and libm;
 *   - ELF import (relocation slot) resolution against the mock
 *     libandroid_runtime.so, cross-checked by reading the live GOT entry;
 *   - payload template building: socket-field patch, pointer fields at the
 *     frozen offsets, and the two entry vaddrs;
 *   - gate ELF structure (both entry symbols exported, text segment fits page);
 *   - state-file round trip;
 *   - wire hello encoding/decoding.
 */
#define _GNU_SOURCE

#include "elf_parser.h"
#include "gate_context.h"
#include "payload.h"
#include "proc_maps.h"
#include "state.h"
#include "wire.h"

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#define SETARGV0_SYMBOL "_Z27android_os_Process_setArgV0P7_JNIEnvP8_jobjectP8_jstring"

static int failures = 0;

#define CHECK(name, condition) \
  do { \
    if (condition) \
      printf ("PASS component/%s\n", name); \
    else \
    { \
      printf ("FAIL component/%s\n", name); \
      failures++; \
    } \
  } while (0)

static void *
lsp_memmem_simple (const void * haystack, size_t haystack_len, const void * needle,
    size_t needle_len)
{
  const unsigned char * h = haystack;
  size_t i;

  if (needle_len == 0 || needle_len > haystack_len)
    return NULL;
  for (i = 0; i + needle_len <= haystack_len; i++)
  {
    if (h[i] == ((const unsigned char *) needle)[0] &&
        memcmp (h + i, needle, needle_len) == 0)
      return (void *) (h + i);
  }
  return NULL;
}

static int
read_own_memory (uint64_t address, void * buffer, size_t length)
{
  int fd = open ("/proc/self/mem", O_RDONLY | O_CLOEXEC);
  ssize_t n;

  if (fd == -1)
    return -1;
  n = pread (fd, buffer, length, (off_t) address);
  close (fd);
  return n == (ssize_t) length ? 0 : -1;
}

static void
find_libc_libm_paths (char * libc_out, size_t libc_size, char * libm_out,
    size_t libm_size)
{
  LspMaps maps;

  libc_out[0] = '\0';
  libm_out[0] = '\0';
  if (lsp_maps_load (getpid (), &maps) != 0)
    return;

  {
    const LspMap * m = lsp_maps_find_path_suffix (&maps, "libc.so.6");
    if (m != NULL)
      snprintf (libc_out, libc_size, "%s", m->path);
  }
  {
    const LspMap * m = lsp_maps_find_path_suffix (&maps, "libm.so.6");
    if (m == NULL)
    {
      void * handle = dlopen ("libm.so.6", RTLD_NOW | RTLD_GLOBAL);
      if (handle != NULL)
      {
        LspMaps after;
        if (lsp_maps_load (getpid (), &after) == 0)
        {
          const LspMap * added = lsp_maps_find_path_suffix (&after, "libm.so.6");
          if (added != NULL)
            snprintf (libm_out, libm_size, "%s", added->path);
          lsp_maps_free (&after);
        }
      }
    }
    else
      snprintf (libm_out, libm_size, "%s", m->path);
  }

  lsp_maps_free (&maps);
}

int
main (int argc, char ** argv)
{
  const char * mock_dir = argc > 1 ? argv[1] : "tests/mock";
  const char * gate_path = argc > 2 ? argv[2] : "out/host/lspfrifa-gate.elf";
  const char * tmp_dir = argc > 3 ? argv[3] : "/tmp";
  char libc_path[1024];
  char libm_path[1024];
  char runtime_path[1024];
  char selinux_path[1024];
  LspMaps maps;

  snprintf (runtime_path, sizeof (runtime_path), "%s/libandroid_runtime.so", mock_dir);
  snprintf (selinux_path, sizeof (selinux_path), "%s/libselinux.so", mock_dir);
  mkdir (tmp_dir, 0700);

  find_libc_libm_paths (libc_path, sizeof (libc_path), libm_path, sizeof (libm_path));

  /* ---- /proc maps ---- */
  {
    size_t anon = 0;
    size_t i;
    LspMap fake;

    CHECK ("proc_maps_load", lsp_maps_load (getpid (), &maps) == 0 && maps.count > 10);
    CHECK ("proc_maps_page_size", lsp_page_size () >= 4096);
    CHECK ("proc_maps_libc_suffix", libc_path[0] != '\0');
    CHECK ("proc_maps_libm_suffix", libm_path[0] != '\0');

    for (i = 0; i != maps.count; i++)
    {
      if (lsp_map_is_anon_rw (&maps.items[i]))
        anon++;
    }
    CHECK ("proc_maps_anon_rw_candidates", anon > 0);

    memset (&fake, 0, sizeof (fake));
    fake.readable = true;
    fake.writable = true;
    snprintf (fake.path, sizeof (fake.path), "/data/dalvik-cache/arm64/boot.art");
    CHECK ("proc_maps_boot_heap", lsp_map_is_boot_heap (&fake));
    fake.executable = true;
    CHECK ("proc_maps_boot_heap_exec_rejected", !lsp_map_is_boot_heap (&fake));

    lsp_maps_free (&maps);
  }

  /* ---- ELF exports: host libc and libm ---- */
  {
    LspElf elf;

    CHECK ("elf_open_libc", libc_path[0] != '\0' && lsp_elf_open (libc_path, &elf) == 0);
    if (libc_path[0] != '\0' && lsp_elf_open (libc_path, &elf) == 0)
    {
      uint64_t value = 0;

      CHECK ("elf_libc_export_mprotect",
          lsp_elf_find_export (&elf, "mprotect", &value));
      CHECK ("elf_libc_export_setenv",
          lsp_elf_find_export (&elf, "setenv", &value));
      CHECK ("elf_libc_export_sendmsg",
          lsp_elf_find_export (&elf, "sendmsg", &value));
      CHECK ("elf_libc_export_errno_location",
          lsp_elf_find_export (&elf, "__errno_location", &value));
      CHECK ("elf_libc_symbol_count", lsp_elf_symbol_count (&elf) > 100);
      CHECK ("elf_libc_export_absent",
          !lsp_elf_find_export (&elf, "no_such_symbol_xyz", &value));
      lsp_elf_close (&elf);
    }

    if (libm_path[0] != '\0' && lsp_elf_open (libm_path, &elf) == 0)
    {
      uint64_t value = 0;
      /* glibc sin/cos are STT_GNU_IFUNC */
      CHECK ("elf_libm_export_sin", lsp_elf_find_export (&elf, "sin", &value));
      CHECK ("elf_libm_export_cos", lsp_elf_find_export (&elf, "cos", &value));
      /* import (relocation slot) resolution against host libm */
      CHECK ("elf_libm_import_slot_cxa_finalize",
          lsp_elf_find_import_slot (&elf, "__cxa_finalize", &value));
      lsp_elf_close (&elf);
    }
    else
      CHECK ("elf_open_libm", false);
  }

  /* ---- ELF imports: mock libandroid_runtime.so, cross-checked on the GOT ---- */
  {
    LspElf elf;

    CHECK ("elf_open_runtime_mock", lsp_elf_open (runtime_path, &elf) == 0);
    if (lsp_elf_open (runtime_path, &elf) == 0)
    {
      uint64_t slot = 0;
      CHECK ("elf_runtime_export_setargv0",
          lsp_elf_find_export (&elf, SETARGV0_SYMBOL, &slot));
      {
        uint64_t import_slot = 0;
        slot = 0;
        CHECK ("elf_runtime_import_slot_selinux",
            lsp_elf_find_import_slot (&elf, "selinux_android_setcontext",
                &import_slot));
        slot = import_slot;

        {
          void * selinux_handle = dlopen (selinux_path, RTLD_NOW | RTLD_GLOBAL);
          void * runtime_handle = dlopen (runtime_path, RTLD_NOW | RTLD_GLOBAL);

          CHECK ("elf_dlopen_mock_runtime",
              selinux_handle != NULL && runtime_handle != NULL);
          if (selinux_handle != NULL && runtime_handle != NULL && slot != 0)
          {
            void * expected = dlsym (selinux_handle, "selinux_android_setcontext");
            LspMaps live;
            const LspMap * base_map = NULL;

            if (lsp_maps_load (getpid (), &live) == 0)
            {
              size_t i;
              for (i = 0; i != live.count; i++)
              {
                if (lsp_map_ends_with (live.items[i].path, "libandroid_runtime.so") &&
                    live.items[i].offset == 0)
                  base_map = &live.items[i];
              }

              if (base_map != NULL && expected != NULL)
              {
                uint64_t got = 0;
                CHECK ("elf_runtime_got_read",
                    read_own_memory (base_map->start + slot, &got, sizeof (got)) == 0);
                CHECK ("elf_runtime_got_value", got == (uint64_t) (uintptr_t) expected);
              }
              else
                CHECK ("elf_runtime_got_mapping", false);

              lsp_maps_free (&live);
            }
          }
        }
      }
      lsp_elf_close (&elf);
    }
  }

  /* ---- payload template ---- */
  {
    uint64_t libc_funcs[LSP_PAYLOAD_LIBC_COUNT];
    uint64_t base = 0x12000000;
    uint64_t setcontext = 0x33000000;
    uint64_t setargv0 = 0x33001000;
    uint64_t setenv_addr = 0x33002000;
    size_t i;
    LspPayload payload;
    int error;

    for (i = 0; i != LSP_PAYLOAD_LIBC_COUNT; i++)
      libc_funcs[i] = 0x34000000 + i * 0x1000;

    error = lsp_payload_build (gate_path, "lspfrifa-zygote-component", base, 5,
        setcontext, setargv0, libc_funcs, LSP_PAYLOAD_LIBC_COUNT, setenv_addr, 0,
        &payload);
    CHECK ("payload_build", error == 0 && payload.bytes != NULL);
    if (error == 0)
    {
      unsigned char * field = payload.bytes + payload.socket_field_offset;
      uint64_t value;
      size_t j;

      CHECK ("payload_socket_field_name",
          memcmp (field, "lspfrifa-zygote-component", 25) == 0);
      CHECK ("payload_socket_field_padded", field[25] == 0 && field[63] == 0);
      CHECK ("payload_no_template_placeholder",
          lsp_memmem_simple (payload.bytes, payload.size, LSP_GATE_SOCKET_TEMPLATE,
              sizeof (LSP_GATE_SOCKET_TEMPLATE) - 1) == NULL);

      memcpy (&value, field + 64, 8);
      CHECK ("payload_field_base", value == base);
      memcpy (&value, field + 72, 8);
      CHECK ("payload_field_size", value == payload.size);
      memcpy (&value, field + 80, 8);
      CHECK ("payload_field_protection", value == 5);
      memcpy (&value, field + 88, 8);
      CHECK ("payload_field_process_name_zero", value == 0);
      memcpy (&value, field + 96, 8);
      CHECK ("payload_field_original_setcontext", value == setcontext);
      memcpy (&value, field + 104, 8);
      CHECK ("payload_field_original_setargv0", value == setargv0);

      for (j = 0; j != LSP_PAYLOAD_LIBC_COUNT; j++)
      {
        memcpy (&value, field + 112 + j * 8, 8);
        if (value != libc_funcs[j])
          break;
      }
      CHECK ("payload_field_libc_order", j == LSP_PAYLOAD_LIBC_COUNT);
      memcpy (&value, field + 208, 8);
      CHECK ("payload_field_setenv", value == setenv_addr);
      memcpy (&value, field + 216, 8);
      CHECK ("payload_field_log_write_zero", value == 0);

      CHECK ("payload_entry_setargv0", payload.entry_setargv0 > base);
      /* host gate: .text.entrypoint starts at vaddr 0, so setcontext may land
       * exactly at payload_base */
      CHECK ("payload_entry_setcontext", payload.entry_setcontext >= base);
      CHECK ("payload_entry_distinct", payload.entry_setargv0 != payload.entry_setcontext);

      lsp_payload_free (&payload);
    }
  }

  /* ---- gate ELF structure ---- */
  {
    LspElf elf;

    CHECK ("gate_elf_open", lsp_elf_open (gate_path, &elf) == 0);
    if (lsp_elf_open (gate_path, &elf) == 0)
    {
      uint64_t vaddr, offset, size;

      uint64_t export_value = 0;
      CHECK ("gate_elf_export_setcontext",
          lsp_elf_find_export (&elf, "lsp_gate_replacement_setcontext", &export_value));
      CHECK ("gate_elf_export_setargv0",
          lsp_elf_find_export (&elf, "lsp_gate_replacement_setargv0", &export_value));
      CHECK ("gate_elf_text_segment",
          lsp_elf_text_segment (&elf, &vaddr, &offset, &size) == 0);
      CHECK ("gate_elf_text_fits_page", size <= lsp_page_size ());
      lsp_elf_close (&elf);
    }
  }

  /* ---- state round trip ---- */
  {
    LspPatchState state;
    LspPatchState loaded;
    unsigned char page[32];
    unsigned char original[8] = { 1, 2, 3, 4, 5, 6, 7, 8 };
    unsigned char replacement[8] = { 8, 7, 6, 5, 4, 3, 2, 1 };
    size_t i;

    for (i = 0; i != sizeof (page); i++)
      page[i] = (unsigned char) i;

    memset (&state, 0, sizeof (state));
    state.pid = 777;
    state.payload_base = 0xabc000;
    state.payload_size = sizeof (page);
    state.payload_protection = 5;
    snprintf (state.payload_path, sizeof (state.payload_path), "/data/app/lib.so");
    state.payload_file_offset = 0x9000;
    state.payload_original = page;
    state.payload_original_size = sizeof (page);
    state.slots[0].address = 0xdef000;
    state.slots[0].length = 8;
    memcpy (state.slots[0].original, original, 8);
    memcpy (state.slots[0].replacement, replacement, 8);
    state.slots[1].address = 0xdef008;
    state.slots[1].length = 8;
    memcpy (state.slots[1].original, replacement, 8);
    memcpy (state.slots[1].replacement, original, 8);
    state.slot_count = 2;

    CHECK ("state_save", lsp_state_save (tmp_dir, &state) == 0);
    CHECK ("state_load", lsp_state_load (tmp_dir, 777, &loaded) == 0);
    CHECK ("state_roundtrip_fields",
        loaded.payload_base == 0xabc000 &&
        loaded.payload_size == sizeof (page) &&
        loaded.payload_protection == 5 &&
        strcmp (loaded.payload_path, "/data/app/lib.so") == 0 &&
        loaded.payload_file_offset == 0x9000);
    CHECK ("state_roundtrip_payload_bytes",
        loaded.payload_original_size == sizeof (page) &&
        memcmp (loaded.payload_original, page, sizeof (page)) == 0);
    CHECK ("state_roundtrip_slots",
        loaded.slot_count == 2 &&
        loaded.slots[0].address == 0xdef000 &&
        memcmp (loaded.slots[0].original, original, 8) == 0 &&
        memcmp (loaded.slots[1].replacement, original, 8) == 0);
    lsp_state_clear (&loaded);
    CHECK ("state_remove", lsp_state_remove (tmp_dir, 777) == 0);
    CHECK ("state_load_missing", lsp_state_load (tmp_dir, 777, &loaded) != 0);
  }

  /* ---- wire hello ---- */
  {
    int pair[2];
    LspHello hello;

    CHECK ("wire_socketpair", socketpair (AF_UNIX, SOCK_STREAM, 0, pair) == 0);
    if (socketpair (AF_UNIX, SOCK_STREAM, 0, pair) == 0)
    {
      CHECK ("wire_write_hello",
          lsp_wire_write_hello (pair[0], 4242, 1, "com.example:remote") == 0);
      CHECK ("wire_read_hello",
          lsp_wire_read_hello (pair[1], &hello) == 0 &&
          hello.pid == 4242 && hello.ppid == 1 &&
          strcmp (hello.name, "com.example:remote") == 0);
      CHECK ("wire_ack", lsp_wire_send_ack (pair[0]) == 0);
      close (pair[0]);
      close (pair[1]);
    }

    {
      char escaped[64];
      lsp_wire_json_escape ("a\"b\\c", escaped, sizeof (escaped));
      CHECK ("wire_json_escape", strcmp (escaped, "a\\\"b\\\\c") == 0);
    }
  }

  printf ("%s component tests (%d failure(s))\n",
      failures == 0 ? "PASS" : "FAIL", failures);
  return failures == 0 ? 0 : 1;
}
