package com.devpilot;
import com.devpilot.architecture.*;
import com.devpilot.indexing.*;
import static com.devpilot.architecture.ArchitectureModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

class ArchitectureEngineTests {
    final IndexingProperties indexing=new IndexingProperties(500000,100,15,20000,50000000,20000,100000,1800);
    final CodeChunker chunker=new CodeChunker(indexing);
    ArchitectureEngine engine(int nodes,int edges){return new ArchitectureEngine(new ArchitectureProperties(300,3000000,nodes,edges),chunker,new SourceFilePolicy(indexing));}
    Source source(String path,String lang,String text){return new Source(path,lang,text,text.split("\n",-1).length-(text.endsWith("\n")?1:0));}
    Source java(String text){return source("src/App.java","JAVA",text);}
    Response analyze(Source... files){return engine(1000,3000).analyze(12,"a".repeat(40),List.of(files),List.of(),0);}
    Component named(Response r,String name){return r.components().stream().filter(c->c.name().equals(name)).findFirst().orElseThrow();}
    @ParameterizedTest @CsvSource({
        "org.springframework.web.bind.annotation.RestController,CONTROLLER","org.springframework.stereotype.Controller,CONTROLLER",
        "org.springframework.stereotype.Service,SERVICE","org.springframework.stereotype.Repository,REPOSITORY",
        "org.springframework.stereotype.Component,COMPONENT","org.springframework.context.annotation.Configuration,CONFIGURATION",
        "jakarta.persistence.Entity,ENTITY","javax.persistence.Entity,ENTITY",
        "org.springframework.security.config.annotation.web.configuration.EnableWebSecurity,SECURITY",
        "org.springframework.boot.autoconfigure.SpringBootApplication,ENTRY_POINT"})
    void stereotypes(String annotation,Type type){var r=analyze(java("import "+annotation+";\n@"+annotation.substring(annotation.lastIndexOf('.')+1)+" class App {}"));assertThat(named(r,"App").type()).isEqualTo(type);}
    @Test void plainClassIsGenericComponent(){assertThat(named(analyze(java("class Helper {}")),"Helper").type()).isEqualTo(Type.COMPONENT);}
    @Test void customAnnotationDoesNotPretendToBeSpring(){assertThat(named(analyze(java("@interface Service {} @Service class App {}")),"App").type()).isEqualTo(Type.COMPONENT);}
    @Test void recordsAndInterfaces(){assertThat(analyze(java("record Value(int count) {} interface Port {}" )).components()).extracting(Component::name).containsExactlyInAnyOrder("Value","Port");}
    @Test void constructorDependencies(){var r=analyze(java("import org.springframework.stereotype.Service; @Service class App { App(Repo repo){} } class Repo {}"));assertThat(r.relationships()).hasSize(1);assertThat(r.relationships().getFirst().type()).isEqualTo(Relation.DEPENDS_ON);}
    @Test void multipleConstructorsNotGuessed(){var r=analyze(java("import org.springframework.stereotype.Service; @Service class App { App(){} App(Repo repo){} } class Repo {}"));assertThat(r.relationships()).isEmpty();}
    @Test void annotatedConstructor(){var r=analyze(java("import org.springframework.beans.factory.annotation.Autowired; class App { App(){} @Autowired App(Repo repo){} } class Repo {}"));assertThat(r.relationships()).hasSize(1);}
    @Test void fieldInjection(){var r=analyze(java("import org.springframework.beans.factory.annotation.Autowired; class App { @Autowired Repo repo; } class Repo {}"));assertThat(r.relationships()).extracting(Relationship::type).containsExactly(Relation.INJECTS);}
    @Test void inheritance(){var r=analyze(java("class Base {} interface Port {} class App extends Base implements Port {}"));assertThat(r.relationships()).extracting(Relationship::type).containsExactlyInAnyOrder(Relation.EXTENDS,Relation.IMPLEMENTS);}
    @Test void interfaceExtends(){assertThat(analyze(java("interface Base {} interface Port extends Base {}" )).relationships()).extracting(Relationship::type).containsExactly(Relation.EXTENDS);}
    @Test void managesEntity(){var r=analyze(java("import org.springframework.data.jpa.repository.JpaRepository; import jakarta.persistence.Entity; @Entity class User {} interface Users extends JpaRepository<User,Long> {}"));assertThat(named(r,"Users").type()).isEqualTo(Type.REPOSITORY);assertThat(r.relationships()).extracting(Relationship::type).containsExactly(Relation.MANAGES_ENTITY);}
    @Test void entityTable(){var c=named(analyze(java("import jakarta.persistence.*; @Entity @Table(name=\"people\") class Person {}")),"Person");assertThat(c.metadata().get("tableName")).isEqualTo("people");}
    @Test void routes(){var c=named(analyze(java("import org.springframework.web.bind.annotation.*; @RestController @RequestMapping(\"/api\") class Api { @GetMapping(\"/items\") void get(){} @PostMapping(path={\"/items\",\"/new\"}) void post(){} }")),"Api");String routes=c.metadata().get("routes").toString();assertThat(routes).contains("/api/items","/api/new","GET","POST");}
    @Test void routeConstantsNotInvented(){var c=named(analyze(java("import org.springframework.web.bind.annotation.*; @RestController @RequestMapping(PREFIX) class Api { @GetMapping(\"/items\") void get(){} }")),"Api");assertThat((List<?>)c.metadata().get("routes")).isEmpty();}
    @Test void mainEntryPointNoDuplicate(){var r=analyze(java("class App { public static void main(String[] args) {} }"));assertThat(r.components()).hasSize(1);assertThat(r.entryPoints()).hasSize(1);}
    @Test void wrongMainNotEntry(){assertThat(analyze(java("class App { void main(int n) {} }" )).entryPoints()).isEmpty();}
    @Test void securityBean(){var c=named(analyze(java("import org.springframework.context.annotation.*; import org.springframework.security.web.SecurityFilterChain; @Configuration class Security { @Bean SecurityFilterChain chain(){return null;} }")),"Security");assertThat(c.type()).isEqualTo(Type.SECURITY);assertThat(c.metadata().get("beanMethods")).isEqualTo(List.of("chain"));}
    @Test void externalClientRequiresUsageNotImport(){var r=analyze(java("import org.springframework.web.client.RestClient; class Remote { private RestClient client; } class Local {}"));assertThat(named(r,"Remote").type()).isEqualTo(Type.EXTERNAL_CLIENT);assertThat(named(r,"Local").type()).isEqualTo(Type.COMPONENT);assertThat(r.externalDependencies()).isEmpty();}
    @Test void dedupeAndSelf(){var r=analyze(java("import org.springframework.stereotype.Service; @Service class App { App(App self,Repo a,Repo b){} } class Repo {}"));assertThat(r.relationships()).hasSize(1);}
    @Test void unresolvedNotFabricated(){var r=analyze(java("import org.springframework.stereotype.Service; @Service class App { App(Missing m){} }"));assertThat(r.components()).hasSize(1);assertThat(r.relationships()).isEmpty();assertThat(r.stats().unresolvedReferences()).isEqualTo(1);}
    @Test void standardAndFrameworkImportsNotNodes(){assertThat(analyze(java("import java.util.List; import org.springframework.stereotype.Service; @Service class App {}" )).components()).extracting(Component::name).containsExactly("App");}
    @Test void exactLocation(){var r=analyze(java("\nclass App {\n void run() {}\n}\n"));var c=named(r,"App");assertThat(c.path()).isEqualTo("src/App.java");assertThat(c.startLine()).isEqualTo(2);assertThat(c.endLine()).isEqualTo(4);}
    @Test void deterministicIdsAndOrder(){var a=java("class App {}");var b=source("other/B.java","JAVA","class B {}");assertThat(analyze(a,b)).isEqualTo(analyze(b,a));}
    @Test void componentBudget(){var r=engine(1,30).analyze(1,null,List.of(java("class A {} class B {}")),List.of(),0);assertThat(r.components()).hasSize(1);assertThat(r.warnings()).anyMatch(w->w.contains("limited to 1 components"));}
    @Test void edgeBudget(){var r=engine(10,1).analyze(1,null,List.of(java("interface A {} interface B {} class C implements A,B {}")),List.of(),0);assertThat(r.relationships()).hasSize(1);assertThat(r.warnings()).anyMatch(w->w.contains("limited to 1 relationships"));}
    @ParameterizedTest @ValueSource(strings={"target/App.java","node_modules/App.js","generated/App.java",".env","secrets/App.java","image.png"})
    void excludedFiles(String path){assertThat(analyze(source(path,"JAVA","class App {}" )).components()).isEmpty();}
    @Test void sensitiveLiteral(){assertThat(analyze(java("class App { String apiKey=\"literal-sensitive-test-value\"; }" )).components()).isEmpty();}
    @Test void binaryOmitted(){assertThat(analyze(java("class App {}\u0000")).components()).isEmpty();}
    @Test void jsImportsAndSymbols(){var r=analyze(source("src/a.ts","TYPESCRIPT","import { B } from './b';\nexport class A {}\nexport function run() { return 1; }"),source("src/b.ts","TYPESCRIPT","export class B {}"));assertThat(r.relationships()).hasSize(1);assertThat(r.components()).extracting(Component::name).contains("A","B","run");}
    @Test void jsExternalImport(){var r=analyze(source("a.js","JAVASCRIPT","import React from 'react';\nexport function App() {return null;}"));assertThat(r.externalDependencies()).extracting(ExternalDependency::name).containsExactly("react");assertThat(named(r,"App").type()).isEqualTo(Type.FUNCTION);}
    @Test void pythonImportsAndSymbols(){var r=analyze(source("a.py","PYTHON","import b\nclass A:\n    pass\ndef run():\n    pass\n"),source("b.py","PYTHON","class B:\n    pass\n"));assertThat(r.relationships()).hasSize(1);assertThat(r.components()).extracting(Component::name).contains("A","B","run");}
    @Test void pythonStringInstructionsNotDeclarations(){var r=analyze(source("a.py","PYTHON","text = '''\nclass Fake:\n    pass\nimport b\n'''\n"),source("b.py","PYTHON","pass\n"));assertThat(r.components()).extracting(Component::name).doesNotContain("Fake");assertThat(r.relationships()).isEmpty();}
    @Test void mixedLanguages(){var r=analyze(java("class App {}"),source("a.ts","TYPESCRIPT","export class A {}"),source("a.py","PYTHON","class B:\n    pass\n"));assertThat(r.stats().languages()).containsExactly("JAVA","PYTHON","TYPESCRIPT");}
    @Test void malformedJavaProducesWarning(){var r=analyze(java("class App {"));assertThat(r.components()).isEmpty();assertThat(r.warnings()).anyMatch(w->w.contains("parse"));}
    @Test void emptyGraph(){assertThat(analyze().components()).isEmpty();}
    @Test void ambiguousModulesDoNotResolve(){var r=analyze(source("a.ts","TYPESCRIPT","import x from './b';"),source("b.ts","TYPESCRIPT","export class B {}"),source("b.js","JAVASCRIPT","export class B {}"));assertThat(r.relationships()).isEmpty();assertThat(r.stats().unresolvedReferences()).isEqualTo(1);}
    @Test void explicitJavaImportResolvesOnlyExactPackage(){var r=analyze(source("a/App.java","JAVA","package a; import b.Repo; import org.springframework.stereotype.Service; @Service class App { App(Repo r){} }"),source("b/Repo.java","JAVA","package b; class Repo {}"),source("c/Repo.java","JAVA","package c; class Repo {}"));assertThat(r.relationships()).hasSize(1);assertThat(r.relationships().getFirst().targetComponentId()).isEqualTo(r.components().stream().filter(c->c.qualifiedName().equals("b.Repo")).findFirst().orElseThrow().id());}
    @Test void reconstructOverlapsAndBlankPartitions(){String text="class A {\n\n void run() {}\n}\n";var chunks=chunker.chunk(text,"JAVA").stream().map(c->Map.<String,Object>of("start_line",c.startLine(),"end_line",c.endLine(),"content",c.content())).toList();assertThat(ArchitectureStore.reconstruct(4,ContentHashes.sha256(text),chunks)).isEqualTo(text);}
    @Test void reconstructionRejectsMissingSource(){assertThat(ArchitectureStore.reconstruct(2,ContentHashes.sha256("class A {\n}"),List.of())).isNull();}
    @Test void reconstructionRejectsConflict(){assertThat(ArchitectureStore.reconstruct(1,"x",List.of(Map.of("start_line",1,"end_line",1,"content","a"),Map.of("start_line",1,"end_line",1,"content","b")))).isNull();}

