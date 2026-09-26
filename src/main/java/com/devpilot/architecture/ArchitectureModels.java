package com.devpilot.architecture;
import java.util.*;
public final class ArchitectureModels {
    private ArchitectureModels() {}
    public enum Type { CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIGURATION, SECURITY, COMPONENT, UTILITY, ENTRY_POINT, EXTERNAL_CLIENT, MODULE, FUNCTION, UNKNOWN }
    public enum Relation { DEPENDS_ON, INJECTS, EXTENDS, IMPLEMENTS, MANAGES_ENTITY }
    public record Evidence(String path, int startLine, int endLine, String symbol) {}
    public record Component(String id, String name, String qualifiedName, Type type, String language,
                            String path, int startLine, int endLine, String symbol, Map<String,Object> metadata) {}
    public record Relationship(String sourceComponentId, String targetComponentId, Relation type, Evidence evidence) {}
    public record ExternalDependency(String name, String language, Evidence evidence) {}
    public record Stats(int components, int relationships, int entryPoints, List<String> languages, int filesAnalyzed, int filesSkipped, int unresolvedReferences) {}
    public record Response(long repositoryId, String indexCommitSha, List<Component> components, List<Relationship> relationships,
                           List<Component> entryPoints, List<ExternalDependency> externalDependencies, Stats stats, List<String> warnings) {}
    public record Source(String path, String language, String content, int lineCount) {
        @Override public String toString() { return "ArchitectureSource[REDACTED]"; }
    }
}
