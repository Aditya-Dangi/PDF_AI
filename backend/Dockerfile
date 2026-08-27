# --- Build stage ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

COPY src/ src/
RUN chmod +x mvnw && ./mvnw -B clean package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre
WORKDIR /app

# LibreOffice Writer (not the full suite) headlessly converts uploaded DOC/DOCX files to PDF
# before the rest of the pipeline ever sees them - see DocumentConversionService.
# tesseract-ocr (+ English trained data) backs the OCR fallback for scanned/image-only PDF pages -
# see OcrService. Without it, OcrService now degrades gracefully instead of crashing (see the
# Throwable catch there), but pages needing OCR would silently come back with no extracted text.
RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice-writer tesseract-ocr tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

RUN mkdir -p /app/data /app/uploads
VOLUME ["/app/data", "/app/uploads"]

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
