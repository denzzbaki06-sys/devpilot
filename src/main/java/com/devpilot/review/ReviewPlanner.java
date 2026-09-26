package com.devpilot.review;

import com.devpilot.indexing.SourceFilePolicy;
import com.devpilot.pullrequest.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import static com.devpilot.review.ReviewModels.*;

@Component
public class ReviewPlanner {
    public record DiffInput(String ref,String type,String content,Integer oldLine,Integer newLine) {
        @Override public String toString(){return "ReviewDiffInput[REDACTED]";}
    }
    public record Unit(String path,String previousPath,String status,int hunk,List<DiffInput> lines,Map<String,Location> locations,String query,int chars) {
        @Override public String toString(){return "ReviewUnit[REDACTED]";}
    }
    public record Plan(List<Unit> units,List<SkippedFile> skipped,List<String> warnings) {}
    private final SourceFilePolicy policy; private final ReviewProperties limits; private final ObjectMapper json;
    public ReviewPlanner(SourceFilePolicy policy,ReviewProperties limits,ObjectMapper json){this.policy=policy;this.limits=limits;this.json=json;}
    public Plan plan(PullRequestContext context){
        var units=new ArrayList<Unit>();var skipped=new ArrayList<SkippedFile>();var warnings=new ArrayList<String>();
        int refs=0,chars=0,files=0,partial=0;
        for(var file:context.files()){
            String reason=policy.skipReason(file.filename(),"100644",file.patch()==null?0:file.patch().getBytes(StandardCharsets.UTF_8).length);
            if(reason==null && file.previousFilename()!=null) reason=policy.skipReason(file.previousFilename(),"100644",0);
            if(reason==null && !file.patchAvailable())reason="DIFF_UNAVAILABLE";
            if(reason==null && file.parseStatus()!=UnifiedDiffParser.Status.PARSED)reason="INVALID_DIFF";
            if(reason==null && ReviewSafety.sensitive(file.patch()))reason="SENSITIVE_CONTENT";
            if(reason==null)reason=policy.normalize(file.patch().getBytes(StandardCharsets.UTF_8)).skipReason();
            if(reason==null && files>=limits.maxFiles())reason="FILE_BUDGET";
            if(reason!=null){skipped.add(new SkippedFile(file.filename(),reason));continue;}
            int selected=0,reviewable=0;
            for(int h=0;h<file.hunks().size();h++){
                var hunk=file.hunks().get(h);var lines=new ArrayList<DiffInput>();var locations=new LinkedHashMap<String,Location>();int size=0;
                for(var line:hunk.lines()){
                    String ref=null;
                    if(line.type()!=UnifiedDiffParser.LineType.CONTEXT){
                        boolean right=line.type()==UnifiedDiffParser.LineType.ADDITION;Integer number=right?line.newLineNumber():line.oldLineNumber();
                        if(number==null || number<1)continue;
                        ref="D"+(++refs);locations.put(ref,new Location(file.filename(),file.previousFilename(),right?Side.RIGHT:Side.LEFT,number,number,h+1));
                    }
                    lines.add(new DiffInput(ref,line.type().name(),line.content(),line.oldLineNumber(),line.newLineNumber()));size+=line.content().length()+1;
                }
                if(locations.isEmpty())continue;reviewable++;
                String query=file.filename()+"\n"+ReviewSafety.metadata(context.pullRequest().title(),500)+"\n"+String.join("\n",lines.stream().map(DiffInput::content).toList());
                if(query.length()>4000)query=query.substring(0,4000); // Query sampling only; evidence/diff is never clipped.
                var unit=new Unit(file.filename(),file.previousFilename(),file.status(),h+1,List.copyOf(lines),Map.copyOf(locations),query,size);
                if(units.size()>=limits.maxBatches() || chars+size>limits.maxDiffChars()
                        || json.writeValueAsString(diff(unit)).length()>limits.maxBatchChars()-2500)continue;
                units.add(unit);chars+=size;selected++;
            }
            if(selected==0)skipped.add(new SkippedFile(file.filename(),reviewable==0?"NO_CHANGED_LINES":"CONTEXT_BUDGET"));
            else{files++;if(selected<reviewable)partial++;}
        }
        if(partial>0)warnings.add(partial+" files were only partially reviewed because of context budgets.");
        return new Plan(List.copyOf(units),List.copyOf(skipped),List.copyOf(warnings));
    }
    public static Map<String,Object> diff(Unit unit){
        return Map.of("path",unit.path(),"previousPath",Objects.toString(unit.previousPath(),""),"status",unit.status(),"hunk",unit.hunk(),"lines",unit.lines());
    }
}
