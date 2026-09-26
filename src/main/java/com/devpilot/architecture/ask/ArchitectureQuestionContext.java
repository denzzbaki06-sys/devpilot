package com.devpilot.architecture.ask;
import com.devpilot.architecture.ArchitectureModels.Component;
import com.devpilot.architecture.ArchitectureModels.Relationship;
import com.devpilot.embedding.SemanticSearchStore.Result;
import com.devpilot.indexing.SourceFilePolicy;
import com.devpilot.rag.RagContextBuilder;
import com.devpilot.review.ReviewSafety;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

@org.springframework.stereotype.Component
public class ArchitectureQuestionContext {
    public record Context(Map<String,Component> components,Map<String,Relationship> relationships,Map<String,Result> sources,
                          Map<String,Object> input,List<String> warnings) {
        @Override public String toString(){return "ArchitectureQuestionContext[REDACTED]";}
    }
    private final ArchitectureAskProperties limits;private final RagContextBuilder rag;private final SourceFilePolicy policy;private final ObjectMapper json;
    public ArchitectureQuestionContext(ArchitectureAskProperties limits,RagContextBuilder rag,SourceFilePolicy policy,ObjectMapper json){this.limits=limits;this.rag=rag;this.policy=policy;this.json=json;}
    public Context build(String question,String sha,ArchitectureContextSelector.Selection selected,List<Result> priority,List<Result> semantic,List<String> warnings){
        var components=new LinkedHashMap<String,Component>();var edges=new LinkedHashMap<String,Relationship>();var sources=new LinkedHashMap<String,Result>();
        int graphBudget=limits.maxContextChars()/3;
        for(var c:selected.components()){
            if(ReviewSafety.sensitive(json.writeValueAsString(c)))continue;
            String id="A"+(components.size()+1);components.put(id,c);
            if(json.writeValueAsString(components).length()>graphBudget)components.remove(id);
        }
        var ids=new HashSet<String>();components.values().forEach(c->ids.add(c.id()));
        for(var e:selected.relationships()){
            if(!ids.contains(e.sourceComponentId())||!ids.contains(e.targetComponentId()))continue;
            String id="E"+(edges.size()+1);edges.put(id,e);
            if(json.writeValueAsString(components).length()+json.writeValueAsString(edges).length()>graphBudget)edges.remove(id);
        }
        var ranked=new ArrayList<Result>();var semanticById=new HashMap<Long,Result>();semantic.forEach(c->semanticById.put(c.chunkId(),c));
        // Reserve half the source slots for ranked semantic results; prioritize selected/direct sources in the other half.
        for(var component:selected.components())priority.stream().filter(c->overlaps(component,c)).sorted(Comparator.<Result>comparingDouble(c->semanticById.getOrDefault(c.chunkId(),c).similarity()).reversed().thenComparing(Result::startLine))
                .limit(3).forEach(c->ranked.add(semanticById.getOrDefault(c.chunkId(),c)));
        var candidates=dedupe(ranked).stream().limit(limits.maxChunks()/2).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        candidates.addAll(semantic.stream().sorted(Comparator.comparingDouble(Result::similarity).reversed().thenComparing(Result::chunkId)).toList());
        candidates= new ArrayList<>(dedupe(candidates).stream().limit(limits.maxChunks()).toList());
        var bounded=rag.buildOrdered(candidates,limits.maxContextChars()-graphBudget);
        for(var c:bounded.sources())sources.put("S"+(sources.size()+1),c);
        var safeWarnings=new ArrayList<String>();warnings.stream().filter(w->!ReviewSafety.sensitive(w)).limit(8).forEach(w->safeWarnings.add(w.length()>400?w.substring(0,400):w));
        safeWarnings.add("Bounded architecture and source context; absence of an edge is not proof that no runtime dependency exists.");
        Map<String,Object> input=input(question,sha,components,edges,sources,safeWarnings);
        while(json.writeValueAsString(input).length()>limits.maxContextChars()&&!sources.isEmpty()){
            sources.remove("S"+sources.size());input=input(question,sha,components,edges,sources,safeWarnings);
        }
        if(json.writeValueAsString(input).length()>limits.maxContextChars()){
            components.clear();edges.clear();sources.clear();safeWarnings.clear();safeWarnings.add("Context budget was insufficient for this question.");
            input=Map.of();
        }
        return new Context(Collections.unmodifiableMap(components),Collections.unmodifiableMap(edges),Collections.unmodifiableMap(sources),input,List.copyOf(safeWarnings));
    }
    private List<Result> dedupe(List<Result> values){
        var ids=new HashSet<Long>();var spans=new HashSet<String>();var out=new ArrayList<Result>();
        for(var c:values){
            if(c.content()==null||c.content().isBlank()||ReviewSafety.sensitive(json.writeValueAsString(c))
                    ||policy.skipReason(c.path(),"100644",c.content().getBytes(StandardCharsets.UTF_8).length)!=null)continue;
            String key=c.path()+":"+c.startLine()+":"+c.endLine();if(ids.contains(c.chunkId())||spans.contains(key))continue;
            ids.add(c.chunkId());spans.add(key);out.add(c);
        }return out;
    }
    private boolean overlaps(Component a,Result b){return a.path().equals(b.path())&&a.startLine()<=b.endLine()&&a.endLine()>=b.startLine();}
    private Map<String,Object> input(String question,String sha,Map<String,Component> components,Map<String,Relationship> edges,Map<String,Result> sources,List<String> warnings){
        var refs=new HashMap<String,String>();components.forEach((ref,c)->refs.put(c.id(),ref));
        var relationships=new LinkedHashMap<String,Object>();edges.forEach((ref,e)->relationships.put(ref,Map.of("source",refs.get(e.sourceComponentId()),"target",refs.get(e.targetComponentId()),"type",e.type(),"evidence",e.evidence()==null?Map.of():e.evidence())));
        var code=new LinkedHashMap<String,Object>();sources.forEach((ref,c)->code.put(ref,Map.of("path",c.path(),"startLine",c.startLine(),"endLine",c.endLine(),"language",c.language(),"symbol",Objects.toString(c.symbolName(),""),"content",c.content())));
        return Map.of("question",question,"indexCommitSha",Objects.toString(sha,"unknown"),"untrustedRepository",Map.of("components",components,"relationships",relationships,"sources",code,"warnings",warnings));
    }
}
