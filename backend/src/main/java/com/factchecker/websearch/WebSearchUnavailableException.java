package com.factchecker.websearch;

public class WebSearchUnavailableException extends RuntimeException {
    public WebSearchUnavailableException(String message) {
        super(message);
    }

    public WebSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
