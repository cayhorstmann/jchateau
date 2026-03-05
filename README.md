# jchateau — Markdown-to-HTML with Quarkus + Chicory + WebAssembly

A demo application that converts Markdown to HTML entirely on the server using a
WebAssembly module running inside the JVM via the
[Chicory](https://chicory.dev) runtime and the
[quarkus-chicory](https://github.com/quarkiverse/quarkus-chicory) Quarkus extension.

![Demo screenshot](https://github.com/user-attachments/assets/0ab7720f-8ab7-44ba-86b5-463e6d2a0b9d)

## Architecture

```
Browser (index.html)
  │  POST /api/convert   (text/plain Markdown)
  ▼
Quarkus REST endpoint (MarkdownResource)
  │
  ▼
MarkdownService  ──► Chicory Instance (markdown.wasm / Emscripten)
                           │  host import: a.a(newSize) → heap-resize callback
                           │  1. c()  – __wasm_call_ctors
                           │  2. d(0, 4) – allocate result-pointer slot
                           │  3. j(inputPtr, len, flags, …) – _parseUTF8
                      writes output to WASM linear memory
  │
  ▼
HTML fragment returned to browser and displayed in an <iframe>
```

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/cayhorstmann/jchateau.git
cd jchateau

# 2. Start in development mode (live reload)
mvn quarkus:dev

# 3. Open http://localhost:8080 in your browser
```

Paste Markdown into the left pane, click **Convert** (or press **Ctrl+Enter**),
and the rendered HTML appears in the iframe on the right.

## Building a production JAR

```bash
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

## WebAssembly module — markdown.wasm vs pandoc.wasm

### Current implementation — markdown-wasm

The application uses
[markdown-wasm](https://github.com/nicolo-ribaudo/markdown-wasm) v1.2.0
— an Emscripten-compiled build of the
[cmark](https://github.com/commonmark/cmark) C library.  It is CommonMark
compliant, supports GitHub Flavoured Markdown extensions (tables, task lists,
strikethrough), and is only **56 KB**.

It requires a single host import:

| Import | Signature | Purpose |
|--------|-----------|---------|
| `a.a` | `(i32) → i32` | Emscripten heap-resize callback |

And exposes the following exports used by the service:

| Export | Alias | Purpose |
|--------|-------|---------|
| `c` | `__wasm_call_ctors` | C/C++ module-level constructors |
| `d` | `_wrealloc(ptr, size)` | Heap allocator |
| `e` | `_wfree(ptr)` | Heap deallocator |
| `j` | `_parseUTF8(…)` | Markdown → HTML conversion |

### Future upgrade — pandoc.wasm

The intended target is the official
[pandoc.wasm](https://github.com/pandoc/pandoc-wasm) binary (≈ 56 MB), which is
the full [Pandoc](https://pandoc.org) document converter compiled to `wasm32-wasi`
by the GHC WebAssembly backend.

**Current blocker:** pandoc.wasm is compiled with the GHC WASM backend which
generates WASM Exception Handling opcodes (`try`/`catch`/`throw`, opcode `0x06`).
Chicory 1.7.x does not yet support this proposal.  It is tracked on the
[Chicory roadmap](https://github.com/dylibso/chicory#roadmap).

Once Chicory adds exception-handling support, the migration requires only:

1. Replace `markdown.wasm` with `pandoc.wasm` in `src/main/resources/`.
2. Update `application.properties`:
   ```properties
   quarkus.chicory.modules.markdown.wasm-resource=pandoc.wasm
   quarkus.chicory.modules.markdown.name=io.jchateau.PandocModule
   ```
3. Rewrite `MarkdownService` to use the pandoc WASI interface:
   ```java
   // Write markdown to /stdin in the preopened directory
   Files.writeString(workDir.resolve("stdin"), markdown);
   // Set up WasiPreview1 with the working directory
   WasiPreview1 wasi = WasiPreview1.builder()
       .withOptions(WasiOptions.builder()
           .withArguments(List.of("pandoc.wasm", "+RTS", "-H64m", "-RTS"))
           .withDirectory("/", workDir)
           .build())
       .build();
   // Create instance, call __wasm_call_ctors + hs_init_with_rtsopts
   // Call convert({"from":"markdown","to":"html5"})
   // Read HTML from /stdout
   ```

The `WasmQuarkusContext` injection, Chicory `Instance` builder, and REST layer
remain identical for both modules.

## Project structure

```
src/main/java/io/jchateau/
  MarkdownService.java   – Chicory / markdown.wasm integration
  MarkdownResource.java  – JAX-RS REST endpoint (POST /api/convert)

src/main/resources/
  application.properties         – Quarkus + quarkus-chicory config
  markdown.wasm                  – CommonMark parser (Emscripten/cmark, ~56 KB)
  META-INF/resources/index.html  – Single-page UI

src/test/java/io/jchateau/
  MarkdownResourceTest.java  – Integration tests
```

## Key dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `io.quarkus:quarkus-rest` | 3.30.6 | JAX-RS REST layer |
| `io.quarkiverse.chicory:quarkus-chicory` | 0.0.1 | Quarkus Chicory extension |
| `com.dylibso.chicory:runtime` | 1.6.1 | WebAssembly Instance / Memory API |
