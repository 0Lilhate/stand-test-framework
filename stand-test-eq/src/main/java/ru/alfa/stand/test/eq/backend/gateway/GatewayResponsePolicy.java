package ru.alfa.stand.test.eq.backend.gateway;

import java.util.regex.Pattern;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.http.RestResponse;


public final class GatewayResponsePolicy implements GatewayContracts {

    private static final Pattern PIN = Pattern.compile("[A-Z0-9]{6}");
    private static final Pattern ACCOUNT = Pattern.compile("[0-9]{20}");
    private static final String UNVERIFIABLE = "UNVERIFIABLE_RESPONSE";
    private static final String EMPTY_OBJECT = "{}";

    @Override
    public String confirm(String operation, RestResponse response) {
        if (response.statusCode() != 200) {
            throw new EqSeedException("HTTP_UNEXPECTED_STATUS", operation,
                    "EQ gateway " + operation + " returned HTTP " + response.statusCode());
        }
        String value = normalise(response.body());
        if (value.isEmpty()) {
            throw new EqSeedException(UNVERIFIABLE, operation,
                    "EQ gateway " + operation + " returned an empty body");
        }
        if (issuesIdentifier(operation)) {
            if (!PIN.matcher(value).matches()) {
                throw new EqSeedException(UNVERIFIABLE, operation,
                        "EQ gateway " + operation + " returned a value that is not a confirmed PIN shape");
            }
            return value;
        }
        if (issuesAccount(operation)) {
            if (!ACCOUNT.matcher(value).matches()) {
                throw new EqSeedException(UNVERIFIABLE, operation,
                        "EQ gateway " + operation + " returned a value that is not a confirmed account shape");
            }
            return value;
        }
        if (isContractConfirmed(operation)) {
            if (!EMPTY_OBJECT.equals(value)) {
                throw new EqSeedException(UNVERIFIABLE, operation,
                        "EQ gateway " + operation + " returned an unexpected body for a confirmed empty-object contract");
            }
            return operation;
        }
        throw new EqSeedException(UNVERIFIABLE, operation,
                "EQ gateway " + operation + " response contract is not confirmed; the seed cannot be treated as successful");
    }

    /** Reports whether an operation's successful response is a confirmed PIN. */
    public static boolean issuesIdentifier(String operation) {
        return "ONU".equals(operation) || "ONF".equals(operation);
    }

    /** Reports whether an operation's successful response is a confirmed account. */
    public static boolean issuesAccount(String operation) {
        return "OKC".equals(operation);
    }

    /** Reports whether an operation's successful response is a confirmed empty JSON object. */
    public static boolean issuesEmptyObject(String operation) {
        return "YFT2".equals(operation) || "KP1".equals(operation)
                || "VAD".equals(operation) || "SPU".equals(operation);
    }

    /** Reports whether an operation's response contract is confirmed at all. */
    public static boolean isContractConfirmed(String operation) {
        return issuesIdentifier(operation) || issuesAccount(operation) || issuesEmptyObject(operation);
    }

    /** Normalises a possibly JSON-quoted or whitespace-padded scalar body into a trimmed string. */
    public static String normalise(String body) {
        if (body == null) {
            return "";
        }
        String text = body.trim();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }
}