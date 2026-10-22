# Runtime third-party notices

This file describes the **reproducible runtime input**, including licenses that are
not permissive. The runtime is assembled from the Termux `termux-main` stable
`aarch64` repository; the build script records downloaded index and `.deb` SHA-256
values and package versions in its temporary build log. Source repository:
[termux-packages](https://github.com/termux/termux-packages), with the matching
package recipes and version used by the repository snapshot. Do not describe this
runtime as GPL-free.

- **Node.js / DSH JavaScript packages**: Node.js and the pinned `@deepseek-ai/dsh`
  `0.2.1-rc.2` dependency tree. Preserve each package's own LICENSE/NOTICE files.
  DSH source is MIT; dependency licenses remain authoritative.
- **bash and readline**: GNU Bash and GNU Readline are **GPL-3.0-or-later**.
  Their corresponding source and complete license text are available from the
  Termux package recipes/source downloads for the exact package versions recorded
  by the build. These GPL components are intentionally shipped because the runtime
  exposes `bin/bash`; redistribution is under their GPL terms.
- **ncurses, libiconv, termux-tools, ripgrep, PCRE2, OpenSSL, c-ares, ICU,
  SQLite, zlib, libc++, libuv, Brotli, and Android support libraries**: retain
  their upstream package notices and licenses from the exact Termux packages.
  They include permissive, BSD, ISC, Unicode, OpenSSL, and zlib terms as applicable.
- **flock.c and Node-API headers**: vendored under `third_party/runtime/`; MIT
  license text is in `third_party/runtime/LICENSE` and
  `third_party/runtime/node-api-headers/LICENSE`.
- **dsh_pty_exec.c**: project source; its source header and the project LICENSE
  govern redistribution.

The Android system libraries (`libc.so`, `libdl.so`, `libm.so`, `liblog.so`, and
related platform libraries) are supplied by the device and are not redistributed
in this ZIP. `su`, `sudo`, `doas`, and `tsu` are deliberately removed; no
privilege-escalation helper is part of this runtime.
