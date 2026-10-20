package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.commercelink.pim.api.PimEntry;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
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
    private final AtomicInteger liveIndexStatus = new AtomicInteger(200);
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

    // the index status can be switched between refreshes to simulate PIM failing after a good load
    private String startSwitchableServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/PIM/Index", exchange -> {
            int status = liveIndexStatus.get();
            respond(exchange, status, status == 200 ? INDEX : "{\"status\":" + status + "}");
        });
        server.createContext("/PIM/Brands", exchange -> respond(exchange, 200, "[]"));
        server.createContext("/PIM/Categories", exchange -> respond(exchange, 200, "[]"));
        server.start();
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void assertLoadedIndexSurvives(CommerceLinkPimCatalog catalog) {
        assertThat(catalog.findByPimId("g1")).isPresent();
        assertThat(catalog.forStore("s1").findByPimId("g1")).isPresent();
        assertThat(catalog.findAll()).extracting(PimEntry::pimId).containsExactly("g1");
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void refusedRefreshKeepsLoadedIndexInProd(int status) throws Exception {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(startSwitchableServer(), "", "k", true);
        catalog.refresh();
        assertLoadedIndexSurvives(catalog);

        liveIndexStatus.set(status);

        assertThatThrownBy(catalog::refresh).hasMessageContaining(String.valueOf(status));
        assertLoadedIndexSurvives(catalog);
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 503})
    void refusedRefreshKeepsLoadedIndexOutsideProd(int status) throws Exception {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(startSwitchableServer(), "", "k", false);
        catalog.refresh();
        assertLoadedIndexSurvives(catalog);

        liveIndexStatus.set(status);
        catalog.refresh();

        assertLoadedIndexSurvives(catalog);
        assertThat(logged()).contains(String.valueOf(status)).contains("/PIM/Index?scope=all");
    }
}
