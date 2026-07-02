package ru.alfa.stand.test.grpc;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import ru.alfa.stand.test.core.exception.StandTestException;

/**
 * Converts between JSON and protobuf {@link DynamicMessage} using {@link JsonFormat}, so declarative
 * scenarios describe requests/responses as JSON without any generated stubs (plan §"Ключевое решение",
 * decision A). Conversion failures are configuration/serialisation errors and surface as
 * {@link StandTestException}.
 */
final class DynamicMessages {

    private DynamicMessages() {
    }

    static DynamicMessage fromJson(Descriptor descriptor, String json) {
        DynamicMessage.Builder builder = DynamicMessage.newBuilder(descriptor);
        if (json == null || json.isBlank()) {
            return builder.build();
        }
        try {
            JsonFormat.parser().merge(json, builder);
        } catch (InvalidProtocolBufferException invalid) {
            throw new StandTestException("Request JSON does not match protobuf type '" + descriptor.getFullName() + "': " + invalid.getMessage(), invalid);
        }
        return builder.build();
    }

    static String toJson(Message message) {
        try {
            return JsonFormat.printer().omittingInsignificantWhitespace().print(message);
        } catch (InvalidProtocolBufferException invalid) {
            throw new StandTestException("Failed to render gRPC response as JSON: " + invalid.getMessage(), invalid);
        }
    }
}
