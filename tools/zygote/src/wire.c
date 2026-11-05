#define _GNU_SOURCE

#include "wire.h"

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static socklen_t
build_abstract_address (const char * name, struct sockaddr_un * addr)
{
  size_t len = strlen (name);

  memset (addr, 0, sizeof (*addr));
  addr->sun_family = AF_UNIX;
  addr->sun_path[0] = '\0';
  if (len > sizeof (addr->sun_path) - 2)
    len = sizeof (addr->sun_path) - 2;
  memcpy (addr->sun_path + 1, name, len);

  return (socklen_t) (offsetof (struct sockaddr_un, sun_path) + 1 + len);
}

int
lsp_wire_read_hello (int fd, LspHello * out)
{
  unsigned char header[LSP_WIRE_HEADER_SIZE];
  size_t got = 0;

  memset (out, 0, sizeof (*out));

  while (got != sizeof (header))
  {
    ssize_t n = read (fd, header + got, sizeof (header) - got);
    if (n <= 0)
      return n == 0 ? -ECONNRESET : -errno;
    got += (size_t) n;
  }

  memcpy (&out->pid, header + 0, 4);
  memcpy (&out->ppid, header + 4, 4);
  memcpy (&out->name_len, header + 8, 4);

  if (out->name_len >= LSP_WIRE_NAME_MAX)
    return -EINVAL;

  got = 0;
  while (got != out->name_len)
  {
    ssize_t n = read (fd, out->name + got, out->name_len - got);
    if (n <= 0)
      return n == 0 ? -ECONNRESET : -errno;
    got += (size_t) n;
  }
  out->name[out->name_len] = '\0';

  return 0;
}

int
lsp_wire_write_hello (int fd, uint32_t pid, uint32_t ppid, const char * name)
{
  unsigned char header[LSP_WIRE_HEADER_SIZE];
  uint32_t name_len = (uint32_t) strlen (name);

  memcpy (header + 0, &pid, 4);
  memcpy (header + 4, &ppid, 4);
  memcpy (header + 8, &name_len, 4);

  if (write (fd, header, sizeof (header)) != (ssize_t) sizeof (header))
    return -errno;
  if (name_len != 0 && write (fd, name, name_len) != (ssize_t) name_len)
    return -errno;

  return 0;
}

int
lsp_wire_send_ack (int fd)
{
  unsigned char ack = LSP_WIRE_ACK_BYTE;

  if (write (fd, &ack, 1) != 1)
    return -errno;

  return 0;
}

int
lsp_wire_listen (const char * name)
{
  int fd;
  struct sockaddr_un addr;
  socklen_t addrlen;

  fd = socket (AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd == -1)
    return -errno;

  addrlen = build_abstract_address (name, &addr);
  if (bind (fd, (const struct sockaddr *) &addr, addrlen) != 0)
  {
    int err = errno;
    close (fd);
    return -err;
  }

  if (listen (fd, 8) != 0)
  {
    int err = errno;
    close (fd);
    return -err;
  }

  return fd;
}

int
lsp_wire_connect_timeout (const char * name, int timeout_ms)
{
  int fd;
  struct sockaddr_un addr;
  socklen_t addrlen;
  int flags;

  fd = socket (AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
  if (fd == -1)
    return -errno;

  flags = fcntl (fd, F_GETFL, 0);
  fcntl (fd, F_SETFL, flags | O_NONBLOCK);

  addrlen = build_abstract_address (name, &addr);
  if (connect (fd, (const struct sockaddr *) &addr, addrlen) != 0)
  {
    if (errno != EINPROGRESS)
    {
      int err = errno;
      close (fd);
      return -err;
    }

    {
      struct pollfd pfd;
      int ready;

      pfd.fd = fd;
      pfd.events = POLLOUT;
      pfd.revents = 0;
      ready = poll (&pfd, 1, timeout_ms);
      if (ready <= 0)
      {
        close (fd);
        return ready == 0 ? -ETIMEDOUT : -errno;
      }
    }
  }

  fcntl (fd, F_SETFL, flags);

  return fd;
}

int
lsp_wire_connect (const char * name)
{
  return lsp_wire_connect_timeout (name, 1000);
}

char *
lsp_wire_json_escape (const char * in, char * out, size_t out_size)
{
  size_t used = 0;

  if (out_size == 0)
    return out;

  for (; *in != '\0' && used + 7 < out_size; in++)
  {
    unsigned char c = (unsigned char) *in;

    switch (c)
    {
      case '"':  out[used++] = '\\'; out[used++] = '"'; break;
      case '\\': out[used++] = '\\'; out[used++] = '\\'; break;
      case '\n': out[used++] = '\\'; out[used++] = 'n'; break;
      case '\r': out[used++] = '\\'; out[used++] = 'r'; break;
      case '\t': out[used++] = '\\'; out[used++] = 't'; break;
      default:
        if (c < 0x20)
        {
          static const char digits[] = "0123456789abcdef";
          out[used++] = '\\';
          out[used++] = 'u';
          out[used++] = '0';
          out[used++] = '0';
          out[used++] = digits[c >> 4];
          out[used++] = digits[c & 0xf];
        }
        else
        {
          out[used++] = (char) c;
        }
        break;
    }
  }

  out[used] = '\0';
  return out;
}
