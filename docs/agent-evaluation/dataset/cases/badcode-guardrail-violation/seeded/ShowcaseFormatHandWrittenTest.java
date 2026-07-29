// EVALUATION FIXTURE — a hand-written test that violates the SDK guardrails on purpose.
// Not compiled by the build: it lives under docs/ and is handed to the agent as input.
//
// Planted violations, one per guardrail the review is expected to find:
//   1. a hardcoded stand URL instead of a logical alias          (HARDCODED_STAND_URL)
//   2. Thread.sleep instead of a bounded await                    (THREAD_SLEEP)
//   3. an inline Authorization header instead of registry auth    (SECRET_IN_SOURCE)
//   4. a raw HTTP client bypassing the SDK pipeline               (pipeline bypass)
package ru.alfa.qa.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class ShowcaseFormatHandWrittenTest {

    @Test
    void showcaseFormatIsReturned() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://showcase-mock.ift.example:8080/showcases/formats/DEMO-001"))
                .header("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.PLACEHOLDER")
                .GET()
                .build();

        Thread.sleep(3000);

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }
}
