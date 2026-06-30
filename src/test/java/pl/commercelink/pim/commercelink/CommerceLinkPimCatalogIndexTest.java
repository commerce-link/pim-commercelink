package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.PimEntry;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceLinkPimCatalogIndexTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.createContext("/PIM/Index", exchange -> {
            String json = """
                    [
                      {
                        "pimId": "pim-cpu",
                        "identifiers": [
                          {"value": "MFN-CLEAR-01", "type": "MPN"},
                          {"value": "5900000000001", "type": "GTIN"}
                        ],
                        "brand": "AMD",
                        "name": "AMD ClearEdge Pro X3D",
                        "category": "CPU",
                        "subcategory": "Desktop",
                        "approved": true,
                        "netWeightInGrams": 120,
                        "grossWeightInGrams": 250
                      },
                      {
                        "pimId": "pim-tail",
                        "identifiers": [
                          {"value": "MFN-LEASH-99", "type": "MPN"}
                        ],
                        "brand": "Acme",
                        "name": "Smart Dog Leash",
                        "category": "Wireless Dog Leash",
                        "subcategory": "Pets",
                        "approved": true,
                        "netWeightInGrams": 80,
                        "grossWeightInGrams": 100
                      }
                    ]
                    """;
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/PIM/Brands", exchange -> {
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
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
    void deserializesEnumNameCategoryAsStringOverHttp() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        PimEntry entry = catalog.findByGtinOrMpn("5900000000001", "MFN-CLEAR-01").orElseThrow();
        assertThat(entry.category()).isEqualTo("CPU");
    }

    @Test
    void preservesNonEnumCategoryVerbatimOverHttp() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        PimEntry entry = catalog.findByMpn("MFN-LEASH-99").orElseThrow();
        assertThat(entry.category()).isEqualTo("Wireless Dog Leash");
    }
}
