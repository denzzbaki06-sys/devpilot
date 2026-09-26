package com.devpilot.architecture;
import com.devpilot.exception.ApiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
@Service
@EnableConfigurationProperties(ArchitectureProperties.class)
public class ArchitectureService {
    private final ArchitectureStore store;private final ArchitectureEngine engine;
    public ArchitectureService(ArchitectureStore store,ArchitectureEngine engine){this.store=store;this.engine=engine;}
    public ArchitectureModels.Response analyze(long user,long repo){
        var loaded=store.load(user,repo);
        var result=engine.analyze(repo,loaded.snapshot().sha(),loaded.sources(),loaded.warnings(),loaded.skipped());
        if(!loaded.snapshot().equals(store.snapshot(user,repo)))throw new ApiException(HttpStatus.CONFLICT,"Repository index changed; retry architecture analysis");
        return result;
    }
}
