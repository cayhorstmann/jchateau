# jchateau — Markdown-to-HTML with Quarkus + Chicory + pandoc.wasm

A demo application that converts Markdown to HTML entirely on the server
using the official [pandoc.wasm](https://github.com/pandoc/pandoc-wasm) binary
running inside the JVM via the [Chicory](https://chicory.dev) WebAssembly runtime
and the [quarkus-chicory](https://github.com/quarkiverse/quarkus-chicory) Quarkus
extension.

## Architecture

```
Browser (index.html)
  │  POST /api/convert   (text/plain Markdown)
  ▼
Quarkus REST endpoint (MarkdownResource)
  │
  ▼
MarkdownService  ──► Chicory Instance (pandoc.wasm / wasm32-wasi)
                           │  WasiPreview1 (preopened dir "/" → tmp)
                           │  1. __wasm_call_ctors()
                           │  2. hs_init_with_rtsopts()
                           │  3. convert({"from":"markdown","to":"html5"})
                      reads /stdin, writes /stdout
  │
  ▼
HTML fragment returned to browser and displayed in an <iframe>
```

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.9+ |

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

## How it works

### WebAssembly module — pandoc.wasm

`pandoc.wasm` is the official [Pandoc](https://pandoc.org) document converter
compiled to `wasm32-wasi` by the
[pandoc/pandoc-wasm](https://github.com/pandoc/pandoc-wasm) project.

The module exports:

| Export | Purpose |
|--------|---------|
| `__wasm_call_ctors()` | C/C++ module-level constructors |
| `hs_init_with_rtsopts(argc*, argv*)` | Haskell RTS initialisation |
| `convert(optsPtr, optsLen)` | Document conversion (reads `/stdin`, writes `/stdout`) |
| `malloc(size)` | Memory allocator |

### Quarkus Chicory extension

The [quarkus-chicory](https://github.com/quarkiverse/quarkus-chicory) extension
(v0.0.1) parses `pandoc.wasm` **once** at startup and caches the resulting
`WasmModule`.  For every conversion request `MarkdownService` creates a fresh
`Instance` backed by this cached module, wires in Chicory's `WasiPreview1`
host-function bindings, and invokes the three-step initialisation sequence
described above.

The WASI environment maps the virtual root directory `"/"` to a per-request
temporary directory.  The input Markdown is written to `<tmpDir>/stdin` before
calling `convert`, and the output HTML is read from `<tmpDir>/stdout` afterwards.

## Project structure

```
src/main/java/io/jchateau/
  MarkdownService.java   – Chicory / pandoc.wasm integration
  MarkdownResource.java  – JAX-RS REST endpoint (POST /api/convert)

src/main/resources/
  application.properties         – Quarkus + quarkus-chicory config
  pandoc.wasm                    – pandoc binary (wasm32-wasi, ~56 MB)
  META-INF/resources/index.html  – Single-page UI

src/test/java/io/jchateau/
  MarkdownResourceTest.java  – Integration tests
```

## Key dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `io.quarkus:quarkus-rest` | 3.30.6 | JAX-RS REST layer |
| `io.quarkiverse.chicory:quarkus-chicory` | 0.0.1 | Quarkus Chicory extension |
| `com.dylibso.chicory:wasi` | 1.6.1 | WASI host-function implementations |
| `com.dylibso.chicory:runtime` | 1.6.1 | WebAssembly Instance / Memory API |