    @Test void genericTypeParameterDoesNotBecomeClassDependency(){var r=analyze(java("import org.springframework.stereotype.Service; @Service class App<T> { App(T value){} } class T {}"));assertThat(r.relationships()).isEmpty();}
    @Test void noGuessedCalls(){var r=analyze(java("class App { void run(){ service.execute(); } } class Service {}"));assertThat(r.relationships()).isEmpty();}
    @Test void unsupportedLanguageHasNoFakeModule(){assertThat(analyze(source("README.md","MARKDOWN","# title")).components()).isEmpty();}
    @Test void snapshotChangeRejected(){
        var store=org.mockito.Mockito.mock(ArchitectureStore.class);var engine=engine(100,100);
        var old=new ArchitectureStore.Snapshot(1,"a");
        org.mockito.Mockito.when(store.load(1,12)).thenReturn(new ArchitectureStore.Loaded(old,List.of(java("class A {}")),List.of(),0));
        org.mockito.Mockito.when(store.snapshot(1,12)).thenReturn(new ArchitectureStore.Snapshot(2,"b"));
        assertThatThrownBy(()->new ArchitectureService(store,engine).analyze(1,12)).isInstanceOf(com.devpilot.exception.ApiException.class).hasMessageContaining("index changed");
    }
    @Test void roughFixtureTiming(){
        var sources=new ArrayList<Source>();for(int i=0;i<30;i++)sources.add(source("src/p"+i+"/App.java","JAVA","package p"+i+"; import org.springframework.stereotype.Service; @Service class App {App(Repo r){}} class Repo {}"));
        long start=System.nanoTime();var r=engine(1000,3000).analyze(1,"fixture",sources,List.of(),0);
        assertThat(r.components()).hasSize(60);assertThat(r.relationships()).hasSize(30);
        System.out.println("Architecture fixture: 30 files / 60 components / 30 edges in "+((System.nanoTime()-start)/1000000)+" ms (parse and graph, no DB)");
    }

    @Test void unresolvedHttpMethodDoesNotBecomeAny(){var c=named(analyze(java("import org.springframework.web.bind.annotation.*; @RestController class Api { @RequestMapping(path=\"/items\",method=METHOD) void get(){} }")),"Api");assertThat((List<?>)c.metadata().get("routes")).isEmpty();}
    @Test void explicitRequestMappingMethods(){var c=named(analyze(java("import org.springframework.web.bind.annotation.*; @RestController class Api { @RequestMapping(path=\"/items\",method={RequestMethod.GET,RequestMethod.POST}) void get(){} }")),"Api");assertThat(c.metadata().get("routes").toString()).contains("GET","POST").doesNotContain("ANY");}

    @Test void rootAndTrailingSlashRoutesPreserved(){var c=named(analyze(java("import org.springframework.web.bind.annotation.*; @RestController class Api { @GetMapping(\"/\") void root(){} @GetMapping(\"/items/\") void items(){} }")),"Api");var routes=(List<?>)c.metadata().get("routes");assertThat(routes).hasSize(2);assertThat(((Map<?,?>)routes.get(0)).get("path")).isEqualTo("/");assertThat(((Map<?,?>)routes.get(1)).get("path")).isEqualTo("/items/");}
}
