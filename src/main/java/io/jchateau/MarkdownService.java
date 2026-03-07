package io.jchateau;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.ByteSequence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * Service that converts Markdown to HTML using {@code pandoc.wasm} via GraalVM WebAssembly.
 *
 * <p>pandoc.wasm is the official pandoc binary compiled for WebAssembly using the GHC
 * WebAssembly backend.  The GHC backend emits the WASM
 * <a href="https://github.com/WebAssembly/exception-handling">Exception Handling</a>
 * proposal (section&nbsp;13 "tag" + opcodes {@code try}/{@code catch}/{@code throw}) which is
 * needed for Haskell's exception machinery.  The Chicory runtime does not support this
 * proposal, hence the migration to GraalVM (GraalWasm).
 *
 * <h2>GraalWasm version requirement</h2>
 * <p>Exception Handling support was added in <strong>GraalWasm 25.1.0</strong>
 * (see the GraalWasm
 * <a href="https://github.com/oracle/graal/blob/master/wasm/CHANGELOG.md">CHANGELOG</a>).
 * Maven Central only carries up to 25.0.2 at the time of writing, which does
 * <em>not</em> yet include this feature.  When 25.1.0 is published to Maven Central,
 * update {@code graalvm.version} in {@code pom.xml} to 25.1.0 (or later) and this
 * service will become fully functional.
 *
 * <p>Until then the service starts in a <em>degraded</em> state: {@link #isAvailable()}
 * returns {@code false} and {@link #convertToHtml} throws
 * {@link UnsupportedOperationException}.
 *
 * <h2>Binary source</h2>
 * <p>The pandoc.wasm binary is distributed via the {@code pandoc-wasm} npm package
 * (version&nbsp;1.0.1, Pandoc&nbsp;3.9).  It is gitignored because of its size (~56&nbsp;MB)
 * and is downloaded automatically during {@code mvn generate-resources}.
 *
 * <h2>WASM / WASI interface</h2>
 * <p>The binary uses only {@code wasi_snapshot_preview1} imports – GraalWasm satisfies
 * these automatically when {@code wasm.Builtins=wasi_snapshot_preview1} is set.
 * All document I/O goes through a WASI preopened directory that is
 * mapped to a temporary directory on the host:
 * <ul>
 *   <li>Before each conversion, the markdown is written to {@code <wasiDir>/stdin}.</li>
 *   <li>Pandoc reads options from a JSON blob passed via WASM linear memory and reads
 *       the document from {@code /stdin} (the preopened root).</li>
 *   <li>After conversion, the HTML output is read from {@code <wasiDir>/stdout}.</li>
 * </ul>
 *
 * <h2>Initialisation sequence (once at startup)</h2>
 * <ol>
 *   <li>Parse the WASM binary and instantiate the module.</li>
 *   <li>{@code __wasm_call_ctors()} – WASM module-level constructors (C/Haskell globals).</li>
 *   <li>{@code hs_init_with_rtsopts(argc_ptr, argv_ptr)} – start the GHC runtime with
 *       RTS options that cap initial heap usage ({@code +RTS -H64m -RTS}).</li>
 * </ol>
 *
 * <h2>Per-conversion sequence</h2>
 * <ol>
 *   <li>Write the markdown bytes to {@code <wasiDir>/stdin}.</li>
 *   <li>Call {@code convert(opts_ptr, opts_len)} with a pointer to the JSON options
 *       {@code {"from":"markdown","to":"html"}} in WASM linear memory.</li>
 *   <li>Read the HTML from {@code <wasiDir>/stdout}.</li>
 * </ol>
 */
@ApplicationScoped
public class MarkdownService {

    private static final Logger LOG = Logger.getLogger(MarkdownService.class.getName());

    /** Pandoc options JSON for a Markdown → HTML fragment conversion. */
    private static final byte[] OPTS_JSON =
            "{\"from\":\"markdown\",\"to\":\"html\"}".getBytes(StandardCharsets.UTF_8);

    /** GHC RTS arguments: cap initial heap at 64 MB to avoid OOM on first allocation. */
    private static final String[] GHC_RTS_ARGS = {"pandoc.wasm", "+RTS", "-H64m", "-RTS"};

    private Context context;
    private Value exports;
    private Path wasiDir;
    private int optsPtr;

    /**
     * {@code true} once pandoc.wasm has been successfully loaded and initialised.
     * {@code false} if the binary could not be parsed (e.g. GraalWasm < 25.1.0).
     */
    private boolean available = false;

    /** Human-readable reason why the service is not available, if any. */
    private String unavailableReason;

    @PostConstruct
    void init() {
        try {
            doInit();
        } catch (Exception e) {
            // Non-fatal: log the problem and continue in degraded mode.
            unavailableReason = e.getMessage();
            LOG.severe("pandoc.wasm could not be initialised – conversion will be unavailable. "
                    + "Reason: " + e.getMessage()
                    + "\nThis is expected with GraalWasm < 25.1.0 because pandoc.wasm uses "
                    + "WASM Exception Handling (section 13 / tag), which was added in "
                    + "GraalWasm 25.1.0. Update graalvm.version in pom.xml to 25.1.0+ "
                    + "once that release is available on Maven Central.");
        }
    }

    private void doInit() throws IOException {
        // Load pandoc.wasm from the classpath (auto-downloaded during generate-resources).
        byte[] wasmBytes;
        try (InputStream is = getClass().getResourceAsStream("/pandoc.wasm")) {
            if (is == null) {
                throw new IllegalStateException(
                        "pandoc.wasm not found on the classpath. "
                        + "Run 'mvn generate-resources' to download it, or manually place "
                        + "the file at src/main/resources/pandoc.wasm "
                        + "(source: npm install pandoc-wasm && "
                        + "cp node_modules/pandoc-wasm/src/pandoc.wasm src/main/resources/)");
            }
            wasmBytes = is.readAllBytes();
        }

        // Create a temporary directory for the WASI preopened "/" directory.
        wasiDir = Files.createTempDirectory("pandoc-wasi-");

        // Build a GraalVM context with WASI enabled and the temp dir mounted as "/".
        // "wasm.Builtins=wasi_snapshot_preview1" tells GraalWasm to provide the
        // standard WASI host imports automatically.
        context = Context.newBuilder("wasm")
                .option("wasm.Builtins", "wasi_snapshot_preview1")
                .option("wasm.WasiMapDirs", "/::" + wasiDir.toAbsolutePath())
                .option("engine.WarnInterpreterOnly", "false")
                .allowAllAccess(true)
                .build();

        // Parse + instantiate the WASM module; exports are returned as a polyglot Value.
        // NOTE: This step will throw PolyglotException("invalid section ID: 13") on
        // GraalWasm < 25.1.0 because that version does not support the WASM Exception
        // Handling proposal (section 13 "tag").
        Source source = Source.newBuilder("wasm", ByteSequence.create(wasmBytes), "pandoc")
                .build();
        exports = context.eval(source);

        // 1. Run WASM module-level constructors (Haskell + C global initialisers).
        exports.getMember("__wasm_call_ctors").executeVoid();

        // 2. Start the GHC Haskell runtime with RTS memory options.
        initGhcRuntime();

        // 3. Pre-allocate the conversion options string in WASM memory once.
        //    The same options {"from":"markdown","to":"html"} are reused for all calls.
        Value malloc = exports.getMember("malloc");
        optsPtr = malloc.execute((long) OPTS_JSON.length).asInt();
        Value memory = exports.getMember("memory");
        for (int i = 0; i < OPTS_JSON.length; i++) {
            memory.writeBufferByte(optsPtr + i, OPTS_JSON[i]);
        }

        available = true;
        LOG.info("pandoc.wasm initialised successfully via GraalVM (wasiDir=" + wasiDir + ")");
    }

    /**
     * Returns {@code true} when pandoc.wasm has been successfully loaded and the
     * service can perform conversions.
     */
    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns a human-readable explanation of why the service is not available,
     * or {@code null} if it is available.
     */
    public String getUnavailableReason() {
        return unavailableReason;
    }

    /**
     * Converts a Markdown string to an HTML fragment.
     *
     * <p>The method is {@code synchronized} because the GraalVM {@link Context} and the
     * WASI temp directory are shared state that must not be accessed concurrently.
     *
     * @param markdown the Markdown source
     * @return the rendered HTML fragment (no {@code <html>} wrapper)
     * @throws UnsupportedOperationException if the service is not available
     * @throws IOException if reading/writing the WASI files fails
     */
    public synchronized String convertToHtml(String markdown) throws IOException {
        if (!available) {
            throw new UnsupportedOperationException(
                    "pandoc.wasm is not available: " + unavailableReason);
        }

        Path stdinPath  = wasiDir.resolve("stdin");
        Path stdoutPath = wasiDir.resolve("stdout");

        // Write the input markdown to the WASI "/stdin" file.
        Files.writeString(stdinPath, markdown, StandardCharsets.UTF_8);
        // Remove any leftover output from a previous call.
        Files.deleteIfExists(stdoutPath);

        // Call pandoc's convert() export with the pre-allocated options pointer.
        exports.getMember("convert").execute((long) optsPtr, (long) OPTS_JSON.length);

        // Read the HTML output from the WASI "/stdout" file.
        if (!Files.exists(stdoutPath)) {
            LOG.warning("pandoc.wasm did not produce a stdout file – returning empty string");
            return "";
        }
        return Files.readString(stdoutPath, StandardCharsets.UTF_8);
    }

    @PreDestroy
    void cleanup() {
        if (context != null) {
            try {
                context.close();
            } catch (Exception e) {
                LOG.warning("Error closing GraalVM context: " + e.getMessage());
            }
        }
        if (wasiDir != null) {
            try {
                Files.walk(wasiDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                LOG.warning("Could not delete WASI temp file " + p + ": " + e.getMessage());
                            }
                        });
            } catch (IOException e) {
                LOG.warning("Error cleaning up WASI temp dir: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Starts the GHC Haskell runtime inside the WASM module.
     *
     * <p>Replicates the JavaScript initialisation in {@code pandoc-wasm/src/core.js}:
     * allocates {@code argc}/{@code argv} in WASM linear memory and calls
     * {@code hs_init_with_rtsopts(&argc, &argv)}.
     */
    private void initGhcRuntime() {
        Value malloc = exports.getMember("malloc");
        Value memory = exports.getMember("memory");

        // Allocate and populate argv string pointers.
        int argvPtr = malloc.execute((long) (4 * (GHC_RTS_ARGS.length + 1))).asInt();
        for (int i = 0; i < GHC_RTS_ARGS.length; i++) {
            int argPtr = writeString(malloc, memory, GHC_RTS_ARGS[i]);
            writeI32LE(memory, argvPtr + 4 * i, argPtr);
        }
        // Null-terminate argv.
        writeI32LE(memory, argvPtr + 4 * GHC_RTS_ARGS.length, 0);

        // &argc (pointer to int holding the count)
        int argcPtr = malloc.execute((long) 4).asInt();
        writeI32LE(memory, argcPtr, GHC_RTS_ARGS.length);

        // &argv (pointer to the argv pointer itself – hs_init_with_rtsopts takes char***)
        int argvPtrPtr = malloc.execute((long) 4).asInt();
        writeI32LE(memory, argvPtrPtr, argvPtr);

        exports.getMember("hs_init_with_rtsopts").execute((long) argcPtr, (long) argvPtrPtr);
    }

    /** Copies a null-terminated UTF-8 string into WASM linear memory via {@code malloc}. */
    private int writeString(Value malloc, Value memory, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        int ptr = malloc.execute((long) (bytes.length + 1)).asInt();
        for (int i = 0; i < bytes.length; i++) {
            memory.writeBufferByte(ptr + i, bytes[i]);
        }
        memory.writeBufferByte(ptr + bytes.length, (byte) 0);
        return ptr;
    }

    /** Writes a 32-bit little-endian integer into WASM linear memory at {@code offset}. */
    private void writeI32LE(Value memory, int offset, int value) {
        memory.writeBufferByte(offset,     (byte)  (value         & 0xFF));
        memory.writeBufferByte(offset + 1, (byte) ((value  >>  8) & 0xFF));
        memory.writeBufferByte(offset + 2, (byte) ((value  >> 16) & 0xFF));
        memory.writeBufferByte(offset + 3, (byte) ((value  >> 24) & 0xFF));
    }
}
