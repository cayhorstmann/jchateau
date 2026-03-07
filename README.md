# jchateau — Markdown-to-HTML with Quarkus + GraalVM WebAssembly

A demo application that converts Markdown to HTML entirely on the server using the
official [pandoc.wasm](https://github.com/pandoc/pandoc-wasm) binary running inside
the JVM via [GraalVM's WebAssembly engine (GraalWasm)](https://www.graalvm.org/latest/reference-manual/wasm/).

![Demo screenshot](https://github.com/user-attachments/assets/0ab7720f-8ab7-44ba-86b5-463e6d2a0b9d)

## Architecture

```
Browser (index.html)
  │  POST /api/convert   (text/plain Markdown)
  ▼
Quarkus REST endpoint (MarkdownResource)
  │
  ▼
MarkdownService ──► GraalVM Polyglot Context (pandoc.wasm / GHC WASM)
                          │  wasm.Builtins=wasi_snapshot_preview1
                          │  wasm.WasiMapDirs=/::<tmpDir>
                          │  1. __wasm_call_ctors()
                          │  2. hs_init_with_rtsopts(argc, argv)
                          │  3. write markdown → <tmpDir>/stdin
                          │  4. convert(optsPtr, optsLen)
                          │  5. read <tmpDir>/stdout → HTML
  │
  ▼
HTML fragment returned to browser and displayed in an <iframe>
```

## Quick Start

```bash
# 1. Clone the repository
git clone https://github.com/cayhorstmann/jchateau.git
cd jchateau

# 2. Download pandoc.wasm (runs automatically; requires curl + tar)
mvn generate-resources

# 3. Start in development mode (live reload)
mvn quarkus:dev

# 4. Open http://localhost:8080 in your browser
```

Paste Markdown into the left pane, click **Convert** (or press **Ctrl+Enter**),
and the rendered HTML appears in the iframe on the right.

## Building a production JAR

```bash
mvn package
java -jar target/quarkus-app/quarkus-run.jar
```

## WebAssembly module — pandoc.wasm

The application uses the official **pandoc.wasm** binary (Pandoc 3.9, ~56 MB)
distributed via the [`pandoc-wasm`](https://www.npmjs.com/package/pandoc-wasm)
npm package.  It is compiled from the full
[Pandoc](https://pandoc.org) Haskell source by the GHC WebAssembly backend and
targets `wasm32-wasi`.

The binary only requires `wasi_snapshot_preview1` host imports, which GraalWasm
satisfies automatically via the `wasm.Builtins` option.

### WASM / WASI interface

| Step | Call | Purpose |
|------|------|---------|
| Init | `__wasm_call_ctors()` | WASM module-level constructors |
| Init | `hs_init_with_rtsopts(&argc, &argv)` | Start GHC Haskell runtime |
| Convert | write `<wasiDir>/stdin` | Provide Markdown input via WASI FS |
| Convert | `convert(optsPtr, optsLen)` | Run pandoc with JSON options |
| Convert | read `<wasiDir>/stdout` | Collect HTML output via WASI FS |

The options JSON `{"from":"markdown","to":"html"}` is allocated in WASM linear
memory once at startup and reused for all conversions.

### pandoc.wasm is gitignored — automatic download

`src/main/resources/pandoc.wasm` is excluded from git (≈56 MB).  The Maven
build downloads it automatically during the `generate-resources` phase using
`curl` + `tar` from the npm registry:

```bash
mvn generate-resources   # downloads pandoc.wasm if missing
```

You can also download it manually:

```bash
npm install pandoc-wasm
cp node_modules/pandoc-wasm/src/pandoc.wasm src/main/resources/pandoc.wasm
```

## GraalWasm version requirement

pandoc.wasm is compiled with the GHC WebAssembly backend, which emits the
[WASM Exception Handling](https://github.com/WebAssembly/exception-handling)
proposal (section&nbsp;13 "tag" + `try`/`catch`/`throw` opcodes).  This proposal
is required by the Haskell exception machinery used throughout Pandoc.

| Runtime | Exception Handling | Status |
|---------|-------------------|--------|
| Chicory 1.x | ❌ Not supported | Previous implementation |
| GraalWasm 25.0.x | ❌ Not supported | Current Maven Central release |
| GraalWasm 25.1.0+ | ✅ Supported | Pending Maven Central release |

Until GraalWasm 25.1.0 is available on Maven Central, the service starts in a
**degraded mode**: `MarkdownService.isAvailable()` returns `false` and
conversions return HTTP 503.  Tests are automatically skipped with an
`assumeTrue` assumption failure.

**To activate full functionality:** update `graalvm.version` in `pom.xml`
to `25.1.0` (or later) once that version appears on Maven Central.

## Project structure

```
src/main/java/io/jchateau/
  MarkdownService.java   – GraalVM Polyglot / pandoc.wasm integration
  MarkdownResource.java  – JAX-RS REST endpoint (POST /api/convert)

src/main/resources/
  application.properties             – Quarkus config
  pandoc.wasm                        – Pandoc WASM binary (gitignored; auto-downloaded)
  META-INF/resources/index.html      – Single-page UI

src/test/java/io/jchateau/
  MarkdownResourceTest.java  – Integration tests (skipped until GraalWasm 25.1.0)
```

## Key dependencies

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `io.quarkus:quarkus-rest` | 3.30.6 | JAX-RS REST layer |
| `org.graalvm.polyglot:polyglot` | 24.2.2 | GraalVM Polyglot API |
| `org.graalvm.polyglot:wasm-community` | 24.2.2 | GraalWasm WASM engine |
