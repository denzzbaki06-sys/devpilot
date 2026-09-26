package com.devpilot.architecture;

import com.devpilot.exception.ApiException;
import com.devpilot.indexing.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import static com.devpilot.architecture.ArchitectureModels.*;

@Component
public class ArchitectureStore {
    public record Snapshot(long generation, String sha) {}
    public record Loaded(Snapshot snapshot, List<Source> sources, List<String> warnings, int skipped) {}
    private final JdbcTemplate jdbc;
    private final ArchitectureProperties limits;
    private final SourceFilePolicy policy;
    public ArchitectureStore(JdbcTemplate jdbc, ArchitectureProperties limits, SourceFilePolicy policy) { this.jdbc=jdbc;this.limits=limits;this.policy=policy; }
    public Snapshot snapshot(long user, long repo) {
        var rows=jdbc.queryForList("""
            SELECT r.status,j.id,j.source_commit FROM repositories r LEFT JOIN LATERAL
            (SELECT id,source_commit FROM indexing_jobs WHERE repository_id=r.id AND status='COMPLETED' ORDER BY id DESC LIMIT 1) j ON true
            WHERE r.id=? AND r.user_id=?
            """,repo,user);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Connected repository not found");
        var row=rows.getFirst();
        if(!"READY".equals(row.get("status")))throw new ApiException(HttpStatus.CONFLICT,"Repository must be indexed before architecture analysis.");
        return new Snapshot(row.get("id")==null?0:((Number)row.get("id")).longValue(),(String)row.get("source_commit"));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Loaded load(long user,long repo) {
        var snapshot=snapshot(user,repo);
        var warnings=new ArrayList<String>();
        var files=jdbc.queryForList("SELECT id,path,language,line_count,size_bytes,content_hash,count(*) OVER() AS total_files FROM repository_files WHERE repository_id=? ORDER BY path LIMIT ?",repo,limits.maxFiles()+1);
        int totalFiles=files.isEmpty()?0:((Number)files.getFirst().get("total_files")).intValue();
        if(files.size()>limits.maxFiles()){files.removeLast();warnings.add("Architecture input was limited to "+limits.maxFiles()+" files.");}
        var selected=new LinkedHashMap<Long,Map<String,Object>>();long size=0;int skipped=totalFiles-files.size();
        for(var f:files){
            String path=(String)f.get("path"),lang=(String)f.get("language");long bytes=((Number)f.get("size_bytes")).longValue();
            if(!Set.of("JAVA","JAVASCRIPT","JAVASCRIPT_REACT","TYPESCRIPT","TYPESCRIPT_REACT","PYTHON").contains(lang)
                    || policy.skipReason(path,"100644",bytes)!=null){skipped++;continue;}
            if(size+bytes>limits.maxSourceChars()){skipped++;warnings.add("Architecture source budget reached; remaining oversized inputs omitted.");continue;}
            size+=bytes;selected.put(((Number)f.get("id")).longValue(),f);
        }
        if(selected.isEmpty())return new Loaded(snapshot,List.of(),warnings,skipped);
        // One bounded bulk query, independent of file count. No vectors or staged data are fetched.
        String placeholders=String.join(",",Collections.nCopies(selected.size(),"?"));
        var footprint=jdbc.queryForMap("SELECT count(*) AS chunks,coalesce(sum(length(content)),0) AS chars FROM code_chunks WHERE repository_file_id IN ("+placeholders+")",selected.keySet().toArray());
        if(((Number)footprint.get("chunks")).longValue()>20000 || ((Number)footprint.get("chars")).longValue()>2L*limits.maxSourceChars())
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Architecture chunk budget exceeded");
        var chunks=jdbc.queryForList("SELECT repository_file_id,start_line,end_line,content FROM code_chunks WHERE repository_file_id IN ("+placeholders+") ORDER BY repository_file_id,chunk_index LIMIT 20001",selected.keySet().toArray());
        if(chunks.size()>20000)throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"Architecture chunk budget exceeded");
        var grouped=new HashMap<Long,List<Map<String,Object>>>();
        chunks.forEach(c->grouped.computeIfAbsent(((Number)c.get("repository_file_id")).longValue(),k->new ArrayList<>()).add(c));
        var sources=new ArrayList<Source>();
        for(var entry:selected.entrySet()){
            var f=entry.getValue();String source=reconstruct(((Number)f.get("line_count")).intValue(),(String)f.get("content_hash"),grouped.getOrDefault(entry.getKey(),List.of()));
            if(source==null){skipped++;warnings.add("Indexed source could not be reconstructed exactly: "+f.get("path"));continue;}
            sources.add(new Source((String)f.get("path"),(String)f.get("language"),source,((Number)f.get("line_count")).intValue()));
        }
        return new Loaded(snapshot,List.copyOf(sources),warnings,skipped);
    }
    public static String reconstruct(int count,String hash,List<Map<String,Object>> chunks){
        if(count<1 || count>500000)return null;
        String[] lines=new String[count];
        for(var chunk:chunks){
            int start=((Number)chunk.get("start_line")).intValue(),end=((Number)chunk.get("end_line")).intValue();
            String[] content=((String)chunk.get("content")).split("\n",-1);
            if(start<1 || end>count || end-start+1!=content.length)return null;
            for(int i=0;i<content.length;i++){int n=start+i-1;if(lines[n]!=null&&!lines[n].equals(content[i]))return null;lines[n]=content[i];}
        }
        for(int i=0;i<count;i++)if(lines[i]==null)lines[i]=""; // Chunker omits blank partitions; hash proves exact restoration.
        String source=String.join("\n",lines);
        if(ContentHashes.sha256(source).equals(hash))return source;
        return ContentHashes.sha256(source+"\n").equals(hash)?source+"\n":null;
    }
}
