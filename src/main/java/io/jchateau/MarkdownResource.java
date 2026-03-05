package io.jchateau;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST resource that exposes the Markdown-to-HTML conversion endpoint.
 *
 * <p>The frontend ({@code index.html}) POSTs the raw Markdown text to
 * {@code POST /api/convert} and receives the rendered HTML fragment in the
 * response body, which is then injected into an {@code <iframe>} using the
 * {@code srcdoc} attribute.
 */
@Path("/api")
public class MarkdownResource {

    @Inject
    MarkdownService markdownService;

    /**
     * Converts a Markdown document to HTML.
     *
     * @param markdown the raw Markdown text submitted by the browser
     * @return an HTTP 200 response containing the rendered HTML, or HTTP 500
     *         on conversion failure
     */
    @POST
    @Path("/convert")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_HTML)
    public Response convert(String markdown) {
        try {
            String html = markdownService.convertToHtml(markdown);
            return Response.ok(html, MediaType.TEXT_HTML).build();
        } catch (Exception e) {
            return Response
                    .serverError()
                    .entity("<p>Conversion failed: " + e.getMessage() + "</p>")
                    .type(MediaType.TEXT_HTML)
                    .build();
        }
    }
}
