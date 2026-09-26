// Test data only; never imported by application entrypoints.
export const supportedTypes = ["CONTROLLER", "SERVICE", "REPOSITORY", "ENTITY", "CONFIGURATION", "SECURITY", "COMPONENT", "UTILITY", "ENTRY_POINT", "EXTERNAL_CLIENT", "MODULE", "FUNCTION", "UNKNOWN"];
export function graphFixture(count = 4, dense = false) {
  const components = Array.from({ length: count }, (_, i) => ({ id: `backend-${String(i).padStart(4, "0")}`, name: `Unit${i}`, qualifiedName: `fixture.Unit${i}`, type: supportedTypes[i % supportedTypes.length], language: "JAVA", path: `src/feature${i}/Unit${i}.java`, startLine: 10, endLine: 35, symbol: `Symbol${i}`, metadata: i === 0 ? { routes: [{ path: "/api/items", methods: ["GET"], symbol: "items" }] } : i === 3 ? { tableName: "items", entityName: "Item" } : {} }));
  const relationships = [];
  const edge = (source, target, type = "DEPENDS_ON") => ({ sourceComponentId: components[source].id, targetComponentId: components[target].id, type, evidence: { path: components[source].path, startLine: 14, endLine: 15, symbol: "constructor" } });
  if (dense) {
    for (let i = 0; i < count - 25; i++) for (const step of [25, 26, 27]) if (i + step < count) relationships.push(edge(i, i + step));
  } else for (let i = 1; i < count; i++) relationships.push(edge(Math.floor((i - 1) / 3), i, i % 3 ? "DEPENDS_ON" : "MANAGES_ENTITY"));
  return { repositoryId: 12, indexCommitSha: "a".repeat(40), components, relationships, entryPoints: components.filter(c => c.type === "ENTRY_POINT"), externalDependencies: [], stats: { components: count, relationships: relationships.length, entryPoints: components.filter(c => c.type === "ENTRY_POINT").length, languages: ["JAVA"], filesAnalyzed: count, filesSkipped: 0 }, warnings: [] };
}
