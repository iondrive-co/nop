# Linux CEF malloc diagnostics

The pinned Linux x86-64 CEF runtime uses legacy glibc `mallinfo()`. Its signed
32-bit counters can overflow above 2 GiB, causing a checked conversion in
`MallocDumpProvider::OnMemoryDump()` to abort the process. nop does not need
process-wide malloc diagnostics.

`build-cef-runtime.py` disables the provider's existing
`emit_metrics_on_memory_dump_` flag while packaging the native dependency.
`OnMemoryDump()` already returns successfully without sampling when this flag
is false. This leaves memory allocation, safety checks, browser resources and
other dump providers intact. The change applies to every process loading the
packaged library, including JCEF helpers. It does not change installed binaries
or patch executable memory at runtime.

For the exact upstream binary pinned in `runtime.properties`:

- `MallocDumpProvider::GetInstance()` begins at virtual address `0x8e6ac30`.
- Its inlined constructor initializes the flag at `0x8e6ac73` with
  `c6 43 08 01` (`movb $1, 8(%rbx)`). The patch changes that initializer to
  `c6 43 08 00` (`movb $0, 8(%rbx)`).
- The executable ELF segment has file offsets 0x1000 below virtual addresses;
  the instruction's file offset is `0x8e69c73`.
- `OnMemoryDump()` starts at `0x8e6acf0`, reads the flag at `0x8e6ad1f`, and
  takes its successful return at `0x8e6aeb1` when the flag is false. The disabled
  path never reaches `mallinfo()` at `0x8e6ad4f`.

The packager verifies the entire original library's SHA-256, the instruction
bytes, and the entire resulting library's SHA-256. It refuses unknown versions
or architectures. Linux release installers currently target x86-64. A Linux
architecture or dependency update must supply a reviewed fix and pass the native
regression before packaging; it cannot silently ship the affected library.
Windows and macOS retain their upstream platform bundles.

The runtime cache directory includes the patch revision and library hash, and
nop verifies the extracted library before loading JNI. Browser profiles remain
in their existing separate location.

Run `xvfb-run -a ./gradlew testCefMemory testSlackSidebar` on Linux. The memory
regression allocates 2.5 GiB and 4.5 GiB through native malloc in an isolated JVM,
forces detailed CEF memory dumps, verifies that malloc virtual-size counters
are absent, and checks that other providers run in multiple browser processes.
These allocations reserve address space without touching the full payload.
The test uses an empty page and a fresh profile, with no sign-in.

References:

- [Chromium issue 401168177](https://issuetracker.google.com/issues/401168177)
- [CEF issue 3963](https://github.com/chromiumembedded/cef/issues/3963)
- [Pinned provider source](https://chromium.googlesource.com/chromium/src/+/152.0.7977.83/base/trace_event/malloc_dump_provider.cc)
- [Pinned provider header](https://chromium.googlesource.com/chromium/src/+/152.0.7977.83/base/trace_event/malloc_dump_provider.h)
