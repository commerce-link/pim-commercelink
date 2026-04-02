# pim-commercelink

CommerceLink PIM client library — an implementation of [pim-api](../pim-api) that connects to the CommerceLink PIM microservice.

## Overview

This library provides a `PimCatalog` implementation that:
- Fetches and caches PIM entries from the CommerceLink PIM service via REST (`/PIM/Index`)
- Submits new product fetch requests to the PIM service via SQS (`pim-fetch-queue`)
- Declares SQS event bindings for `pim-entry-added-queue` and `pim-entry-deleted-queue`

It is a **plain Java library** with no Spring dependency. Discovery is done via Java `ServiceLoader` using the `PimCatalogDescriptor` SPI (extends `ProviderDescriptor<PimCatalog>` from `provider-api`).

## Usage

Add the dependency:

```xml
<dependency>
    <groupId>pl.commercelink</groupId>
    <artifactId>pim-commercelink</artifactId>
    <version>0.1.0</version>
</dependency>
```

The implementation is automatically discovered via `ServiceLoader`:

```java
PimCatalogDescriptor descriptor = ServiceLoader.load(PimCatalogDescriptor.class)
        .findFirst()
        .orElseThrow();

PimCatalog catalog = descriptor.create(
        Map.of("apiDomain", "https://api.example.com", "apiKey", "secret", "env", "prod"),
        Map.of("sqsAsyncClient", sqsAsyncClient)
);

catalog.refresh();
catalog.findByGtin("5901234123457");
```

Event bindings are declared by the descriptor and can be set up by the hosting application:

```java
for (EventBinding<?> binding : descriptor.bindings()) {
    // QueueBinding  -> set up SQS listener
    // WebhookBinding -> set up HTTP route
}
```

## Configuration

| Key         | Description                          |
|-------------|--------------------------------------|
| `apiDomain` | Base URL of the PIM service          |
| `apiKey`    | API Gateway key (optional)           |
| `env`       | Environment: `prod` or `localhost`   |

## Context (optional)

| Key              | Type             | Description                        |
|------------------|------------------|------------------------------------|
| `sqsAsyncClient` | `SqsAsyncClient` | AWS SQS client for submit requests |

Without `sqsAsyncClient`, the catalog works in read-only mode (queries and refresh only).

## License

MIT
