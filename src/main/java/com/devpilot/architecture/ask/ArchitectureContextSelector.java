package com.devpilot.architecture.ask;
import com.devpilot.architecture.ArchitectureModels;
import com.devpilot.architecture.ArchitectureModels.Component;
import com.devpilot.architecture.ArchitectureModels.Relationship;
import com.devpilot.embedding.SemanticSearchStore.Result;
import com.devpilot.exception.ApiException;
import com.devpilot.review.ReviewSafety;
import java.util.*;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

@org.springframework.stereotype.Component
public class ArchitectureContextSelector {
    public record Selection(List<Component> components,List<Relationship> relationships) {}
    private final ArchitectureAskProperties limits;
    private final ObjectMapper json;
    public ArchitectureContextSelector(ArchitectureAskProperties limits,ObjectMapper json){this.limits=limits;this.json=json;}
    public Component resolve(ArchitectureModels.Response graph,String id){
        if(id==null)return null;
        return graph.components().stream().filter(c->c.id().equals(id)).findFirst()
                .orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"Selected component is not in this architecture snapshot"));
    }
    private boolean safe(Component c){return !ReviewSafety.sensitive(json.writeValueAsString(c));}
    public Selection select(ArchitectureModels.Response graph,String question,Component selected,List<Result> semantic){
        var byId=new LinkedHashMap<String,Component>();
        graph.components().stream().filter(this::safe).sorted(Comparator.comparing(Component::id)).forEach(c->byId.put(c.id(),c));
        var seeds=new LinkedHashSet<String>();
        if(selected!=null && byId.containsKey(selected.id()))seeds.add(selected.id());
        if(selected==null){
            var terms=Arrays.stream(question.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}_/.$-]+"))
                    .filter(s->s.length()>=3).filter(s->!Set.of("the","this","how","does","what","explain","with","from","architecture","repository","component").contains(s)).toList();
            byId.values().stream().filter(c->score(c,terms)>0).sorted(Comparator.<Component>comparingInt(c->score(c,terms)).reversed().thenComparing(Component::id))
                    .limit(Math.max(1,limits.maxComponents()/2)).forEach(c->seeds.add(c.id()));
            for(var source:semantic)byId.values().stream().filter(c->c.path().equals(source.path())&&c.startLine()<=source.endLine()&&c.endLine()>=source.startLine()).limit(2).forEach(c->seeds.add(c.id()));
            graph.entryPoints().stream().filter(c->byId.containsKey(c.id())).limit(3).forEach(c->seeds.add(c.id()));
        }
        var chosen=new LinkedHashSet<String>();var queue=new ArrayDeque<Map.Entry<String,Integer>>();
        seeds.stream().limit(limits.maxComponents()).forEach(id->{chosen.add(id);queue.add(Map.entry(id,0));});
        while(!queue.isEmpty() && chosen.size()<limits.maxComponents()){
            var next=queue.remove();if(next.getValue()>=limits.depth())continue;
            for(var edge:graph.relationships()){
                String neighbor=edge.sourceComponentId().equals(next.getKey())?edge.targetComponentId():edge.targetComponentId().equals(next.getKey())?edge.sourceComponentId():null;
                if(neighbor!=null && byId.containsKey(neighbor)&&chosen.size()<limits.maxComponents()&&chosen.add(neighbor))queue.add(Map.entry(neighbor,next.getValue()+1));
            }
        }
        var edges=graph.relationships().stream().filter(e->chosen.contains(e.sourceComponentId())&&chosen.contains(e.targetComponentId()))
                .filter(e->!ReviewSafety.sensitive(json.writeValueAsString(e))).limit(limits.maxRelationships()).toList();
        return new Selection(chosen.stream().map(byId::get).toList(),edges);
    }
    private int score(Component c,List<String> terms){
        String text=(c.name()+" "+c.qualifiedName()+" "+c.symbol()+" "+c.path()+" "+c.type()+" "+json.writeValueAsString(c.metadata().getOrDefault("routes",List.of()))).toLowerCase(Locale.ROOT);
        return (int)terms.stream().filter(text::contains).count();
    }
    public String retrievalQuery(String question,Selection selection){
        var text=new StringBuilder(question);
        for(var c:selection.components()){
            String extra="\n"+c.name()+" "+c.symbol()+" "+c.path();
            if(text.length()+extra.length()>4000)break;text.append(extra);
        }
        return text.toString();
    }
}
