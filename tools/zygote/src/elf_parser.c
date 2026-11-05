#include "elf_parser.h"

#include <elf.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct
{
  const Elf64_Dyn * entries;
  size_t count;
  uint64_t strtab;
  uint64_t strsz;
  uint64_t symtab;
  uint64_t hash;
  uint64_t gnu_hash;
  uint64_t rela;
  uint64_t relasz;
  uint64_t relaent;
  uint64_t rel;
  uint64_t relsz;
  uint64_t relent;
  uint64_t jmprel;
  uint64_t pltrelsz;
  uint64_t pltrel;
} ElfDynInfo;

static const Elf64_Phdr *
find_phdr (const LspElf * elf, uint32_t type)
{
  const Elf64_Ehdr * ehdr = (const Elf64_Ehdr *) elf->data;
  uint16_t i;

  for (i = 0; i != ehdr->e_phnum; i++)
  {
    const Elf64_Phdr * phdr =
        (const Elf64_Phdr *) (elf->data + ehdr->e_phoff + (i * ehdr->e_phentsize));

    if (phdr->p_type == type)
      return phdr;
  }

  return NULL;
}

/* Convert a vaddr in the file image to a file offset through PT_LOAD. */
static bool
vaddr_to_offset (const LspElf * elf, uint64_t vaddr, uint64_t * offset)
{
  const Elf64_Ehdr * ehdr = (const Elf64_Ehdr *) elf->data;
  uint16_t i;

  for (i = 0; i != ehdr->e_phnum; i++)
  {
    const Elf64_Phdr * phdr =
        (const Elf64_Phdr *) (elf->data + ehdr->e_phoff + (i * ehdr->e_phentsize));

    if (phdr->p_type != PT_LOAD)
      continue;

    if (vaddr >= phdr->p_vaddr && vaddr < phdr->p_vaddr + phdr->p_filesz)
    {
      *offset = phdr->p_offset + (vaddr - phdr->p_vaddr);
      return true;
    }
  }

  return false;
}

static int
dyn_info_from_program_headers (const LspElf * elf, ElfDynInfo * out)
{
  const Elf64_Phdr * dyn;
  size_t i;

  memset (out, 0, sizeof (*out));

  dyn = find_phdr (elf, PT_DYNAMIC);
  if (dyn == NULL)
    return -ENOENT;

  out->entries = (const Elf64_Dyn *) (elf->data + dyn->p_offset);
  out->count = dyn->p_filesz / sizeof (Elf64_Dyn);

  for (i = 0; i != out->count; i++)
  {
    const Elf64_Dyn * e = &out->entries[i];

    switch (e->d_tag)
    {
      case DT_STRTAB:     out->strtab = e->d_un.d_ptr; break;
      case DT_STRSZ:      out->strsz = e->d_un.d_ptr; break;
      case DT_SYMTAB:     out->symtab = e->d_un.d_ptr; break;
      case DT_HASH:       out->hash = e->d_un.d_ptr; break;
      case DT_GNU_HASH:   out->gnu_hash = e->d_un.d_ptr; break;
      case DT_RELA:       out->rela = e->d_un.d_ptr; break;
      case DT_RELASZ:     out->relasz = e->d_un.d_ptr; break;
      case DT_RELAENT:    out->relaent = e->d_un.d_ptr; break;
      case DT_REL:        out->rel = e->d_un.d_ptr; break;
      case DT_RELSZ:      out->relsz = e->d_un.d_ptr; break;
      case DT_RELENT:     out->relent = e->d_un.d_ptr; break;
      case DT_JMPREL:     out->jmprel = e->d_un.d_ptr; break;
      case DT_PLTRELSZ:   out->pltrelsz = e->d_un.d_ptr; break;
      case DT_PLTREL:     out->pltrel = e->d_un.d_ptr; break;
      default: break;
    }
  }

  if (out->symtab == 0 || out->strtab == 0 || out->strsz == 0)
    return -ENOENT;

  return 0;
}

static const char *
strtab_pointer (const LspElf * elf, const ElfDynInfo * info)
{
  uint64_t offset;

  if (!vaddr_to_offset (elf, info->strtab, &offset))
    return NULL;

  return (const char *) (elf->data + offset);
}

