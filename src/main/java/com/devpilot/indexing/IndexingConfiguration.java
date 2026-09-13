package com.devpilot.indexing;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(IndexingProperties.class)
public class IndexingConfiguration {
    @Bean("indexingExecutor")
    public ThreadPoolTaskExecutor indexingExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2); executor.setMaxPoolSize(2); executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("devpilot-index-");
        executor.setWaitForTasksToCompleteOnShutdown(true); executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
