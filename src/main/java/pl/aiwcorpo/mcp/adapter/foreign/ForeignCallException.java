package pl.aiwcorpo.mcp.adapter.foreign;

/**
 * A failed exchange with the foreign server. Carries no text of the server: what it said is untrusted and can hold the
 * credential or the address of the system, so only the kind and, for HTTP, the status are kept.
 */
public final class ForeignCallException extends RuntimeException {

    /** What the client may be told: {@code DENIED} is the constant {@code not_found_or_denied}, {@code UNAVAILABLE} is {@code upstream_unavailable}. */
    public enum Kind {
        DENIED,
        UNAVAILABLE,
        OVERSIZE
    }

    private final Kind kind;
    private final int httpStatus;

    public ForeignCallException(Kind kind, int httpStatus, String reason) {
        super(reason, null, false, false);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public ForeignCallException(Kind kind, String reason) {
        this(kind, 0, reason);
    }

    public Kind kind() {
        return kind;
    }

    /** The HTTP status of the answer, 0 when there was none. */
    public int httpStatus() {
        return httpStatus;
    }
}
