package ru.alfa.stand.test.eq.backend.gateway;

import java.util.regex.Pattern;
import ru.alfa.stand.test.eq.EqSeedException;
import ru.alfa.stand.test.http.RestResponse;

/**
 * The default gateway response policy, confirmed against a live controlled chain.
 *
 * <p>BR-22 requires every response to be checked, and "an unverifiable response counts as an error, not a
 * success". The contracts below were captured on 2026-09-30 from one controlled organisation chain
 * ({@code ONU → OKC → YFT2 → KP1}) against the canonical {@code K68} gateway, closing OQ-5:
 *
 * <ul>
 *   <li>{@code ONU}/{@code ONF} issue a bare PIN — {@code ^[A-Z0-9]{6}$} (observed {@code UDHSUS});</li>
 *   <li>{@code OKC} issues a bare 20-digit account — starting {@code 40702} for an organisation and
 *       {@code 40817} for an individual (both observed);</li>
 *   <li>{@code YFT2}, {@code KP1}, {@code VAD} and {@code SPU} answer an empty JSON object {@code {}} on
 *       success (all four observed on 2026-09-30, closing OQ-5 for the individual chain too).</li>
 * </ul>
 *
 * <p>AS-3 allows the value to arrive as a JSON-quoted scalar, so the body is normalised before matching
 * (surrounding whitespace and quotes stripped). Any other body, and any non-200 status, still fails closed.
 */
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