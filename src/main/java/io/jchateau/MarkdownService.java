package io.jchateau;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasi.WasiPreview1_ModuleFactory;
import io.quarkiverse.chicory.runtime.wasm.WasmQuarkusContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Service that converts Markdown to HTML using the pandoc.wasm WebAssembly module
 * via the Quarkus Chicory extension.
 *
 * <p>pandoc.wasm is the official Pandoc binary compiled to wasm32-wasi. It exposes
 * a {@code convert(optsPtr, optsLen)} function that reads the input document from a
 * virtual file named {@code stdin} inside the preopened directory {@code /} and writes
 * the HTML output to a virtual file named {@code stdout} in the same directory.
 *
 * <p>Because the Haskell runtime system must be initialised before calling
 * {@code convert}, each {@link Instance} goes through a three-step startup:
 * <ol>
 *   <li>{@code __wasm_call_ctors()} – C/C++ global constructor chain</li>
 *   <li>{@code hs_init_with_rtsopts(argc*, argv*)} – Haskell RTS initialisation</li>
 *   <li>{@code convert(optsPtr, optsLen)} – the actual Pandoc conversion</li>
 * </ol>
 */
@ApplicationScoped
public class MarkdownService {

    /** RTS arguments forwarded to the Haskell runtime embedded in pandoc.wasm. */
    private static final String[] HS_INIT_ARGS = {
        "pandoc.wasm", "+RTS", "-H64m", "-RTS"
    };

    /**
     * Pandoc options JSON passed to {@code convert()}.
     * We request Markdown → HTML5 fragment output (no standalone DOCTYPE wrapper).
     */
    private static final String PANDOC_OPTS = "{\"from\":\"markdown\",\"to\":\"html5\"}";

    @Inject
    @Named("pandoc")
    WasmQuarkusContext wasmContext;

    /**
     * Converts a Markdown string to an HTML fragment.
     *
     * @param markdown the Markdown source
     * @return the rendered HTML
     * @throws IOException if a temporary file operation fails
     */
    public String convertToHtml(String markdown) throws IOException {
        Path workDir = Files.createTempDirectory("pandoc-work-");
        try {
            return doConvert(markdown, workDir);
        } finally {
            deleteDirectory(workDir);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String doConvert(String markdown, Path workDir) throws IOException {
        // Write Markdown to the virtual stdin file that pandoc.wasm reads.
        Files.writeString(workDir.resolve("stdin"), markdown, StandardCharsets.UTF_8);

        ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrCapture = new ByteArrayOutputStream();

        WasiOptions wasiOptions = WasiOptions.builder()
                .withArguments(Arrays.asList(HS_INIT_ARGS))
                // Map virtual root "/" to the temporary working directory so
                // pandoc.wasm can open /stdin and write /stdout.
                .withDirectory("/", workDir)
                .withStdin(InputStream.nullInputStream())
                .withStdout(stdoutCapture)
                .withStderr(stderrCapture)
                .build();

        try (WasiPreview1 wasi = WasiPreview1.builder().withOptions(wasiOptions).build()) {
            ImportValues imports = ImportValues.builder()
                    .addFunction(WasiPreview1_ModuleFactory.toHostFunctions(wasi))
                    .build();

            // Build a fresh Instance for this request.
            // The WasmModule is parsed once and cached by the quarkus-chicory extension;
            // only the execution environment is created anew here.
            Instance instance = Instance.builder(wasmContext.getWasmModule())
                    .withMachineFactory(wasmContext.getMachineFactory())
                    .withStart(false)  // do NOT call _start – we drive initialisation manually
                    .withImportValues(imports)
                    .build();

            // Step 1: module-level C/C++ constructors
            instance.export("__wasm_call_ctors").apply();

            // Step 2: Haskell RTS initialisation
            initHaskellRuntime(instance);

            // Step 3: run the conversion
            byte[] optsBytes = PANDOC_OPTS.getBytes(StandardCharsets.UTF_8);
            ExportFunction malloc = instance.export("malloc");
            long optsPtr = malloc.apply((long) optsBytes.length)[0];
            instance.memory().write((int) optsPtr, optsBytes);
            instance.export("convert").apply(optsPtr, (long) optsBytes.length);
        }

        // Read the HTML output that pandoc.wasm wrote to the /stdout virtual file.
        Path stdoutFile = workDir.resolve("stdout");
        if (Files.exists(stdoutFile)) {
            return Files.readString(stdoutFile, StandardCharsets.UTF_8);
        }

        // Fallback: some builds write to the fd-based stdout stream instead.
        String fallback = stdoutCapture.toString(StandardCharsets.UTF_8);
        if (!fallback.isBlank()) {
            return fallback;
        }

        throw new RuntimeException("pandoc produced no output. stderr: "
                + stderrCapture.toString(StandardCharsets.UTF_8));
    }

    /**
     * Initialises the Haskell RTS by calling {@code hs_init_with_rtsopts(argc*, argv*)}.
     *
     * <p>The function signature mirrors what the GHC WASM backend expects:
     * <pre>
     *   argc*  → pointer to an i32 containing the argument count
     *   argv*  → pointer to an i32 that itself points to the argv array
     * </pre>
     */
    private void initHaskellRuntime(Instance instance) {
        var memory = instance.memory();
        ExportFunction malloc = instance.export("malloc");

        // Allocate &argc and write the argument count.
        long argcPtr = malloc.apply(4L)[0];
        memory.writeI32((int) argcPtr, HS_INIT_ARGS.length);

        // Allocate the argv[] array (one extra slot for the null terminator).
        long argvArr = malloc.apply(4L * (HS_INIT_ARGS.length + 1))[0];
        for (int i = 0; i < HS_INIT_ARGS.length; i++) {
            byte[] argBytes = HS_INIT_ARGS[i].getBytes(StandardCharsets.UTF_8);
            long argPtr = malloc.apply((long) (argBytes.length + 1))[0];
            memory.write((int) argPtr, argBytes);
            memory.writeByte((int) (argPtr + argBytes.length), (byte) 0);
            memory.writeI32((int) (argvArr + 4L * i), (int) argPtr);
        }
        // Null-terminate the argv array.
        memory.writeI32((int) (argvArr + 4L * HS_INIT_ARGS.length), 0);

        // Allocate &argv and write the pointer to the argv array.
        long argvPtr = malloc.apply(4L)[0];
        memory.writeI32((int) argvPtr, (int) argvArr);

        instance.export("hs_init_with_rtsopts").apply(argcPtr, argvPtr);
    }

    private void deleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
