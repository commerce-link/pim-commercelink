package pl.commercelink.pim.commercelink;

import org.junit.jupiter.api.Test;
import pl.commercelink.pim.api.CategoryMatchRequest;
import pl.commercelink.pim.api.CategoryMatchedEvent;
import pl.commercelink.provider.api.EventBinding;
import pl.commercelink.provider.api.EventBinding.QueueBinding;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceLinkPimCatalogCategoryMatchTest {

    static class FakeSqsAsyncClient implements SqsAsyncClient {

        String lastQueueUrl;
        String lastBody;

        @Override
        public CompletableFuture<GetQueueUrlResponse> getQueueUrl(GetQueueUrlRequest request) {
            return CompletableFuture.completedFuture(GetQueueUrlResponse.builder()
                    .queueUrl("http://sqs.local/" + request.queueName())
                    .build());
        }

        @Override
        public CompletableFuture<SendMessageResponse> sendMessage(SendMessageRequest request) {
            lastQueueUrl = request.queueUrl();
            lastBody = request.messageBody();
            return CompletableFuture.completedFuture(SendMessageResponse.builder().build());
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    }

    @Test
    void submitCategoryMatchSendsJsonToCategoryMatchQueue() {
        // given
        FakeSqsAsyncClient sqs = new FakeSqsAsyncClient();
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog(sqs, "http://localhost:9", "", false);
        CategoryMatchRequest request = new CategoryMatchRequest(null, "5901234567890", "MFN-1", "Acme", "Widget Pro", null);

        // when
        catalog.submitCategoryMatch(request);

        // then
        assertThat(sqs.lastQueueUrl).isEqualTo("http://sqs.local/pim-category-match-queue");
        assertThat(sqs.lastBody).contains("\"mfn\":\"MFN-1\"").contains("\"ean\":\"5901234567890\"");
    }

    @Test
    void submitCategoryMatchWithoutSqsClientThrows() {
        // given
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog("http://localhost:9", "", false);

        // when / then
        assertThatThrownBy(() -> catalog.submitCategoryMatch(new CategoryMatchRequest(null, "e", "m", "b", "n", null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void dispatchRoutesCategoryMatchedEventToRegisteredListener() {
        // given
        CommerceLinkPimCatalog catalog = new CommerceLinkPimCatalog("http://localhost:9", "", false);
        List<CategoryMatchedEvent> received = new ArrayList<>();
        catalog.onCategoryMatched(received::add);
        CategoryMatchedEvent event = new CategoryMatchedEvent("e", "m", "CPU", null, null, "mock");

        // when
        catalog.dispatch(event);

        // then
        assertThat(received).containsExactly(event);
    }

    @Test
    void descriptorDeclaresCategoryMatchedQueueBinding() {
        // given
        CommerceLinkPimDescriptor descriptor = new CommerceLinkPimDescriptor();

        // when
        List<EventBinding<?>> bindings = descriptor.bindings();

        // then
        assertThat(bindings)
                .filteredOn(binding -> binding instanceof QueueBinding<?> queueBinding
                        && "pim-category-matched-queue".equals(queueBinding.queueName()))
                .singleElement()
                .satisfies(binding -> assertThat(binding.eventType()).isEqualTo(CategoryMatchedEvent.class));
    }
}
