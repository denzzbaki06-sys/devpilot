package com.devpilot.architecture;

import com.devpilot.indexing.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.util.*;
import java.util.regex.*;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import static com.devpilot.architecture.ArchitectureModels.*;

@Service
public class ArchitectureEngine {
    private final ArchitectureProperties limits;
    private final CodeChunker chunker;
    private final SourceFilePolicy policy;
    public ArchitectureEngine(ArchitectureProperties limits,CodeChunker chunker,SourceFilePolicy policy){this.limits=limits;this.chunker=chunker;this.policy=policy;}
    record Pending(Component source,String target,Relation type,Evidence evidence,Map<String,String> imports,String pkg) {}
    static final Map<String,Type> STEREOTYPES=Map.of(
        "org.springframework.web.bind.annotation.RestController",Type.CONTROLLER,"org.springframework.stereotype.Controller",Type.CONTROLLER,
        "org.springframework.stereotype.Service",Type.SERVICE,"org.springframework.stereotype.Repository",Type.REPOSITORY,
        "org.springframework.stereotype.Component",Type.COMPONENT,"org.springframework.context.annotation.Configuration",Type.CONFIGURATION,
        "jakarta.persistence.Entity",Type.ENTITY,"javax.persistence.Entity",Type.ENTITY,
        "org.springframework.boot.autoconfigure.SpringBootApplication",Type.ENTRY_POINT,
        "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity",Type.SECURITY);
    static final Set<String> DATA_REPOS=Set.of("org.springframework.data.jpa.repository.JpaRepository","org.springframework.data.repository.CrudRepository","org.springframework.data.repository.ListCrudRepository","org.springframework.data.repository.PagingAndSortingRepository");
    public Response analyze(long repo,String sha,List<Source> sources,List<String> initialWarnings,int skipped){
        var nodes=new ArrayList<Component>();var pending=new ArrayList<Pending>();var external=new ArrayList<ExternalDependency>();var warnings=new TreeSet<>(initialWarnings);
        int analyzed=0;
        for(var source:sources.stream().sorted(Comparator.comparing(Source::path)).toList()){
            byte[] bytes=source.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(!Set.of("JAVA","JAVASCRIPT","JAVASCRIPT_REACT","TYPESCRIPT","TYPESCRIPT_REACT","PYTHON").contains(source.language()) || policy.skipReason(source.path(),"100644",bytes.length)!=null || policy.normalize(bytes).skipReason()!=null || com.devpilot.review.ReviewSafety.sensitive(source.content())){skipped++;warnings.add("Sensitive, generated or unsupported indexed sources were omitted.");continue;}
            if(source.language().equals("JAVA")){
                int n=nodes.size(),p=pending.size();
                if(!java(source,nodes,pending)){nodes.subList(n,nodes.size()).clear();pending.subList(p,pending.size()).clear();warnings.add("Java parse unavailable or invalid: "+source.path());skipped++;continue;}
            }else script(source,nodes,pending,external,warnings);
            analyzed++;
        }
        nodes.sort(Comparator.comparing(Component::path).thenComparingInt(Component::startLine).thenComparing(Component::qualifiedName));
        var full=List.copyOf(nodes);
        if(nodes.size()>limits.maxComponents()){nodes=new ArrayList<>(nodes.subList(0,limits.maxComponents()));warnings.add("Architecture graph was limited to "+limits.maxComponents()+" components.");}
        var ids=new HashSet<String>();nodes.forEach(n->ids.add(n.id()));
        var edges=new TreeMap<String,Relationship>();int unresolved=0;
        for(var p:pending){
            var candidates=resolve(p,full);
            if(candidates.size()!=1){unresolved++;continue;}
            var target=candidates.getFirst();
            if(target.id().equals(p.source.id()) || !ids.contains(p.source.id()) || !ids.contains(target.id()))continue;
            String key=p.source.id()+":"+target.id()+":"+p.type;
            edges.putIfAbsent(key,new Relationship(p.source.id(),target.id(),p.type,p.evidence));
        }
        var relationships=new ArrayList<>(edges.values());
        if(relationships.size()>limits.maxRelationships()){relationships=new ArrayList<>(relationships.subList(0,limits.maxRelationships()));warnings.add("Architecture graph was limited to "+limits.maxRelationships()+" relationships.");}
        if(unresolved>0)warnings.add(unresolved+" references were unresolved or ambiguous; no target nodes were invented.");
        if(external.size()>limits.maxRelationships())warnings.add("External import evidence was limited to "+limits.maxRelationships()+" entries.");
        var entries=nodes.stream().filter(c->Boolean.TRUE.equals(c.metadata().get("entryPoint"))).toList();
        var languages=nodes.stream().map(Component::language).distinct().sorted().toList();
        return new Response(repo,sha,List.copyOf(nodes),List.copyOf(relationships),entries,
            external.stream().distinct().limit(limits.maxRelationships()).toList(),new Stats(nodes.size(),relationships.size(),entries.size(),languages,analyzed,skipped,unresolved),List.copyOf(warnings));
    }
    List<Component> resolve(Pending p,List<Component> all){
        if(!p.source.language().equals("JAVA"))return all.stream().filter(c->c.type()==Type.MODULE&&Arrays.asList(p.target.split("\\|",-1)).contains(c.path())).toList();
        String name=raw(p.target);String qualified;
        if(name.contains("."))qualified=name;
        else if(p.imports.containsKey(name))qualified=p.imports.get(name);
        else qualified=p.pkg.isEmpty()?name:p.pkg+"."+name;
        String q=qualified;
        return all.stream().filter(c->c.language().equals("JAVA")&&c.qualifiedName().equals(q)).toList();
    }
    static String raw(String s){return s.replaceAll("<.*>","").replace("[]","").strip();}
    static String id(String language,String path,String qualified){return "A"+ContentHashes.sha256(language+":"+path+":"+qualified).substring(0,24);}
    static Component node(Source s,String name,String qualified,Type type,int start,int end,Map<String,Object> metadata){
        return new Component(id(s.language(),s.path(),qualified),name,qualified,type,s.language(),s.path(),start,end,name,Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
    }
    static String qualify(String n,Map<String,String> imports){return imports.getOrDefault(n,n);}
    static boolean annotation(ModifiersTree m,String fqn,Map<String,String> imports){return m.getAnnotations().stream().anyMatch(a->qualify(a.getAnnotationType().toString(),imports).equals(fqn));}
    boolean java(Source source,List<Component> nodes,List<Pending> pending){
        return JavaSourceParser.parse(source.content(),(unit,positions)->{
            String pkg=unit.getPackageName()==null?"":unit.getPackageName().toString();
            var imports=new HashMap<String,String>();
            for(var imp:unit.getImports())if(!imp.isStatic()){
                String fqn=imp.getQualifiedIdentifier().toString();
                if(fqn.endsWith(".*")){
                    // Only known framework names; application wildcard resolution is deliberately not guessed.
                    var known=new HashSet<>(STEREOTYPES.keySet());known.addAll(DATA_REPOS);
                    for(String n:List.of("RequestMapping","GetMapping","PostMapping","PutMapping","PatchMapping","DeleteMapping"))known.add("org.springframework.web.bind.annotation."+n);
                    known.addAll(Set.of("org.springframework.context.annotation.Bean","org.springframework.beans.factory.annotation.Autowired","jakarta.persistence.Table"));
                    for(String k:known)if(k.startsWith(fqn.substring(0,fqn.length()-1))&&!imports.containsKey(k.substring(k.lastIndexOf('.')+1)))imports.put(k.substring(k.lastIndexOf('.')+1),k);
                }else imports.put(fqn.substring(fqn.lastIndexOf('.')+1),fqn);
            }
            new TreeScanner<Void,String>(){
                Evidence evidence(Tree tree,String symbol){long a=positions.getStartPosition(unit,tree),b=positions.getEndPosition(unit,tree);return new Evidence(source.path(),(int)unit.getLineMap().getLineNumber(a),(int)unit.getLineMap().getLineNumber(Math.max(a,b-1)),symbol);}
                @Override public Void visitClass(ClassTree tree,String parent){
                    String name=tree.getSimpleName().toString();if(name.isEmpty())return null;
                    String qualified=(parent==null?(pkg.isEmpty()?"":pkg+"."):parent+".")+name;
                    Type type=Type.COMPONENT;var metadata=new LinkedHashMap<String,Object>();
                    metadata.put("declaration",tree.getKind().name());
                    var annotations=tree.getModifiers().getAnnotations().stream().map(a->qualify(a.getAnnotationType().toString(),imports)).sorted().toList();
                    metadata.put("annotations",annotations);
                    for(String a:annotations)if(STEREOTYPES.containsKey(a))type=STEREOTYPES.get(a);
                    var bases=new ArrayList<Tree>();if(tree.getExtendsClause()!=null)bases.add(tree.getExtendsClause());bases.addAll(tree.getImplementsClause());
                    for(var b:bases)if(DATA_REPOS.contains(qualify(raw(b.toString()),imports)))type=Type.REPOSITORY;
                    if(annotations.contains("org.springframework.security.config.annotation.web.configuration.EnableWebSecurity"))type=Type.SECURITY;
                    var methods=tree.getMembers().stream().filter(t->t instanceof MethodTree).map(t->(MethodTree)t).toList();
                    var beans=methods.stream().filter(m->annotation(m.getModifiers(),"org.springframework.context.annotation.Bean",imports)).map(m->m.getName().toString()).toList();
                    metadata.put("beanMethods",beans);
                    metadata.put("methods",methods.stream().filter(m->m.getReturnType()!=null).map(m->m.getName().toString()).distinct().toList());
                    metadata.put("fields",tree.getMembers().stream().filter(t->t instanceof VariableTree).map(t->((VariableTree)t).getName().toString()).toList());
                    metadata.put("constructors",methods.stream().filter(m->m.getReturnType()==null).map(m->m.getParameters().stream().map(v->v.getType().toString()).toList()).toList());

                    if(type==Type.CONFIGURATION && methods.stream().anyMatch(m->m.getReturnType()!=null && qualify(m.getReturnType().toString(),imports).equals("org.springframework.security.web.SecurityFilterChain")))type=Type.SECURITY;
                    boolean main=methods.stream().anyMatch(m->m.getName().contentEquals("main") && m.getModifiers().getFlags().containsAll(Set.of(javax.lang.model.element.Modifier.PUBLIC,javax.lang.model.element.Modifier.STATIC)) && m.getReturnType()!=null && m.getReturnType().toString().equals("void") && m.getParameters().size()==1 && Set.of("String[]","java.lang.String[]").contains(m.getParameters().getFirst().getType().toString()));
                    boolean entry=main||annotations.contains("org.springframework.boot.autoconfigure.SpringBootApplication");metadata.put("entryPoint",entry);if(entry)type=Type.ENTRY_POINT;
                    if(type==Type.ENTITY)for(var a:tree.getModifiers().getAnnotations()){
                        String q=qualify(a.getAnnotationType().toString(),imports);
                        if(Set.of("jakarta.persistence.Table","javax.persistence.Table","jakarta.persistence.Entity","javax.persistence.Entity").contains(q)){
                            var values=attribute(a,"name");if(!values.isEmpty())metadata.put(q.endsWith("Table")?"tableName":"entityName",values.getFirst());
                        }
                    }
                    if(type==Type.CONTROLLER)metadata.put("routes",routes(tree,methods,imports));
                    boolean http=tree.getMembers().stream().filter(t->t instanceof VariableTree).map(t->(VariableTree)t).anyMatch(v->Set.of("org.springframework.web.client.RestClient","org.springframework.web.reactive.function.client.WebClient","java.net.http.HttpClient").contains(qualify(raw(v.getType().toString()),imports)));
                    if(http && Set.of(Type.SERVICE,Type.COMPONENT,Type.UTILITY).contains(type)){type=Type.EXTERNAL_CLIENT;metadata.put("transport","HTTP client field; remote service not inferred");}
                    Evidence location=evidence(tree,name);if(location.startLine()<1||location.endLine()>source.lineCount())return null;
                    var component=node(source,name,qualified,type,location.startLine(),location.endLine(),metadata);nodes.add(component);
                    for(var b:bases){
                        Relation relation=tree.getKind()==Tree.Kind.INTERFACE || b==tree.getExtendsClause()?Relation.EXTENDS:Relation.IMPLEMENTS;
                        String target=raw(b.toString());
                        if(DATA_REPOS.contains(qualify(target,imports))){if(b instanceof ParameterizedTypeTree pt && !pt.getTypeArguments().isEmpty())pending.add(new Pending(component,pt.getTypeArguments().getFirst().toString(),Relation.MANAGES_ENTITY,evidence(b,name),imports,pkg));}
                        else if(!qualify(target,imports).startsWith("java."))pending.add(new Pending(component,target,relation,evidence(b,name),imports,pkg));
                    }
                    var constructors=methods.stream().filter(m->m.getReturnType()==null).toList();
                    boolean spring=annotations.stream().anyMatch(STEREOTYPES::containsKey);
                    var typeParameters=new HashSet<String>();tree.getTypeParameters().forEach(t->typeParameters.add(t.getName().toString()));
                    for(var m:constructors)if((spring&&constructors.size()==1)||annotation(m.getModifiers(),"org.springframework.beans.factory.annotation.Autowired",imports))
                        for(var parameter:m.getParameters())if(!typeParameters.contains(raw(parameter.getType().toString())) && m.getTypeParameters().stream().noneMatch(t->t.getName().contentEquals(raw(parameter.getType().toString()))))pending.add(new Pending(component,parameter.getType().toString(),Relation.DEPENDS_ON,evidence(parameter,name),imports,pkg));
                    for(var t:tree.getMembers())if(t instanceof VariableTree v && !typeParameters.contains(raw(v.getType().toString())) && annotation(v.getModifiers(),"org.springframework.beans.factory.annotation.Autowired",imports))pending.add(new Pending(component,v.getType().toString(),Relation.INJECTS,evidence(v,name),imports,pkg));
                    // Only member types: local/anonymous classes and method calls require symbol resolution, so omit them.
                    for(var t:tree.getMembers())if(t instanceof ClassTree)scan(t,qualified);
                    return null;
                }
            }.scan(unit,null);
        });
    }
    static List<String> literals(ExpressionTree value){
        if(value instanceof LiteralTree l && l.getValue() instanceof String s && s.length()<=200 && !s.matches(".*[\\r\\n].*"))return List.of(s);
        if(value instanceof NewArrayTree a && a.getInitializers()!=null)return a.getInitializers().stream().flatMap(v->literals(v).stream()).toList();
        return List.of();
    }
    static List<String> attribute(AnnotationTree a,String key){
        for(var arg:a.getArguments()){
            if(arg instanceof AssignmentTree assign && assign.getVariable().toString().equals(key))return literals(assign.getExpression());
            if(key.equals("value") && !(arg instanceof AssignmentTree))return literals(arg);
        }
        return List.of();
    }
    record Mapping(List<String> paths,List<String> methods) {}
    static Mapping mapping(ModifiersTree modifiers,Map<String,String> imports){
        for(var a:modifiers.getAnnotations()){
            String q=qualify(a.getAnnotationType().toString(),imports);
            if(!q.startsWith("org.springframework.web.bind.annotation."))continue;
            String name=q.substring(q.lastIndexOf('.')+1);if(!Set.of("RequestMapping","GetMapping","PostMapping","PutMapping","PatchMapping","DeleteMapping").contains(name))continue;
            var paths=attribute(a,"path");if(paths.isEmpty())paths=attribute(a,"value");
            // Nonliteral route expressions cannot be claimed as concrete paths.
            boolean explicit=a.getArguments().stream().anyMatch(x->!(x instanceof AssignmentTree)||Set.of("path","value").contains(((AssignmentTree)x).getVariable().toString()));
            if(paths.isEmpty()&&explicit)return null;
            var methods=new ArrayList<String>();
            if(!name.equals("RequestMapping"))methods.add(name.replace("Mapping","").toUpperCase(Locale.ROOT));
            else for(var arg:a.getArguments())if(arg instanceof AssignmentTree assign && assign.getVariable().toString().equals("method")){
                ExpressionTree value=assign.getExpression();
                List<? extends ExpressionTree> values=value instanceof NewArrayTree array && array.getInitializers()!=null?array.getInitializers():List.of(value);
                for(var v:values){
                    if(!(v instanceof MemberSelectTree select) || !Set.of("RequestMethod","org.springframework.web.bind.annotation.RequestMethod").contains(select.getExpression().toString())
                            || !Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS","TRACE").contains(select.getIdentifier().toString()))return null;
                    methods.add(select.getIdentifier().toString());
                }
                if(methods.isEmpty())return null;
            }
            return new Mapping(paths.isEmpty()?List.of(""):paths,methods.isEmpty()?List.of("ANY"):methods);
        }
        return null;
    }
    static String joinRoute(String prefix,String path){
        String joined;
        if(prefix.isEmpty())joined=path;
        else if(path.isEmpty())joined=prefix;
        else if(prefix.endsWith("/")&&path.startsWith("/"))joined=prefix+path.substring(1);
        else if(!prefix.endsWith("/")&&!path.startsWith("/"))joined=prefix+"/"+path;
        else joined=prefix+path;
        return joined.startsWith("/")?joined:"/"+joined;
    }
    static List<Map<String,Object>> routes(ClassTree type,List<MethodTree> methods,Map<String,String> imports){
        var base=mapping(type.getModifiers(),imports);var result=new ArrayList<Map<String,Object>>();
        boolean classMapping=type.getModifiers().getAnnotations().stream().anyMatch(a->qualify(a.getAnnotationType().toString(),imports).equals("org.springframework.web.bind.annotation.RequestMapping"));
        if(base==null&&classMapping)return result;
        for(var method:methods){var mapping=mapping(method.getModifiers(),imports);if(mapping==null)continue;
            for(String prefix:base==null?List.of(""):base.paths)for(String path:mapping.paths){
                var verbs=mapping.methods;
                if(base!=null&&!base.methods.equals(List.of("ANY")))verbs=verbs.equals(List.of("ANY"))?base.methods:verbs.stream().filter(base.methods::contains).toList();
                if(!verbs.isEmpty())result.add(Map.of("path",joinRoute(prefix,path),"methods",verbs,"symbol",method.getName().toString()));
            }
        }
        return result;
    }
    void script(Source source,List<Component> nodes,List<Pending> pending,List<ExternalDependency> external,Set<String> warnings){
        boolean python=source.language().equals("PYTHON");
        String clean=mask(source.content(),python);String[] rows=source.content().split("\n",-1),masked=clean.split("\n",-1);
        var module=node(source,source.path().substring(source.path().lastIndexOf('/')+1),source.path(),Type.MODULE,1,source.lineCount(),Map.of("analysis","STATIC_MODULE"));nodes.add(module);
        for(var symbol:chunker.symbols(source.content(),source.language())){
            if(symbol.start()<1||symbol.start()>masked.length||symbol.end()>source.lineCount())continue;
            String row=masked[symbol.start()-1];
            // Only top-level explicit declarations. No React/framework or nested-scope inference.
            boolean declaration=python?row.matches("^(?:async\\s+)?(?:def|class)\\s+"+Pattern.quote(symbol.name())+"\\b.*"):
                row.matches("^(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(?:function|class)\\s+"+Pattern.quote(symbol.name())+"\\b.*");
            if(declaration)nodes.add(node(source,symbol.name(),source.path()+"#"+symbol.name()+":"+symbol.start(),symbol.type().equals("CLASS")?Type.COMPONENT:Type.FUNCTION,symbol.start(),symbol.end(),Map.of("analysis","LIGHTWEIGHT_DECLARATION")));
        }
        for(int i=0;i<rows.length;i++){
            if(!masked[i].matches("^(?:import|from)\\s+.*"))continue;
            String target=null;
            if(python){
                var from=Pattern.compile("^from\\s+([.\\w]+)\\s+import\\s+.+$").matcher(rows[i]);
                var imp=Pattern.compile("^import\\s+([\\w.]+)(?:\\s+as\\s+\\w+)?\\s*(?:#.*)?$").matcher(rows[i]);
                if(from.matches())target=from.group(1);else if(imp.matches())target=imp.group(1);
            }else{
                var imp=Pattern.compile("^import\\s+(?:[^;]*?\\s+from\\s+)?['\"]([^'\"]+)['\"]\\s*;?\\s*(?://.*)?$").matcher(rows[i]);
                if(imp.matches())target=imp.group(1);
            }
            if(target==null)continue;
            Evidence evidence=new Evidence(source.path(),i+1,i+1,module.name());
            if(python){
                String path;
                if(target.startsWith(".")){
                    int dots=0;while(dots<target.length()&&target.charAt(dots)=='.')dots++;
                    Path parent=Path.of(source.path()).getParent();if(parent==null)continue;
                    for(int d=1;d<dots&&parent!=null;d++)parent=parent.getParent();if(parent==null)continue;
                    path=parent.resolve(target.substring(dots).replace('.','/')).normalize().toString();
                }else path=target.replace('.','/');
                // Candidate alternatives are resolved after all modules exist, without fabricating a package.
                pending.add(new Pending(module,path+".py|"+path+"/__init__.py",Relation.DEPENDS_ON,evidence,Map.of(),""));
            }else if(target.startsWith(".")){
                Path parent=Path.of(source.path()).getParent();String path=(parent==null?Path.of(target):parent.resolve(target)).normalize().toString();
                if(path.startsWith("..")||path.startsWith("/"))continue;
                String options=path;
                if(!path.matches(".*\\.(?:js|jsx|ts|tsx)$"))for(String ext:List.of("js","jsx","ts","tsx"))options+="|"+path+"."+ext+"|"+path+"/index."+ext;
                pending.add(new Pending(module,options,Relation.DEPENDS_ON,evidence,Map.of(),""));
            }else if(target.matches("(?:@[A-Za-z0-9_-]+/)?[A-Za-z0-9_-]+(?:/[A-Za-z0-9_.-]+)*"))external.add(new ExternalDependency(target,source.language(),evidence));
        }
        warnings.add("JS/TS/Python support is limited to top-level declarations and static imports; dynamic imports, aliases, re-exports and framework inference are omitted.");
    }
    /** Offset-preserving lexical mask; never execute repository scripts. */
    static String mask(String source,boolean python){
        char[] result=source.toCharArray();String quote=null;boolean escape=false,block=false,line=false;
        for(int i=0;i<source.length();i++){
            char c=source.charAt(i);String tail=source.substring(i,Math.min(source.length(),i+3));
            if(line){if(c=='\n')line=false;else result[i]=' ';continue;}
            if(block){if(c!='\n')result[i]=' ';if(tail.startsWith("*/")){result[++i]=' ';block=false;}continue;}
            if(quote!=null){
                if(!escape&&source.startsWith(quote,i)){for(int j=0;j<quote.length();j++)result[i+j]=' ';i+=quote.length()-1;quote=null;continue;}
                if(c!='\n')result[i]=' ';if(escape)escape=false;else if(c=='\\')escape=true;continue;
            }
            if(python&&c=='#'||!python&&tail.startsWith("//")){line=true;result[i]=' ';continue;}
            if(!python&&tail.startsWith("/*")){block=true;result[i]=result[++i]=' ';continue;}
            if(c=='\''||c=='"'||!python&&c=='`'){
                quote=python&&(tail.equals("\"\"\"")||tail.equals("'''"))?tail:String.valueOf(c);
                for(int j=0;j<quote.length();j++)result[i+j]=' ';i+=quote.length()-1;
            }
        }
        return new String(result);
    }
}
