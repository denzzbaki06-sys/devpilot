package com.devpilot.architecture;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("devpilot.architecture")
public record ArchitectureProperties(int maxFiles, int maxSourceChars, int maxComponents, int maxRelationships) {
    public ArchitectureProperties {
        if(maxFiles<1 || maxFiles>2000 || maxSourceChars<1000 || maxSourceChars>10000000 || maxComponents<1 || maxComponents>5000 || maxRelationships<1 || maxRelationships>20000)
            throw new IllegalArgumentException("Invalid architecture limits");
    }
}
