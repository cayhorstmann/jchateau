package io.jchateau;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.HostFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.types.ValType;
import io.quarkiverse.chicory.runtime.wasm.WasmQuarkusContext;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Service that converts Markdown to HTML using the {@code markdown.wasm} WebAssembly
 * module via the Quarkus Chicory extension.
 *
 * <p><strong>Why markdown.wasm and not pandoc.wasm?</strong>
 * The official {@code pandoc.wasm} binary (from the pandoc/pandoc-wasm npm package) is
 * compiled with the GHC WebAssembly backend and relies on WASM Exception Handling
 * (opcodes {@code try}/{@code catch}/{@code throw}).  That proposal is not yet supported
 * by Chicory 1.6.x / 1.7.x.  Once Chicory ships exception-handling support the service
 * can be updated to use pandoc.wasm with exactly the same integration pattern; see the
 * README for the planned migration path.
 *
 * <p>Instead we use <a href="https://github.com/nicolo-ribaudo/markdown-wasm">markdown-wasm</a>
 * (Emscripten-compiled cmark/C), which requires only a single host import
 * ({@code a.a} – Emscripten's heap-resize callback) and exports the high-level
 * {@code _parseUTF8} function.
 *
 * <h2>Initialisation sequence</h2>
 * <ol>
 *   <li>{@code c()} – {@code __wasm_call_ctors}: C/C++ global constructor chain.</li>
 *   <li>{@code d(0, 4)} – {@code wrealloc(null, 4)}: allocate the 4-byte result-pointer
 *       slot used by every subsequent {@code _parseUTF8} call.</li>
 * </ol>
 *
 * <h2>Per-conversion call sequence</h2>
 * <ol>
 *   <li>{@code d(0, len)} – allocate input buffer.</li>
 *   <li>Write markdown bytes into WASM linear memory at the returned address.</li>
 *   <li>{@code j(inputPtr, len, parseFlags, outputFlags, resultSlotPtr, 0)} –
 *       {@code _parseUTF8}: returns the output byte-length; writes the output
 *       pointer into the 4-byte result slot.</li>
 *   <li>Read output bytes from {@code *resultSlotPtr}.</li>
 *   <li>{@code e(inputPtr)} – free the input buffer.</li>
 * </ol>
 */
@ApplicationScoped
public class MarkdownService {

    /**
     * Default CommonMark parse flags (mirrors {@code ParseFlags.DEFAULT} in the JS wrapper).
     * Value: COLLAPSE_WHITESPACE | PERMISSIVE_ATX_HEADERS | PERMISSIVE_URL_AUTO_LINKS |
     *        PERMISSIVE_EMAIL_AUTO_LINKS | TABLES | STRIKETHROUGH | PERMISSIVE_WWW_AUTOLINKS |
     *        TASK_LISTS = 2823.
     */
    private static final long PARSE_FLAGS_DEFAULT = 2823L;

    /** Output flag: emit standard HTML (not XHTML). */
    private static final long OUTPUT_FLAGS_HTML = 1L;

    @Inject
    @Named("markdown")
    WasmQuarkusContext wasmContext;

    private Instance instance;
    private int resultSlotPtr;

    @PostConstruct
    void init() throws IOException {
        // Provide the single host import that Emscripten's standalone WASM needs:
        // a.a(newSize) → 1 on success, 0 on failure.
        HostFunction resizeHeap = new HostFunction(
                "a", "a",
                List.of(ValType.I32),
                List.of(ValType.I32),
                (inst, args) -> {
                    int requested = (int) args[0];
                    int current = inst.memory().pages() * 65536;
                    if (requested <= current) {
                        return new long[]{1L};
                    }
                    int pagesNeeded = (requested + 65535) / 65536;
                    int result = inst.memory().grow(pagesNeeded - inst.memory().pages());
                    return new long[]{result >= 0 ? 1L : 0L};
                });

        ImportValues imports = ImportValues.builder()
                .addFunction(resizeHeap)
                .build();

        instance = Instance.builder(wasmContext.getWasmModule())
                .withMachineFactory(wasmContext.getMachineFactory())
                .withStart(false)
                .withImportValues(imports)
                .build();

        // Initialise the C/C++ module (global constructors, stdlib init).
        instance.export("c").apply();

        // Allocate the 4-byte result-pointer slot that _parseUTF8 writes into.
        // wrealloc(null, 4) allocates fresh memory (ptr==0 means allocate).
        resultSlotPtr = (int) wrealloc(0, 4)[0];
    }

    /**
     * Converts a Markdown string to an HTML fragment.
     *
     * <p>The method is {@code synchronized} because the single WASM instance shares
     * linear memory across calls and is not thread-safe by itself.
     *
     * @param markdown the Markdown source
     * @return the rendered HTML fragment
     */
    public synchronized String convertToHtml(String markdown) {
        byte[] inputBytes = markdown.getBytes(StandardCharsets.UTF_8);

        // Allocate input buffer in WASM heap.
        int inputPtr = (int) wrealloc(0, inputBytes.length)[0];
        instance.memory().write(inputPtr, inputBytes);

        // j = _parseUTF8(inputPtr, inputLen, parseFlags, outputFlags, resultSlotPtr, callback=0)
        ExportFunction parseUtf8 = instance.export("j");
        long outputLen = parseUtf8.apply(
                (long) inputPtr,
                (long) inputBytes.length,
                PARSE_FLAGS_DEFAULT,
                OUTPUT_FLAGS_HTML,
                (long) resultSlotPtr,
                0L)[0];

        // Free the input buffer.
        instance.export("e").apply((long) inputPtr);

        // Read output pointer from the result slot and then read the HTML bytes.
        int outputPtr = instance.memory().readInt(resultSlotPtr);
        byte[] outputBytes = instance.memory().readBytes(outputPtr, (int) outputLen);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Calls {@code wrealloc(ptr, size)} – Emscripten's realloc export (export "d"). */
    private long[] wrealloc(long ptr, long size) {
        return instance.export("d").apply(ptr, size);
    }
}