static const Elf64_Sym *
symtab_pointer (const LspElf * elf, const ElfDynInfo * info, size_t * count)
{
  uint64_t offset;
  uint64_t n = 0;

  if (!vaddr_to_offset (elf, info->symtab, &offset))
    return NULL;

  if (info->hash != 0)
  {
    uint64_t hash_offset;

    if (vaddr_to_offset (elf, info->hash, &hash_offset))
    {
      const uint32_t * hash = (const uint32_t *) (elf->data + hash_offset);
      n = hash[1]; /* nchain */
    }
  }

  if (n == 0 && info->gnu_hash != 0)
  {
    uint64_t gnu_offset;

    if (vaddr_to_offset (elf, info->gnu_hash, &gnu_offset))
    {
      const uint32_t * gnu = (const uint32_t *) (elf->data + gnu_offset);
      const uint32_t nbuckets = gnu[0];
      const uint32_t symoffset = gnu[1];
      const uint32_t bloom_size = gnu[2];
      const uint64_t * bloom = (const uint64_t *) (gnu + 4);
      const uint32_t * buckets = (const uint32_t *) (bloom + bloom_size);
      const uint32_t * chain = buckets + nbuckets;
      uint32_t max_sym = 0;
      uint32_t b;

      for (b = 0; b != nbuckets; b++)
      {
        uint32_t sym = buckets[b];

        if (sym < symoffset)
          continue;

        while ((chain[sym - symoffset] & 1u) == 0)
          sym++;

        if (sym > max_sym)
          max_sym = sym;
      }

      n = (size_t) max_sym + 1;
    }
  }

  if (n == 0)
  {
    /* Fallback: section header .dynsym (present in NDK/glibc libs). */
    const Elf64_Ehdr * ehdr = (const Elf64_Ehdr *) elf->data;
    uint16_t i;

    for (i = 0; i != ehdr->e_shnum; i++)
    {
      const Elf64_Shdr * shdr =
          (const Elf64_Shdr *) (elf->data + ehdr->e_shoff + (i * ehdr->e_shentsize));

      if (shdr->sh_type == SHT_DYNSYM && shdr->sh_entsize != 0)
      {
        n = shdr->sh_size / shdr->sh_entsize;
        break;
      }
    }
  }

  *count = (size_t) n;
  return (const Elf64_Sym *) (elf->data + offset);
}

int
lsp_elf_open (const char * path, LspElf * out)
{
  FILE * f;
  long len;
  unsigned char * data;
  const Elf64_Ehdr * ehdr;

  memset (out, 0, sizeof (*out));

  f = fopen (path, "rb");
  if (f == NULL)
    return -errno;

  if (fseek (f, 0, SEEK_END) != 0)
  {
    int err = errno;
    fclose (f);
    return -err;
  }

  len = ftell (f);
  if (len <= 0)
  {
    fclose (f);
    return -EINVAL;
  }

  rewind (f);
  data = malloc ((size_t) len);
  if (data == NULL)
  {
    fclose (f);
    return -ENOMEM;
  }

  if (fread (data, 1, (size_t) len, f) != (size_t) len)
  {
    free (data);
    fclose (f);
    return -EIO;
  }

  fclose (f);

  ehdr = (const Elf64_Ehdr *) data;
  if (memcmp (ehdr->e_ident, ELFMAG, SELFMAG) != 0 ||
      ehdr->e_ident[EI_CLASS] != ELFCLASS64)
  {
    free (data);
    return -ENOEXEC;
  }

  out->data = data;
  out->size = (size_t) len;
  return 0;
}

void
lsp_elf_close (LspElf * elf)
{
  free (elf->data);
  elf->data = NULL;
  elf->size = 0;
}

size_t
lsp_elf_symbol_count (const LspElf * elf)
{
  ElfDynInfo info;
  const Elf64_Sym * symbols;
  size_t count = 0;

  if (dyn_info_from_program_headers (elf, &info) != 0)
    return 0;

  symbols = symtab_pointer (elf, &info, &count);
  (void) symbols;
  return count;
}

