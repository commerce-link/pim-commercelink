package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.PimEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// a non-200 answer of PIM (a wrong scope key gives 403) must never turn into a silent empty catalog
class CommerceLinkPimCatalogStatusTest {

    private static final String INDEX = """
            [{"pimId": "g1", "identifiers": [{"value": "590", "type": "GTIN", "primary": true}],
              "brand": "B", "name": "Global", "approved": true}]
            """;

    private HttpServer server;
    private final AtomicReference<String> indexScopeHeader = new AtomicReference<>();
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    private PrintStream originalErr;

    @BeforeEach
    void captureLog() {
        originalErr = System.err;
        System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void restore() {
        System.setErr(originalErr);
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(int indexStatus, int brandsStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/PIM/Index", exchange -> {
            indexScopeHeader.set(exchange.getRequestHeaders().getFirst("x-pim-scope-key"));
            respond(exchange, indexStatus, indexStatus == 200 ? INDEX : "{\"status\":" + indexStatus + "}");
        });
        server.createContext("/PIM/Brands", exchange -> respond(exchange, brandsStatus, "[]"));
        server.createContext("/PIM/Categories", exchange -> respond(exchange, 200, "[]"));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private String logged() {
        return log.toString(StandardCharsets.UTF_8);
    }

    @Test
    void scopeKeyIsSentWithoutSurroundingWhitespace() throws Exception {
        new CommerceLinkPimCatalog(startServer(200, 200), "", " k\n", false).refresh();

        assertThat(indexScopeHeader.get()).isEqualTo("k");
    }

    @Test
    void refusedIndexIsLoggedWithStatusAndPathOutsideProd() throws Exception {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(startServer(403, 200), "", "k", false);

        catalog.refresh();

        assertThat(catalog.findAll()).isEmpty();
        assertThat(logged()).contains("403").contains("/PIM/Index?scope=all");
    }

    // a rolling deploy then fails its health check instead of serving an empty catalog
    @Test
    void refusedIndexFailsTheRefreshInProd() throws Exception {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(startServer(403, 200), "", "k", true);

        assertThatThrownBy(catalog::refresh)
                .hasMessageContaining("403")
                .hasMessageContaining("/PIM/Index");
        assertThat(logged()).contains("403").contains("/PIM/Index?scope=all");
    }

    @Test
    void failingBrandsAreLoggedButDoNotFailTheRefreshInProd() throws Exception {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(startServer(200, 500), "", "k", true);

        catalog.refresh();

        assertThat(catalog.findAll()).extracting(PimEntry::pimId).containsExactly("g1");
        assertThat(logged()).contains("500").contains("/PIM/Brands");
    }
}
