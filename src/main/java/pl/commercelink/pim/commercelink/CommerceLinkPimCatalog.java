package pl.commercelink.pim.commercelink;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import pl.commercelink.pim.api.*;
import pl.commercelink.pim.api.Brand;
import pl.commercelink.taxonomy.UnifiedProductIdentifiers;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;


public class CommerceLinkPimCatalog implements PimCatalog {

    private static final String SUBMIT_QUEUE_NAME = "pim-fetch-queue";
    private static final String CATEGORY_MATCH_QUEUE_NAME = "pim-category-match-queue";
    private static final String INDEX_PATH = "/PIM/Index?scope=all";
    private static final String BRANDS_PATH = "/PIM/Brands";
    private static final String CATEGORIES_PATH = "/PIM/Categories";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final SqsAsyncClient sqsAsyncClient;
    private final String pimIndexUrl;
    private final String pimBrandsUrl;
    private final String pimCategoriesUrl;
    private final String apiKey;
    private final String scopeKey;
    private final String submitQueueUrl;
    private final String categoryMatchQueueUrl;
    private final boolean prod;

    private final List<Consumer<PIMEntryAddedEvent>> addedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PIMEntryDeletedEvent>> deletedListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<CategoryMatchedEvent>> categoryMatchedListeners = new CopyOnWriteArrayList<>();

    private volatile PimEntryIndex index = PimEntryIndex.empty();
    private volatile List<Brand> brandsCache = List.of();
    private volatile List<PimCategory> categoriesCache = List.of();

    public CommerceLinkPimCatalog(String pimBaseUrl, String apiKey, boolean prod) {
        this((SqsAsyncClient) null, pimBaseUrl, apiKey, "", prod);
    }

    public CommerceLinkPimCatalog(String pimBaseUrl, String apiKey, String scopeKey, boolean prod) {
        this(null, pimBaseUrl, apiKey, scopeKey, prod);
    }

    public CommerceLinkPimCatalog(SqsAsyncClient sqsAsyncClient, String pimBaseUrl, String apiKey, boolean prod) {
        this(sqsAsyncClient, pimBaseUrl, apiKey, "", prod);
    }

    public CommerceLinkPimCatalog(SqsAsyncClient sqsAsyncClient, String pimBaseUrl, String apiKey, String scopeKey, boolean prod) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.sqsAsyncClient = sqsAsyncClient;
        this.pimIndexUrl = pimBaseUrl + INDEX_PATH;
        this.pimBrandsUrl = pimBaseUrl + BRANDS_PATH;
        this.pimCategoriesUrl = pimBaseUrl + CATEGORIES_PATH;
        this.apiKey = apiKey;
        this.scopeKey = scopeKey;
        this.submitQueueUrl = sqsAsyncClient != null
                ? sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(SUBMIT_QUEUE_NAME).build()).join().queueUrl()
                : null;
        this.categoryMatchQueueUrl = sqsAsyncClient != null
                ? sqsAsyncClient.getQueueUrl(GetQueueUrlRequest.builder().queueName(CATEGORY_MATCH_QUEUE_NAME).build()).join().queueUrl()
                : null;
        this.prod = prod;
    }

    @Override
    public void refresh() {
        List<PimEntry> entries = fetchJsonList(pimIndexUrl, new TypeReference<>() {}, "PIM index", scopeKey);
        System.out.println("Loaded " + entries.size() + " PIM entries into cache from " + pimIndexUrl);
        if (!entries.isEmpty()) {
            updateIndexCaches(entries);
        }

        List<Brand> brands = fetchJsonList(pimBrandsUrl, new TypeReference<>() {}, "brand cache", null);
        if (!brands.isEmpty()) {
            updateBrandsCache(brands);
            System.out.println("Loaded " + brands.size() + " brand mappings from " + pimBrandsUrl);
        }

        List<PimCategory> categories = fetchJsonList(pimCategoriesUrl, new TypeReference<>() {}, "category cache", null);
        if (!categories.isEmpty()) {
            updateCategoriesCache(categories);
            System.out.println("Loaded " + categories.size() + " categories from " + pimCategoriesUrl);
        }
    }

    private <T> List<T> fetchJsonList(String url, TypeReference<List<T>> type, String errorContext, String scopeKeyHeader) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("x-api-key", apiKey);
            }
            if (scopeKeyHeader != null && !scopeKeyHeader.isBlank()) {
                builder.header("x-pim-scope-key", scopeKeyHeader);
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
        this.index = new PimEntryIndex(entries, UnifiedProductIdentifiers::unifyEan, UnifiedProductIdentifiers::unifyMfn);
    }

    private void updateBrandsCache(List<Brand> brands) {
        this.brandsCache = List.copyOf(brands);
    }

    private void updateCategoriesCache(List<PimCategory> categories) {
        this.categoriesCache = List.copyOf(categories);
    }

    @Override
    public List<PimEntry> findAll() {
        return index.global().findAll();
    }

    @Override
    public Optional<PimEntry> findByPimId(String pimId) {
        return index.global().findByPimId(pimId);
    }

    @Override
    public Optional<PimEntry> findByGtin(String gtin) {
        return index.global().findByGtin(gtin);
    }

    @Override
    public Optional<PimEntry> findByMpn(String mpn) {
        return index.global().findByMpn(mpn);
    }

    @Override
    public Optional<PimEntry> findByGtinOrMpn(String gtin, String mpn) {
        return index.global().findByGtinOrMpn(gtin, mpn);
    }

    @Override
    public PimLookup forStore(String storeId) {
        // a snapshot of the current index: callers use it within one request
        return index.forStore(storeId);
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
    public void submitCategoryMatch(CategoryMatchRequest request) {
        if (sqsAsyncClient == null) {
            throw new IllegalStateException("Cannot submit category match request: SQS client not configured");
        }
        try {
            String body = objectMapper.writeValueAsString(request);
            sqsAsyncClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(categoryMatchQueueUrl)
                    .messageBody(body)
                    .build()).join();
        } catch (Exception e) {
            throw new RuntimeException("Failed to submit category match request", e);
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
    public void onCategoryMatched(Consumer<CategoryMatchedEvent> listener) {
        categoryMatchedListeners.add(listener);
    }

    @Override
    public void dispatch(Object event) {
        switch (event) {
            case PIMEntryAddedEvent e -> addedListeners.forEach(l -> l.accept(e));
            case PIMEntryDeletedEvent e -> deletedListeners.forEach(l -> l.accept(e));
            case CategoryMatchedEvent e -> categoryMatchedListeners.forEach(l -> l.accept(e));
            default -> {}
        }
    }

    @Override
    public List<Brand> allBrands() {
        return brandsCache;
    }

    @Override
    public List<PimCategory> allCategories() {
        return categoriesCache;
    }
}
