package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.PimCategory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommerceLinkPimCatalogCategoriesTest {

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
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/PIM/Categories", exchange -> {
            String json = """
                    [
                      {"id":"151","parentId":"150","name":"Notebooki/laptopy","lang":"pl"},
                      {"id":"107","parentId":null,"name":"Telekomunikacja i nawigacja","lang":"pl"}
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
    void allCategoriesReturnsEmptyListBeforeRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);

        assertThat(catalog.allCategories()).isEmpty();
    }

    @Test
    void allCategoriesReturnsCategoriesFromEndpointAfterRefresh() {
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(
                "http://localhost:" + port, "", false);
        catalog.refresh();

        List<PimCategory> categories = catalog.allCategories();
        assertThat(categories).hasSize(2);
        PimCategory laptops = categories.stream().filter(c -> c.id().equals("151")).findFirst().orElseThrow();
        assertThat(laptops.parentId()).isEqualTo("150");
        assertThat(laptops.name()).isEqualTo("Notebooki/laptopy");
        assertThat(laptops.lang()).isEqualTo("pl");
        PimCategory topLevel = categories.stream().filter(c -> c.id().equals("107")).findFirst().orElseThrow();
        assertThat(topLevel.topLevel()).isTrue();
    }
}
