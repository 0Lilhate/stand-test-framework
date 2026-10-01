package ru.alfa.stand.test.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory {@link HttpCaller} that records every request and returns canned responses (or throws a
 * configured failure), so the executor can be unit-tested without a live server. Multiple responses
 * form a sequence for polling tests; the last one repeats once the queue is drained.
 */
public final class FakeHttpCaller implements HttpCaller {

    private final Deque<RestResponse> responses = new ArrayDeque<>();
    private final List<RestRequest> requests = new ArrayList<>();
    private RestResponse lastResponse;
    private RuntimeException failure;

    public FakeHttpCaller respondWith(RestResponse... responses) {
        for (RestResponse response : responses) {
            this.responses.addLast(response);
        }
        return this;
    }

    public FakeHttpCaller failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public RestRequest lastRequest() {
        return this.requests.isEmpty() ? null : this.requests.get(this.requests.size() - 1);
    }

    public List<RestRequest> requests() {
        return List.copyOf(this.requests);
    }

    @Override
    public RestResponse execute(RestRequest request) {
        this.requests.add(request);
        if (this.failure != null) {
            throw this.failure;
        }
        if (!this.responses.isEmpty()) {
            this.lastResponse = this.responses.pollFirst();
        }
        return this.lastResponse;
    }
}
