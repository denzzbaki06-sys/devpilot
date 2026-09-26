package com.devpilot.review;
import com.devpilot.exception.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import static com.devpilot.review.ReviewModels.*;
@Component
public class ReviewOutputValidator {
    public static ApiException invalid(){return new ApiException(HttpStatus.BAD_GATEWAY,"Chat provider returned an invalid review response");}
    public static Map<String,Object> schema(){
        var strings=Map.of("type","string");
        var props=new LinkedHashMap<String,Object>();
        props.put("category",Map.of("type","string","enum",Arrays.stream(Category.values()).map(Enum::name).toList()));
        props.put("severity",Map.of("type","string","enum",Arrays.stream(Severity.values()).map(Enum::name).toList()));
        for(String name:List.of("title","description","recommendation","locationRef"))props.put(name,strings);
        props.put("evidenceRefs",Map.of("type","array","items",strings));
        return Map.of("type","object","additionalProperties",false,"required",List.of("findings"),"properties",
                Map.of("findings",Map.of("type","array","items",Map.of("type","object","additionalProperties",false,"properties",props,"required",List.copyOf(props.keySet())))));
    }
    public List<Finding> validate(JsonNode output,Map<String,Location> diff,Map<String,Evidence> sources,Set<Category> focus){
        if(output==null || !output.isObject() || output.size()!=1 || !output.path("findings").isArray() || output.path("findings").size()>20)throw invalid();
        var results=new ArrayList<Finding>();
        for(var f:output.path("findings")){
            if(!f.isObject() || f.size()!=7)throw invalid();
            Category category;Severity severity;
            try{category=Category.valueOf(text(f,"category",30));severity=Severity.valueOf(text(f,"severity",20));}catch(IllegalArgumentException ex){throw invalid();}
            if(!focus.contains(category))throw invalid();
            String title=text(f,"title",200),description=text(f,"description",3000),recommendation=text(f,"recommendation",2000);
            if(ReviewSafety.sensitive(title+"\n"+description+"\n"+recommendation))throw invalid();
            var location=diff.get(text(f,"locationRef",20));if(location==null || location.startLine()<1)throw invalid();
            if(!f.path("evidenceRefs").isArray() || f.path("evidenceRefs").size()>20)throw invalid();
            var evidence=new LinkedHashSet<Evidence>();
            for(var ref:f.path("evidenceRefs")){
                if(!ref.isTextual() || !sources.containsKey(ref.asText()))throw invalid();evidence.add(sources.get(ref.asText()));
            }
            results.add(new Finding("",category,severity,title,description,recommendation,location,List.copyOf(evidence)));
        }
        return List.copyOf(results);
    }
    private String text(JsonNode n,String key,int max){if(!n.path(key).isTextual() || n.path(key).asText().isBlank() || n.path(key).asText().length()>max)throw invalid();return n.path(key).asText().strip();}
    public List<Finding> deduplicate(List<Finding> findings){
        var map=new LinkedHashMap<String,Finding>();
        for(var f:findings){
            String key=f.location().path()+":"+f.location().side()+":"+f.location().startLine()+":"+f.category()+":"+f.title().toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+","");
            map.merge(key,f,(a,b)->{
                var chosen=a.severity().ordinal()<=b.severity().ordinal()?a:b;var evidence=new LinkedHashSet<>(a.evidence());evidence.addAll(b.evidence());
                return new Finding("",chosen.category(),chosen.severity(),chosen.title(),chosen.description(),chosen.recommendation(),chosen.location(),List.copyOf(evidence));
            });
        }
        var result=new ArrayList<Finding>();
        for(var f:map.values())result.add(new Finding("F"+(result.size()+1),f.category(),f.severity(),f.title(),f.description(),f.recommendation(),f.location(),f.evidence()));
        return List.copyOf(result);
    }
}
