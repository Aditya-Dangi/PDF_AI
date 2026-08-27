package com.factchecker.repository;

import com.factchecker.domain.WebCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebCacheRepository extends JpaRepository<WebCache, String> {
    Optional<WebCache> findByClaimHash(String claimHash);
}