bool
lsp_elf_find_export (const LspElf * elf, const char * name, uint64_t * value_out)
{
  ElfDynInfo info;
  const Elf64_Sym * symbols;
  const char * strings;
  size_t count = 0;
  size_t i;

  if (dyn_info_from_program_headers (elf, &info) != 0)
    return false;

  symbols = symtab_pointer (elf, &info, &count);
  strings = strtab_pointer (elf, &info);
  if (symbols == NULL || strings == NULL)
    return false;

  for (i = 1; i != count; i++)
  {
    const Elf64_Sym * sym = &symbols[i];
    unsigned int type, bind;

    if (sym->st_name >= info.strsz)
      break;
    if (sym->st_shndx == SHN_UNDEF)
      continue;

    type = ELF64_ST_TYPE (sym->st_info);
    if (!(type == STT_FUNC || type == STT_OBJECT
#if defined (STT_GNU_IFUNC)
          || type == STT_GNU_IFUNC
#endif
        ))
      continue;

    bind = ELF64_ST_BIND (sym->st_info);
    if (!(bind == STB_GLOBAL || bind == STB_WEAK))
      continue;

    if (strcmp (strings + sym->st_name, name) == 0)
    {
      *value_out = sym->st_value;
      return true;
    }
  }

  return false;
}

static uint64_t
scan_relocations (const LspElf * elf, const ElfDynInfo * info, uint64_t addr,
    uint64_t size, bool is_rela, const char * name, const Elf64_Sym * symbols,
    size_t symbol_count, const char * strings)
{
  uint64_t offset;
  size_t entry_size;
  size_t entries;
  size_t i;

  if (addr == 0 || size == 0)
    return 0;
  if (!vaddr_to_offset (elf, addr, &offset))
    return 0;

  entry_size = is_rela ? sizeof (Elf64_Rela) : sizeof (Elf64_Rel);
  entries = (size_t) (size / entry_size);

  for (i = 0; i != entries; i++)
  {
    const unsigned char * raw = elf->data + offset + (i * entry_size);
    uint64_t r_offset;
    uint64_t r_info;
    size_t sym_index;
    const Elf64_Sym * sym;

    if (is_rela)
    {
      const Elf64_Rela * rela = (const Elf64_Rela *) raw;
      r_offset = rela->r_offset;
      r_info = rela->r_info;
    }
    else
    {
      const Elf64_Rel * rel = (const Elf64_Rel *) raw;
      r_offset = rel->r_offset;
      r_info = rel->r_info;
    }

    sym_index = (size_t) ELF64_R_SYM (r_info);
    if (sym_index == 0 || sym_index >= symbol_count)
      continue;

    sym = &symbols[sym_index];
    if (sym->st_name >= info->strsz)
      continue;

    if (strcmp (strings + sym->st_name, name) == 0)
      return r_offset;
  }

  return 0;
}

bool
lsp_elf_find_import_slot (const LspElf * elf, const char * name, uint64_t * slot_out)
{
  ElfDynInfo info;
  const Elf64_Sym * symbols;
  const char * strings;
  size_t count = 0;
  uint64_t slot;

  if (dyn_info_from_program_headers (elf, &info) != 0)
    return false;

  symbols = symtab_pointer (elf, &info, &count);
  strings = strtab_pointer (elf, &info);
  if (symbols == NULL || strings == NULL)
    return false;

  slot = scan_relocations (elf, &info, info.rela, info.relasz, true, name,
      symbols, count, strings);
  if (slot == 0)
    slot = scan_relocations (elf, &info, info.rel, info.relsz, false, name,
        symbols, count, strings);
  if (slot == 0)
  {
    if (info.pltrel == DT_RELA)
      slot = scan_relocations (elf, &info, info.jmprel, info.pltrelsz, true, name,
          symbols, count, strings);
    else
      slot = scan_relocations (elf, &info, info.jmprel, info.pltrelsz, false, name,
          symbols, count, strings);
  }

  if (slot == 0)
    return false;

  *slot_out = slot;
  return true;
}

int
lsp_elf_text_segment (const LspElf * elf, uint64_t * vm_address,
    uint64_t * file_offset, uint64_t * file_size)
{
  const Elf64_Phdr * phdr = find_phdr (elf, PT_LOAD);
  const Elf64_Ehdr * ehdr = (const Elf64_Ehdr *) elf->data;
  uint16_t i;

  for (i = 0; i != ehdr->e_phnum; i++)
  {
    const Elf64_Phdr * candidate =
        (const Elf64_Phdr *) (elf->data + ehdr->e_phoff + (i * ehdr->e_phentsize));

    if (candidate->p_type == PT_LOAD && (candidate->p_flags & PF_X) != 0)
    {
      phdr = candidate;
      break;
    }
  }

  if (phdr == NULL || phdr->p_type != PT_LOAD || (phdr->p_flags & PF_X) == 0)
    return -ENOENT;

  *vm_address = phdr->p_vaddr;
  *file_offset = phdr->p_offset;
  *file_size = phdr->p_filesz;
  return 0;
}
