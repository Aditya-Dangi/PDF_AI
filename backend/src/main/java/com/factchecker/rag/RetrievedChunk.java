package com.factchecker.rag;

import com.factchecker.domain.Chunk;

public record RetrievedChunk(Chunk chunk, double similarity) {
}
