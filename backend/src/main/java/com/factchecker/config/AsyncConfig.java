package com.factchecker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "documentProcessingExecutor")
    public Executor documentProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-proc-");
        executor.initialize();
        return executor;
    }

    /**
     * Dedicated pool for the sub-tasks *within* a single audit run (per-chunk claim decomposition,
     * per-claim verification) - kept separate from documentProcessingExecutor so an audit task
     * (which itself occupies one of that pool's threads) can never starve waiting on its own
     * sub-tasks. Sized small since each task also drives local Ollama/SearXNG calls.
     */
    @Bean(name = "claimPipelineExecutor")
    public Executor claimPipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("claim-pipe-");
        executor.initialize();
        return executor;
    }
}
