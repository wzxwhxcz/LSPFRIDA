#ifndef LSP_ELF_PARSER_H
#define LSP_ELF_PARSER_H

/*
 * Minimal 64-bit little-endian ELF reader (ports the semantics of
 * frida-core src/linux/helpers/elf-parser.c and adds relocation-slot lookup
 * for imports, which frida's Gum.ElfModule provided).
 *
 * Both supported ABIs (x86_64, aarch64) are ELF64 LE, so Elf64 structs are
 * used directly.
 */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct
{
  unsigned char * data;
  size_t size;
} LspElf;

int lsp_elf_open (const char * path, LspElf * out);
void lsp_elf_close (LspElf * elf);

/* Value (st_value, i.e. vaddr relative to load base) of an exported
 * FUNC/OBJECT/IFUNC with GLOBAL/WEAK binding.  Returns false when not found.
 * (A bool is required: the host gate's first entry point legitimately has
 * st_value == 0 because .text.entrypoint starts at vaddr 0.) */
bool lsp_elf_find_export (const LspElf * elf, const char * name,
    uint64_t * value_out);

/* r_offset (vaddr) of a relocation whose symbol name matches.  Returns false
 * when not found. */
bool lsp_elf_find_import_slot (const LspElf * elf, const char * name,
    uint64_t * slot_out);

/* First executable PT_LOAD segment. */
int lsp_elf_text_segment (const LspElf * elf, uint64_t * vm_address,
    uint64_t * file_offset, uint64_t * file_size);

/* Number of dynamic symbols, for tests/diagnostics. */
size_t lsp_elf_symbol_count (const LspElf * elf);

#endif
