package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.PimEntry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceLinkPimCatalogScopeTest {

    private static final String OWNED_PAYLOAD = """
            [
              {"pimId": "g1", "identifiers": [{"value": "590", "type": "GTIN", "primary": true}],
               "brand": "B", "name": "Global", "approved": true},
              {"pimId": "s1-1", "identifiers": [{"value": "590", "type": "GTIN", "primary": true}],
               "brand": "B", "name": "Store one", "approved": true, "ownerStoreId": "s1"},
              {"pimId": "s1-2", "identifiers": [{"value": "591", "type": "GTIN", "primary": true}],
               "brand": "B", "name": "Store two", "approved": true, "ownerStoreId": "s1"}
            ]
            """;

    private static final String OLD_PAYLOAD = """
            [
              {"pimId": "old", "identifiers": [{"value": "590", "type": "GTIN"}],
               "brand": "B", "name": "Old", "approved": true}
            ]
            """;

    private HttpServer server;
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private final AtomicReference<String> seenQuery = new AtomicReference<>();

    private CommerceLinkPimCatalog catalogServing(String payload) throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/PIM/Index", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenQuery.set(exchange.getRequestURI().getQuery());
            respond(exchange, payload);
        });
        server.createContext("/PIM/Brands", exchange -> respond(exchange, "[]"));
        server.createContext("/PIM/Categories", exchange -> respond(exchange, "[]"));
        server.start();
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + server.getAddress().getPort(), "", false);
        catalog.refresh();
        return catalog;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void requestsTheIndexWithAllScopes() throws Exception {
        catalogServing(OWNED_PAYLOAD);

        assertThat(seenPath.get()).isEqualTo("/PIM/Index");
        assertThat(seenQuery.get()).isEqualTo("scope=all");
    }

    @Test
    void catalogLookupsSeeOnlyGlobalEntries() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing(OWNED_PAYLOAD);

        assertThat(catalog.findByGtin("590")).map(PimEntry::pimId).contains("g1");
        assertThat(catalog.findByGtin("591")).isEmpty();
        assertThat(catalog.findAll()).extracting(PimEntry::pimId).containsExactly("g1");
    }

    @Test
    void storeViewPrefersTheStoresOwnEntry() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing(OWNED_PAYLOAD);

        assertThat(catalog.forStore("s1").findByGtin("590")).map(PimEntry::pimId).contains("s1-1");
        assertThat(catalog.forStore("s2").findByGtin("590")).map(PimEntry::pimId).contains("g1");
        assertThat(catalog.forStore("s2").findByPimId("s1-2")).isEmpty();
    }

    @Test
    void indexOfAnOldPimWithoutOwnersIsAllGlobal() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing(OLD_PAYLOAD);

        assertThat(catalog.findByGtin("590")).isPresent();
        assertThat(catalog.forStore("s1").findByGtin("590")).isPresent();
    }

    @Test
    void lookupNormalizesTheIdentifier() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing("""
                [{"pimId": "n1", "identifiers": [
                   {"value": "5901234567890", "type": "GTIN"},
                   {"value": "MFN-NORM-1", "type": "MPN"}],
                  "brand": "B", "name": "N", "approved": true}]
                """);

        assertThat(catalog.findByGtin("0005901234567890")).map(PimEntry::pimId).contains("n1");
        assertThat(catalog.findByMpn(" mfn-norm-1 ")).map(PimEntry::pimId).contains("n1");
        assertThat(catalog.forStore("s1").findByMpn(" mfn-norm-1 ")).map(PimEntry::pimId).contains("n1");
    }
}
