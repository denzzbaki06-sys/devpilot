package com.devpilot.architecture.ask;
import com.devpilot.architecture.ArchitectureModels;
import com.devpilot.rag.OpenAiChatProvider;
import com.devpilot.review.ReviewSafety;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class ArchitectureAnswerValidator {
    public static final String INSUFFICIENT="I don't have enough repository evidence to answer this reliably.";
    public record Source(String id,Long chunkId,String path,int startLine,int endLine,String symbol,String language) {}
    public record ComponentRef(String ref,String id,String name,ArchitectureModels.Type type) {}
    public record RelationshipRef(String ref,String sourceComponentId,String targetComponentId,ArchitectureModels.Relation type,ArchitectureModels.Evidence evidence) {}
    public record Answer(long repositoryId,String answer,List<Source> sources,List<ComponentRef> components,List<RelationshipRef> relationships,
                         String indexCommitSha,List<String> warnings,boolean grounded) {
        @Override public String toString(){return "ArchitectureAnswer[REDACTED]";}
    }
    public static Map<String,Object> schema(){
        var refs=Map.of("type","array","items",Map.of("type","string"));
        var statement=Map.of("type","object","additionalProperties",false,"required",List.of("text","architectureRefs","relationshipRefs","sourceRefs"),
                "properties",Map.of("text",Map.of("type","string"),"architectureRefs",refs,"relationshipRefs",refs,"sourceRefs",refs));
        return Map.of("type","object","additionalProperties",false,"required",List.of("insufficientContext","statements"),
                "properties",Map.of("insufficientContext",Map.of("type","boolean"),"statements",Map.of("type","array","items",statement)));
    }
    public Answer abstain(long repo,String sha,List<String> warnings){return new Answer(repo,INSUFFICIENT,List.of(),List.of(),List.of(),sha,warnings,false);}
    public Answer validate(long repo,String sha,JsonNode output,ArchitectureQuestionContext.Context ctx){
        if(output==null||!output.isObject()||output.size()!=2||!output.path("insufficientContext").isBoolean()||!output.path("statements").isArray()||output.path("statements").size()>20)throw OpenAiChatProvider.invalid();
        if(output.path("insufficientContext").asBoolean()){
            if(!output.path("statements").isEmpty())throw OpenAiChatProvider.invalid();return abstain(repo,sha,ctx.warnings());
        }
        if(output.path("statements").isEmpty())throw OpenAiChatProvider.invalid();
        var a=new LinkedHashSet<String>();var e=new LinkedHashSet<String>();var s=new LinkedHashSet<String>();var answer=new StringBuilder();
        for(var statement:output.path("statements")){
            if(!statement.isObject()||statement.size()!=4||!statement.path("text").isTextual())throw OpenAiChatProvider.invalid();
            String text=statement.path("text").asText().strip();
            // Citation tokens are server-owned. Model-supplied file/line fields fail the exact schema above.
            if(text.isBlank()||text.length()>4000||ReviewSafety.sensitive(text)||Pattern.compile("\\[[AES]\\d+\\]").matcher(text).find())throw OpenAiChatProvider.invalid();
            var ar=refs(statement,"architectureRefs",ctx.components());var er=refs(statement,"relationshipRefs",ctx.relationships());var sr=refs(statement,"sourceRefs",ctx.sources());
            if(sr.isEmpty()||(ar.isEmpty()&&er.isEmpty()))throw OpenAiChatProvider.invalid();
            a.addAll(ar);e.addAll(er);s.addAll(sr);
            if(!answer.isEmpty())answer.append("\n\n");answer.append(text);
            for(var group:List.of(ar,er,sr))for(var ref:group)answer.append(" [").append(ref).append("]");
            if(answer.length()>16000)throw OpenAiChatProvider.invalid();
        }
        return new Answer(repo,answer.toString(),s.stream().map(ref->{var c=ctx.sources().get(ref);return new Source(ref,c.chunkId(),c.path(),c.startLine(),c.endLine(),c.symbolName(),c.language());}).toList(),
                a.stream().map(ref->{var c=ctx.components().get(ref);return new ComponentRef(ref,c.id(),c.name(),c.type());}).toList(),
                e.stream().map(ref->{var r=ctx.relationships().get(ref);return new RelationshipRef(ref,r.sourceComponentId(),r.targetComponentId(),r.type(),r.evidence());}).toList(),sha,ctx.warnings(),true);
    }
    private Set<String> refs(JsonNode node,String key,Map<String,?> allowed){
        var array=node.path(key);if(!array.isArray()||array.size()>20)throw OpenAiChatProvider.invalid();
        var refs=new LinkedHashSet<String>();for(var ref:array){if(!ref.isTextual()||!allowed.containsKey(ref.asText()))throw OpenAiChatProvider.invalid();refs.add(ref.asText());}return refs;
    }
}
