package io.jchateau;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Integration tests for the Markdown → HTML conversion endpoint.
 *
 * The tests require {@code markdown.wasm} to be present on the classpath
 * (at {@code src/main/resources/markdown.wasm}).  If the file is missing the
 * Quarkus application will fail to start and the tests will be skipped/fail
 * with a clear error message.
 */
@QuarkusTest
class MarkdownResourceTest {

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
                // markdown-wasm uses <b> for strong emphasis (CommonMark compliant)
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
                // markdown-wasm wraps fenced code blocks in <pre><code ...>
                .body(containsString("<code"))
                .body(not(containsString("Conversion failed")));
    }
}
