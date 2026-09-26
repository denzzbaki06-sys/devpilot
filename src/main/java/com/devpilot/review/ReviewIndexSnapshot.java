package com.devpilot.review;
import com.devpilot.exception.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import java.util.Objects;
@Component
public class ReviewIndexSnapshot {
    public record Snapshot(long generation,String commitSha) {}
    private final JdbcTemplate jdbc;
    public ReviewIndexSnapshot(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Snapshot get(long userId,long id){
        var rows=jdbc.queryForList("""
            SELECT r.status, j.id AS generation, j.source_commit
            FROM repositories r LEFT JOIN LATERAL (
                SELECT id,source_commit FROM indexing_jobs WHERE repository_id=r.id AND status='COMPLETED' ORDER BY id DESC LIMIT 1
            ) j ON true WHERE r.id=? AND r.user_id=?
            """,id,userId);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"Connected repository not found");
        var r=rows.getFirst();
        if(!"READY".equals(r.get("status")))throw new ApiException(HttpStatus.CONFLICT,"Repository must be indexed before AI review.");
        return new Snapshot(r.get("generation")==null?0:((Number)r.get("generation")).longValue(),(String)r.get("source_commit"));
    }
    public void unchanged(long userId,long id,Snapshot previous){
        if(!Objects.equals(get(userId,id),previous))throw new ApiException(HttpStatus.CONFLICT,"Repository index changed; retry AI review");
    }
}
