package pl.aiwcorpo.mcp.adapter.foreign;

/**
 * A failed exchange with the foreign server. Carries no text of the server: what it said is untrusted and can hold the
 * credential or the address of the system, so only the kind, a technical reason from a closed list and, for HTTP, the status are kept.
 */
public final class ForeignCallException extends RuntimeException {

    /**
     * What the client may be told: {@code DENIED} is the constant {@code not_found_or_denied}, {@code UNAUTHORIZED} is
     * {@code credential_required} (the foreign server did not accept the header with the user's credential: the user reconnects the account),
     * {@code UNAVAILABLE} is {@code upstream_unavailable}, {@code OVERSIZE} is {@code internal_error}.
     */
    public enum Kind {
        DENIED,
        UNAUTHORIZED,
        UNAVAILABLE,
        OVERSIZE
    }

    /**
     * The technical reason for the log and the metric, which the client never sees. A closed list, so it can be a metric label.
     */
    public enum Reason {
        RESULT_ERROR("result_error"),
        JSONRPC_ERROR("jsonrpc_error"),
        HTTP_401("http_401"),
        HTTP_403("http_403"),
        HTTP_404("http_404"),
        HTTP_REDIRECT("http_redirect"),
        HTTP_OTHER_4XX("http_4xx"),
        HTTP_5XX("http_5xx"),
        UNREACHABLE("unreachable"),
        TIMEOUT("timeout"),
        OVERSIZE("oversize"),
        MALFORMED("malformed"),
        CREDENTIAL_REJECTED("credential_rejected");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Kind kind;
    private final Reason reason;
    private final int httpStatus;

    public ForeignCallException(Kind kind, Reason reason, int httpStatus) {
        super(reason.label(), null, false, false);
        this.kind = kind;
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public ForeignCallException(Kind kind, Reason reason) {
        this(kind, reason, 0);
    }

    public Kind kind() {
        return kind;
    }

    public Reason reason() {
        return reason;
    }

    /** The HTTP status of the answer, 0 when there was none. */
    public int httpStatus() {
        return httpStatus;
    }
}
