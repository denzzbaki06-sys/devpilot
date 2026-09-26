package com.devpilot.controller;
import com.devpilot.review.*;
import com.devpilot.service.UserService;
import com.devpilot.exception.ApiException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
@RestController
@RequestMapping("/api/repositories/{repositoryId}/pull-requests/{number}/review")
public class PullRequestReviewController {
    private final PullRequestReviewService reviews;private final UserService users;
    public PullRequestReviewController(PullRequestReviewService reviews,UserService users){this.reviews=reviews;this.users=users;}
    @PostMapping public ResponseEntity<ReviewModels.Response> review(Authentication auth,@PathVariable long repositoryId,@PathVariable int number,@RequestBody(required=false) JsonNode request){
        var focus=EnumSet.noneOf(ReviewModels.Category.class);String base=null,head=null;
        if(request!=null){
            if(!request.isObject())throw bad();
            for(String name:request.propertyNames())if(!Set.of("focus","expectedBaseSha","expectedHeadSha").contains(name))throw bad();
            if(request.has("focus")){
                var list=request.get("focus");if(!list.isArray() || list.size()>4)throw bad();
                for(var value:list){try{if(!value.isTextual())throw bad();focus.add(ReviewModels.Category.valueOf(value.asText()));}catch(IllegalArgumentException ex){throw bad();}}
            }
            base=sha(request,"expectedBaseSha");head=sha(request,"expectedHeadSha");
        }
        if(number<1)throw bad();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reviews.review(users.currentUser(auth).getId(),repositoryId,number,focus,base,head));
    }
    private String sha(JsonNode n,String key){if(!n.has(key))return null;if(!n.path(key).isTextual() || !n.path(key).asText().matches("[a-fA-F0-9]{40}"))throw bad();return n.path(key).asText();}
    private ApiException bad(){return new ApiException(HttpStatus.BAD_REQUEST,"Invalid pull request review request");}
}
