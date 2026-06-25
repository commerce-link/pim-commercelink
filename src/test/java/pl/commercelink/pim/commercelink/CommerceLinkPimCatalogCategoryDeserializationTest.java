package pl.commercelink.pim.commercelink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.PimEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CommerceLinkPimCatalogCategoryDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String INDEX_WITH_UNKNOWN_CATEGORY = """
            [{"pimId":"p1",
              "identifiers":[{"value":"5901234567890","type":"GTIN"}],
              "brand":"B","name":"N",
              "category":"Cables356k",
              "subcategory":"sub","approved":true,
              "netWeightInGrams":100,"grossWeightInGrams":200}]
            """;

    @Test
    void unknownCategoryTokenDoesNotBrickTheIndexRefresh() {
        // when / then
        assertThatCode(() -> objectMapper.readValue(
                INDEX_WITH_UNKNOWN_CATEGORY, new TypeReference<List<PimEntry>>() {}))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownCategoryTokenIsCarriedVerbatimAsCategoryKey() throws Exception {
        // when
        List<PimEntry> entries = objectMapper.readValue(
                INDEX_WITH_UNKNOWN_CATEGORY, new TypeReference<List<PimEntry>>() {});

        // then
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).categoryKey()).isEqualTo("Cables356k");
        assertThat(entries.get(0).subcategory()).isEqualTo("sub");
    }

    @Test
    void serializedWireKeyStaysCategoryAndSubcategoryIsPreserved() throws Exception {
        // given
        PimEntry entry = new PimEntry("p1", List.of(), "B", "N", "Laptops", "sub", true, null, null);

        // when
        String json = objectMapper.writeValueAsString(entry);

        // then
        assertThat(json).contains("\"category\":\"Laptops\"");
        assertThat(json).contains("\"subcategory\":\"sub\"");
        assertThat(json).doesNotContain("categoryKey");
    }
}
