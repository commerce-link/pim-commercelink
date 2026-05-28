package pl.commercelink.pim.commercelink;

import pl.commercelink.pim.api.*;
import pl.commercelink.provider.api.EventBinding;
import pl.commercelink.provider.api.EventBinding.QueueBinding;
import pl.commercelink.provider.api.ProviderField;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;

import java.util.List;
import java.util.Map;

public class CommerceLinkPimDescriptor implements PimCatalogDescriptor {

    @Override
    public String name() {
        return "commercelink-pim";
    }

    @Override
    public String displayName() {
        return "CommerceLink PIM";
    }

    @Override
    public List<ProviderField> configurationFields() {
        return List.of(
                new ProviderField("apiDomain", "API Domain", ProviderField.FieldType.URL, true, "https://api.example.com"),
                new ProviderField("apiKey", "API Key", ProviderField.FieldType.PASSWORD, false, ""),
                new ProviderField("env", "Environment", ProviderField.FieldType.TEXT, true, "prod")
        );
    }

    @Override
    public PimCatalog create(Map<String, String> configuration) {
        return new CommerceLinkPimCatalog(
                configuration.getOrDefault("apiDomain", ""),
                configuration.getOrDefault("apiKey", ""),
                "prod".equals(configuration.getOrDefault("env", "localhost"))
        );
    }

    @Override
    public PimCatalog create(Map<String, String> configuration, Map<String, Object> context) {
        SqsAsyncClient sqsAsyncClient = (SqsAsyncClient) context.get("sqsAsyncClient");
        return new CommerceLinkPimCatalog(
                sqsAsyncClient,
                configuration.getOrDefault("apiDomain", ""),
                configuration.getOrDefault("apiKey", ""),
                "prod".equals(configuration.getOrDefault("env", "localhost"))
        );
    }

    @Override
    public List<EventBinding<?>> bindings() {
        return List.of(
                new QueueBinding<>("pim-entry-added-queue", PIMEntryAddedEvent.class),
                new QueueBinding<>("pim-entry-deleted-queue", PIMEntryDeletedEvent.class)
        );
    }
}
