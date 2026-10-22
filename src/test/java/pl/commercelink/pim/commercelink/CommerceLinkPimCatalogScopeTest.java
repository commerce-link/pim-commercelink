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

    private final AtomicReference<String> indexScopeHeader = new AtomicReference<>();
    private final AtomicReference<String> brandsScopeHeader = new AtomicReference<>();
    private final AtomicReference<String> categoriesScopeHeader = new AtomicReference<>();

    private CommerceLinkPimCatalog catalogServing(String payload) throws Exception {
        return startServer(payload, null);
    }

    private CommerceLinkPimCatalog catalogServing(String payload, String scopeKey) throws Exception {
        return startServer(payload, scopeKey);
    }

    private CommerceLinkPimCatalog startServer(String payload, String scopeKey) throws Exception {
        if (server != null) {
            server.stop(0); // a test that starts a second server must not leak the first
        }
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/PIM/Index", exchange -> {
            seenPath.set(exchange.getRequestURI().getPath());
            seenQuery.set(exchange.getRequestURI().getQuery());
            indexScopeHeader.set(exchange.getRequestHeaders().getFirst("x-pim-scope-key"));
            respond(exchange, payload);
        });
        server.createContext("/PIM/Brands", exchange -> {
            brandsScopeHeader.set(exchange.getRequestHeaders().getFirst("x-pim-scope-key"));
            respond(exchange, "[]");
        });
        server.createContext("/PIM/Categories", exchange -> {
            categoriesScopeHeader.set(exchange.getRequestHeaders().getFirst("x-pim-scope-key"));
            respond(exchange, "[]");
        });
        server.start();
        String baseUrl = "http://localhost:" + server.getAddress().getPort();
        CommerceLinkPimCatalog catalog = scopeKey == null
                ? new CommerceLinkPimCatalog(baseUrl, "", false)
                : new CommerceLinkPimCatalog(baseUrl, "", scopeKey, false);
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

        assertThat(catalog.withGlobalData().findByGtin("590")).map(PimEntry::pimId).contains("g1");
        assertThat(catalog.withGlobalData().findByGtin("591")).isEmpty();
        assertThat(catalog.withGlobalData().findAll()).extracting(PimEntry::pimId).containsExactly("g1");
    }

    @Test
    void storeViewPrefersTheStoresOwnEntry() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing(OWNED_PAYLOAD);

        assertThat(catalog.withStoreData("s1").findByGtin("590")).map(PimEntry::pimId).contains("s1-1");
        assertThat(catalog.withStoreData("s2").findByGtin("590")).map(PimEntry::pimId).contains("g1");
        assertThat(catalog.withStoreData("s2").findByPimId("s1-2")).isEmpty();
    }

    @Test
    void indexOfAnOldPimWithoutOwnersIsAllGlobal() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing(OLD_PAYLOAD);

        assertThat(catalog.withGlobalData().findByGtin("590")).isPresent();
        assertThat(catalog.withStoreData("s1").findByGtin("590")).isPresent();
    }

    @Test
    void lookupNormalizesTheIdentifier() throws Exception {
        CommerceLinkPimCatalog catalog = catalogServing("""
                [{"pimId": "n1", "identifiers": [
                   {"value": "5901234567890", "type": "GTIN"},
                   {"value": "MFN-NORM-1", "type": "MPN"}],
                  "brand": "B", "name": "N", "approved": true}]
                """);

        assertThat(catalog.withGlobalData().findByGtin("0005901234567890")).map(PimEntry::pimId).contains("n1");
        assertThat(catalog.withGlobalData().findByMpn(" mfn-norm-1 ")).map(PimEntry::pimId).contains("n1");
        assertThat(catalog.withStoreData("s1").findByMpn(" mfn-norm-1 ")).map(PimEntry::pimId).contains("n1");
    }

    @Test
    void indexRequestCarriesTheScopeKey() throws Exception {
        catalogServing(OWNED_PAYLOAD, "k");

        assertThat(seenQuery.get()).isEqualTo("scope=all");
        assertThat(indexScopeHeader.get()).isEqualTo("k");
    }

    @Test
    void brandsAndCategoriesDoNotCarryTheScopeKey() throws Exception {
        catalogServing(OWNED_PAYLOAD, "k");

        assertThat(brandsScopeHeader.get()).isNull();
        assertThat(categoriesScopeHeader.get()).isNull();
    }

    @Test
    void blankScopeKeySendsNoHeader() throws Exception {
        catalogServing(OWNED_PAYLOAD, "  ");
        assertThat(indexScopeHeader.get()).isNull();

        catalogServing(OWNED_PAYLOAD);
        assertThat(indexScopeHeader.get()).isNull();
    }

    @Test
    void descriptorPassesTheScopeKeyFromTheSecret() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/PIM/Index", exchange -> {
            indexScopeHeader.set(exchange.getRequestHeaders().getFirst("x-pim-scope-key"));
            respond(exchange, OWNED_PAYLOAD);
        });
        server.createContext("/PIM/Brands", exchange -> respond(exchange, "[]"));
        server.createContext("/PIM/Categories", exchange -> respond(exchange, "[]"));
        server.start();

        new CommerceLinkPimDescriptor().create(java.util.Map.of(
                "apiDomain", "http://localhost:" + server.getAddress().getPort(),
                "scopeKey", "k")).refresh();

        assertThat(indexScopeHeader.get()).isEqualTo("k");
    }
}
