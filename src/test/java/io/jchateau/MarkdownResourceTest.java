package io.jchateau;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests for the Markdown → HTML conversion endpoint backed by pandoc.wasm.
 *
 * <p>The tests require {@code pandoc.wasm} to be present on the classpath
 * (at {@code src/main/resources/pandoc.wasm}) <em>and</em> require a GraalWasm version
 * that supports the WASM Exception Handling proposal.
 *
 * <p>GraalWasm added exception handling support in version&nbsp;25.1.0.  Until that
 * release is available on Maven Central, all tests in this class are automatically
 * <em>skipped</em> with an informative assumption failure rather than a hard failure.
 *
 * <p>To download pandoc.wasm, run: {@code mvn generate-resources}
 */
@QuarkusTest
class MarkdownResourceTest {

    @Inject
    MarkdownService markdownService;

    /**
     * Skip all tests when the service is not yet available.
     * This happens when GraalWasm < 25.1.0 is in use and cannot parse pandoc.wasm
     * (which requires the WASM Exception Handling proposal, section&nbsp;13).
     */
    @BeforeEach
    void assumeServiceAvailable() {
        assumeTrue(markdownService.isAvailable(),
                "pandoc.wasm is not available with this GraalWasm version. "
                + "Reason: " + markdownService.getUnavailableReason()
                + ". Upgrade graalvm.version to 25.1.0+ once available on Maven Central.");
    }

    @Test
    void convertBasicMarkdown() {
        given()
                .contentType("text/plain")
                .body("# Hello\n\nThis is **bold**.")
                .when()
                .post("/api/convert")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("<h1"))
                .body(containsString("Hello"))
                // pandoc uses <strong> for strong emphasis
                .body(containsString("<strong>"))
                .body(containsString("bold"));
    }

    @Test
    void convertEmptyMarkdown() {
        given()
                .contentType("text/plain")
                .body("")
                .when()
                .post("/api/convert")
                .then()
                .statusCode(200);
    }

    @Test
    void convertMarkdownWithList() {
        String markdown = "- Item 1\n- Item 2\n- Item 3\n";
        given()
                .contentType("text/plain")
                .body(markdown)
                .when()
                .post("/api/convert")
                .then()
                .statusCode(200)
                .body(containsString("<ul>"))
                .body(containsString("<li>"));
    }

    @Test
    void convertMarkdownWithCodeBlock() {
        String markdown = "```java\nSystem.out.println(\"Hello\");\n```\n";
        given()
                .contentType("text/plain")
                .body(markdown)
                .when()
                .post("/api/convert")
                .then()
                .statusCode(200)
                // pandoc wraps fenced code blocks in <pre><code class="sourceCode java">
                .body(containsString("<code"))
                .body(not(containsString("Conversion failed")));
    }
}
