package pl.commercelink.pim.commercelink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import pl.commercelink.pim.api.*;
import pl.commercelink.pim.api.BrandMapping;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static pl.commercelink.taxonomy.UnifiedProductIdentifiers.unifyEan;
import static pl.commercelink.taxonomy.UnifiedProductIdentifiers.unifyMfn;

public class CommerceLinkPimCatalog implements PimCatalog {

    private static final String SUBMIT_QUEUE_NAME = "pim-fetch-queue";
    private static final String INDEX_PATH = "/PIM/Index";
    private static final String BRANDS_PATH = "/PIM/Brands";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SqsAsyncClient sqsAsyncClient;
    private final String pimIndexUrl;
    private final String pimBrandsUrl;
    private final String apiKey;
    private final String submitQueueUrl;
    private final boolean prod;

    private final List<Consumer<PIMEntryAddedEvent>> addedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PIMEntryDeletedEvent>> deletedListeners = new CopyOnWriteArrayList<>();

    private Map<String, PimEntry> pimIdCache = new ConcurrentHashMap<>();
    private Map<String, PimEntry> gtinCache = new ConcurrentHashMap<>();
    private Map<String, PimEntry> mpnCache = new ConcurrentHashMap<>();
    private Map<String, BrandMapping> brandsCache = new ConcurrentHashMap<>();

    public CommerceLinkPimCatalog(String pimBaseUrl, String apiKey, boolean prod) {
        this(null, pimBaseUrl, apiKey, prod);
    }

    public CommerceLinkPimCatalog(SqsAsyncClient sqsAsyncClient, String pimBaseUrl, String apiKey, boolean prod) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.sqsAsyncClient = sqsAsyncClient;
        this.pimIndexUrl = pimBaseUrl + INDEX_PATH;
        this.pimBrandsUrl = pimBaseUrl + BRANDS_PATH;
        this.apiKey = apiKey;
        this.submitQueueUrl = sqsAsyncClient != null
                ? sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(SUBMIT_QUEUE_NAME).build()).join().queueUrl()
                : null;
        this.prod = prod;
    }

    @Override
    public void refresh() {
        List<PimEntry> entries = fetchJsonList(pimIndexUrl, new TypeReference<>() {}, "PIM index");
        System.out.println("Loaded " + entries.size() + " PIM entries into cache from " + pimIndexUrl);
        if (!entries.isEmpty()) {
            updateIndexCaches(entries);
        }

        List<BrandMapping> brands = fetchJsonList(pimBrandsUrl, new TypeReference<>() {}, "brand cache");
        if (!brands.isEmpty()) {
            updateBrandsCache(brands);
            System.out.println("Loaded " + brands.size() + " brand mappings from " + pimBrandsUrl);
        }
    }

    private <T> List<T> fetchJsonList(String url, TypeReference<List<T>> type, String errorContext) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), type);
            }
            return List.of();
        } catch (Exception e) {
            if (prod) {
                throw new RuntimeException("Failed to refresh " + errorContext + " from " + url, e);
            }
            return List.of();
        }
    }

    private void updateIndexCaches(List<PimEntry> entries) {
        Map<String, PimEntry> newPimIdCache = new ConcurrentHashMap<>();
        Map<String, PimEntry> newGtinCache = new ConcurrentHashMap<>();
        Map<String, PimEntry> newMpnCache = new ConcurrentHashMap<>();
        for (PimEntry entry : entries) {
            newPimIdCache.put(entry.pimId(), entry);
            entry.identifiers().forEach(id -> {
                if (id.type() == PimIdentifierType.GTIN) {
                    newGtinCache.put(id.value(), entry);
                } else if (id.type() == PimIdentifierType.MPN) {
                    newMpnCache.put(id.value(), entry);
                }
            });
        }
        synchronized (this) {
            this.pimIdCache = newPimIdCache;
            this.gtinCache = newGtinCache;
            this.mpnCache = newMpnCache;
        }
    }

    private void updateBrandsCache(List<BrandMapping> brands) {
        Map<String, BrandMapping> newBrandsCache = new ConcurrentHashMap<>();
        for (BrandMapping mapping : brands) {
            newBrandsCache.put(mapping.alias().toLowerCase(), mapping);
        }
        synchronized (this) {
            this.brandsCache = newBrandsCache;
        }
    }

    @Override
    public List<PimEntry> findAll() {
        return new ArrayList<>(pimIdCache.values());
    }

    @Override
    public Optional<PimEntry> findByPimId(String pimId) {
        return Optional.ofNullable(pimIdCache.get(pimId));
    }

    @Override
    public Optional<PimEntry> findByGtin(String gtin) {
        String unified = unifyEan(gtin);
        if (unified == null || unified.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(gtinCache.get(unified));
    }

    @Override
    public Optional<PimEntry> findByMpn(String mpn) {
        String unified = unifyMfn(mpn);
        if (unified == null || unified.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mpnCache.get(unified));
    }

    @Override
    public Optional<PimEntry> findByGtinOrMpn(String gtin, String mpn) {
        Optional<PimEntry> entry = findByGtin(gtin);
        if (entry.isEmpty()) {
            entry = findByMpn(mpn);
        }
        return entry;
    }

    @Override
    public void submit(PimEntryRequest request) {
        if (sqsAsyncClient == null) {
            throw new IllegalStateException("Cannot submit PIM entry request: SQS client not configured");
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(submitQueueUrl)
                    .messageBody(body)
                    .build()).join();
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit PIM entry request", e);
        }
    }

    @Override
    public void onEntryAdded(Consumer<PIMEntryAddedEvent> listener) {
        addedListeners.add(listener);
    }

    @Override
    public void onEntryDeleted(Consumer<PIMEntryDeletedEvent> listener) {
        deletedListeners.add(listener);
    }

    @Override
    public void dispatch(Object event) {
        switch (event) {
            case PIMEntryAddedEvent e -> addedListeners.forEach(l -> l.accept(e));
            case PIMEntryDeletedEvent e -> deletedListeners.forEach(l -> l.accept(e));
            default -> {}
        }
    }

    @Override
    public String unifyBrand(String raw) {
        if (raw == null) {
            return null;
        }
        BrandMapping mapping = brandsCache.get(raw.toLowerCase());
        return mapping != null ? mapping.canonicalName() : raw;
    }

    @Override
    public int brandStrength(String brand) {
        if (brand == null) {
            return 1;
        }
        BrandMapping mapping = brandsCache.get(brand.toLowerCase());
        if (mapping != null) {
            return mapping.strength();
        }
        for (BrandMapping m : brandsCache.values()) {
            if (m.canonicalName().equalsIgnoreCase(brand)) {
                return m.strength();
            }
        }
        return 1;
    }
}
