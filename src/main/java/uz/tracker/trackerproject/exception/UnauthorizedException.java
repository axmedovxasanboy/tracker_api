package uz.tracker.trackerproject.exception;

/** Thrown on bad credentials or an invalid/expired refresh token → mapped to HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
