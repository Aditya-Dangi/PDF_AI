package com.factchecker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private String uploadDir;
    private Jwt jwt = new Jwt();
    private Cors cors = new Cors();
    private Searxng searxng = new Searxng();
    private Ollama ollama = new Ollama();
    private Ocr ocr = new Ocr();
    private RateLimit rateLimit = new RateLimit();

    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }

    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }

    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }

    public Searxng getSearxng() { return searxng; }
    public void setSearxng(Searxng searxng) { this.searxng = searxng; }

    public Ollama getOllama() { return ollama; }
    public void setOllama(Ollama ollama) { this.ollama = ollama; }

    public Ocr getOcr() { return ocr; }
    public void setOcr(Ocr ocr) { this.ocr = ocr; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    public static class Jwt {
        private String secret;
        private long expirationMinutes;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getExpirationMinutes() { return expirationMinutes; }
        public void setExpirationMinutes(long expirationMinutes) { this.expirationMinutes = expirationMinutes; }
    }

    public static class Cors {
        private String allowedOrigin;

        public String getAllowedOrigin() { return allowedOrigin; }
        public void setAllowedOrigin(String allowedOrigin) { this.allowedOrigin = allowedOrigin; }
    }

    public static class Searxng {
        private String baseUrl;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    public static class Ollama {
        private String baseUrl;
        private String embeddingModel;
        private String chatModel;
        /** Used only as a fallback when OCR finds too little text in a user-selected image region
         *  (e.g. a pure diagram/chart) - see ImageQueryService. Blank/unset disables the fallback
         *  rather than failing the whole request. */
        private String visionModel;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getEmbeddingModel() { return embeddingModel; }
        public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
        public String getChatModel() { return chatModel; }
        public void setChatModel(String chatModel) { this.chatModel = chatModel; }
        public String getVisionModel() { return visionModel; }
        public void setVisionModel(String visionModel) { this.visionModel = visionModel; }
    }

    public static class Ocr {
        private String tessdataPath;

        public String getTessdataPath() { return tessdataPath; }
        public void setTessdataPath(String tessdataPath) { this.tessdataPath = tessdataPath; }
    }

    /** Requests allowed per minute, per user (or IP if unauthenticated) - see RateLimitFilter. */
    public static class RateLimit {
        private int standardCapacity = 30;
        private int authCapacity = 10;

        public int getStandardCapacity() { return standardCapacity; }
        public void setStandardCapacity(int standardCapacity) { this.standardCapacity = standardCapacity; }
        public int getAuthCapacity() { return authCapacity; }
        public void setAuthCapacity(int authCapacity) { this.authCapacity = authCapacity; }
    }
}
