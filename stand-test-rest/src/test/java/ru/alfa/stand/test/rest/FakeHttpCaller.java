package ru.alfa.stand.test.rest;

/**
 * In-memory {@link HttpCaller} that records the last request and returns a canned response (or throws
 * a configured failure), so the executor can be unit-tested without a live server.
 */
final class FakeHttpCaller implements HttpCaller {

    private RestResponse response;
    private RuntimeException failure;
    private RestRequest lastRequest;

    FakeHttpCaller respondWith(RestResponse response) {
        this.response = response;
        return this;
    }

    FakeHttpCaller failWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    RestRequest lastRequest() {
        return this.lastRequest;
    }

    @Override
    public RestResponse execute(RestRequest request) {
        this.lastRequest = request;
        if (this.failure != null) {
            throw this.failure;
        }
        return this.response;
    }
}
