package com.devpilot.architecture.ask;
import com.devpilot.architecture.ArchitectureModels.Component;
import com.devpilot.embedding.SemanticSearchStore.Result;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
/** One bounded bulk source query, restricted to the owner's published repository. No vector duplication. */
@org.springframework.stereotype.Component
public class ArchitectureQuestionSources {
    private final JdbcTemplate jdbc;
    public ArchitectureQuestionSources(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Result> load(long user,long repo,List<Component> components,int maxChars){
        if(components.isEmpty())return List.of();
        var clauses=new ArrayList<String>();var args=new ArrayList<Object>();args.add(repo);args.add(user);args.add(maxChars);
        for(var c:components){clauses.add("(f.path=? AND c.start_line<=? AND c.end_line>=?)");args.add(c.path());args.add(c.endLine());args.add(c.startLine());}
        var order=new ArrayList<String>();int rank=0;
        for(var path:components.stream().map(Component::path).distinct().toList()){order.add("WHEN f.path=? THEN "+rank++);args.add(path);}
        return jdbc.query("""
            SELECT c.id,f.path,f.language,c.symbol_name,c.symbol_type,c.start_line,c.end_line,c.content
            FROM code_chunks c JOIN repository_files f ON f.id=c.repository_file_id AND f.repository_id=c.repository_id
            JOIN repositories r ON r.id=c.repository_id WHERE r.id=? AND r.user_id=? AND length(c.content)<=? AND (
            """+String.join(" OR ",clauses)+") ORDER BY CASE "+String.join(" ",order)+" ELSE 999 END,c.start_line,c.id LIMIT 120",
            (rs,n)->new Result(rs.getLong("id"),rs.getString("path"),rs.getString("language"),rs.getString("symbol_name"),rs.getString("symbol_type"),rs.getInt("start_line"),rs.getInt("end_line"),rs.getString("content"),0),args.toArray());
    }
}
