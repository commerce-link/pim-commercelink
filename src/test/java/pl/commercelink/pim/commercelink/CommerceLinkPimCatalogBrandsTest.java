package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.Brand;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
                      {"name":"Asus","aliases":["Asustek","asus"]},
                      {"name":"Apple","aliases":["apple"]},
                      {"name":"Phanteks","aliases":["phanteks"]}
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
    void allBrandsReturnsEmptyListBeforeRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);

        assertThat(catalog.allBrands()).isEmpty();
    }

    @Test
    void allBrandsReturnsBrandsFromEndpointAfterRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        List<Brand> brands = catalog.allBrands();
        assertThat(brands).hasSize(3);
        assertThat(brands.stream().map(Brand::name))
                .containsExactlyInAnyOrder("Asus", "Apple", "Phanteks");
        Brand asus = brands.stream().filter(b -> b.name().equals("Asus")).findFirst().orElseThrow();
        assertThat(asus.aliases()).containsExactlyInAnyOrder("Asustek", "asus");
    }
}
