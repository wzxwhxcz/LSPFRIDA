/*
 * Mock libstagefright.so for the host E2E.  Its last executable mapping page
 * is the payload landing page (design §1 / §3.4).  Nothing calls into it after
 * injection, so the payload may overwrite the page.
 */
volatile unsigned long mock_stagefright_data[512] = { 1, 2, 3, 4, 5, 6, 7, 8 };

int
mock_stagefright_pad0 (int x)
{
  return x * 3 + 1;
}

int
mock_stagefright_pad1 (int x)
{
  return mock_stagefright_pad0 (x) ^ 0x5a5a;
}

int
mock_stagefright_pad2 (int x)
{
  return mock_stagefright_pad1 (x) + (int) mock_stagefright_data[0];
}
