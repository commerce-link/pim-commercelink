package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.Category;

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
                      {"key":"CPU","displayName":"CPU","groupKey":"PcComponents"},
                      {"key":"Laptops","displayName":"Laptops","groupKey":"Computers"},
                      {"key":"Services","displayName":"Services","groupKey":"Services"}
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

        List<Category> categories = catalog.allCategories();
        assertThat(categories).hasSize(3);
        assertThat(categories.stream().map(Category::key))
                .containsExactlyInAnyOrder("CPU", "Laptops", "Services");
        Category laptops = categories.stream().filter(c -> c.key().equals("Laptops")).findFirst().orElseThrow();
        assertThat(laptops.displayName()).isEqualTo("Laptops");
        assertThat(laptops.groupKey()).isEqualTo("Computers");
    }
}
