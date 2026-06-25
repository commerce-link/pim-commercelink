package pl.commercelink.pim.commercelink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.Category;

import java.io.IOException;
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
        server.createContext("/PIM/Index", exchange -> respond(exchange, "[]"));
        server.createContext("/PIM/Brands", exchange -> respond(exchange, "[]"));
        server.createContext("/PIM/Categories", exchange -> respond(exchange, """
                [
                  {"key":"CPU","displayName":"CPU","groupKey":"PcComponents"},
                  {"key":"Laptops","displayName":"Laptops","groupKey":"Computers"},
                  {"key":"Services","displayName":"Services","groupKey":"Services"}
                ]
                """));
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
        assertThat(categories).extracting(Category::key)
                .containsExactlyInAnyOrder("CPU", "Laptops", "Services");
        Category cpu = categories.stream().filter(c -> c.key().equals("CPU")).findFirst().orElseThrow();
        assertThat(cpu.displayName()).isEqualTo("CPU");
        assertThat(cpu.groupKey()).isEqualTo("PcComponents");
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
