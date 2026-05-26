package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceLinkPimCatalogBrandsTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/PIM/Index", exchange -> {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/PIM/Brands", exchange -> {
            String json = """
                    [
                      {"alias":"Asustek","canonicalName":"Asus","strength":2},
                      {"alias":"Apple","canonicalName":"Apple","strength":2},
                      {"alias":"Phanteks","canonicalName":"Phanteks","strength":1}
                    ]
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void unifyBrandReturnsCanonicalAfterRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        assertThat(catalog.unifyBrand("Asustek")).isEqualTo("Asus");
        assertThat(catalog.unifyBrand("ASUSTEK")).isEqualTo("Asus");  // case-insensitive
        assertThat(catalog.unifyBrand("UnknownBrand")).isEqualTo("UnknownBrand");
        assertThat(catalog.unifyBrand(null)).isNull();
    }

    @Test
    void brandStrengthReturnsStrengthAfterRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        assertThat(catalog.brandStrength("Apple")).isEqualTo(2);
        assertThat(catalog.brandStrength("Phanteks")).isEqualTo(1);
        assertThat(catalog.brandStrength("UnknownBrand")).isEqualTo(1);
        assertThat(catalog.brandStrength(null)).isEqualTo(1);
    }

    @Test
    void unifyBrandPassesThroughBeforeRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        // No refresh — cache empty

        assertThat(catalog.unifyBrand("Asustek")).isEqualTo("Asustek");
        assertThat(catalog.brandStrength("Apple")).isEqualTo(1);
    }
}
