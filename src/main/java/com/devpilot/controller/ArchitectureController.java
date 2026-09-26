package com.devpilot.controller;
import com.devpilot.architecture.*;
import com.devpilot.architecture.ask.*;
import com.devpilot.service.UserService;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/repositories/{repositoryId}/architecture")
public class ArchitectureController {
    private final ArchitectureService service;private final UserService users;private final ArchitectureQuestionService questions;
    public ArchitectureController(ArchitectureService service,UserService users,ArchitectureQuestionService questions){this.service=service;this.users=users;this.questions=questions;}
    @GetMapping public ResponseEntity<ArchitectureModels.Response> get(Authentication auth,@PathVariable long repositoryId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.analyze(users.currentUser(auth).getId(),repositoryId));
    }
    public record AskRequest(String question,String selectedComponentId,String indexCommitSha) {
        @Override public String toString(){return "ArchitectureAskRequest[REDACTED]";}
    }
    @PostMapping("/ask") public ResponseEntity<ArchitectureAnswerValidator.Answer> ask(Authentication auth,@PathVariable long repositoryId,@RequestBody tools.jackson.databind.JsonNode body){
        if(!body.isObject()||!body.path("question").isTextual())throw bad();
        for(String key:body.propertyNames())if(!java.util.Set.of("question","selectedComponentId","indexCommitSha").contains(key))throw bad();
        String selected=optional(body,"selectedComponentId"),sha=optional(body,"indexCommitSha");
        if(sha!=null&&!sha.matches("[a-fA-F0-9]{40}"))throw bad();
        var request=new AskRequest(body.path("question").asText(),selected,sha);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(questions.ask(users.currentUser(auth).getId(),repositoryId,request.question(),request.selectedComponentId(),request.indexCommitSha()));
    }
    private String optional(tools.jackson.databind.JsonNode body,String key){if(!body.has(key)||body.path(key).isNull())return null;if(!body.path(key).isTextual())throw bad();return body.path(key).asText();}
    private com.devpilot.exception.ApiException bad(){return new com.devpilot.exception.ApiException(HttpStatus.BAD_REQUEST,"Invalid architecture question request");}
}
