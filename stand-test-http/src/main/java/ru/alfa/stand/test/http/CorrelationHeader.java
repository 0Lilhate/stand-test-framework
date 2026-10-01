package ru.alfa.stand.test.http;

import java.util.Map;
import ru.alfa.stand.test.core.environment.CorrelationConfig;
import ru.alfa.stand.test.core.environment.CorrelationSource;
import ru.alfa.stand.test.core.environment.ServiceEndpointDefinition;
import ru.alfa.stand.test.core.exception.StandTestException;

/** Adds an SDK-owned correlation ID to an outbound service request. */
public final class CorrelationHeader {

    private CorrelationHeader() {
    }

    /**
     * Applies the already-decided injection flag. The adapter owns its default and any opt-out.
     */
    public static void inject(boolean enabled, ServiceEndpointDefinition endpoint, Map<String, String> headers, String correlationId) {
        if (!enabled) {
            return;
        }
        CorrelationConfig correlation = endpoint.correlation();
        if (correlation == null || correlation.source() != CorrelationSource.HEADER) {
            throw new StandTestException("Correlation id injection was requested for service '" + endpoint.name()
                    + "', but it has no HEADER correlation config");
        }
        headers.put(correlation.name(), correlationId);
    }
}
