package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.aloute.model.document.Post;
import com.aloute.posts.infrastructure.PostFeedReader;
import com.aloute.service.ContentAccessService;
import com.aloute.service.PostResponseMapper;
import com.aloute.service.RepostPresentationService;
import com.aloute.social.api.FollowedAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/** Opt-in bounded synthetic workload. Refuses shared/production databases; never deletes records. */
@EnabledIfEnvironmentVariable(named="ALOUTE_PERFORMANCE_TEST", matches="true")
class DocumentPerformanceIT {
    @Test
    void measureCandidateRetrievalAndExistenceWithoutPretendingItIsHttpLatency() throws Exception {
        String url=System.getenv("ALOUTE_PERFORMANCE_DB_URL");
        if(url==null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aloute_quality_performance_[A-Za-z0-9_]+"))
            throw new IllegalArgumentException("Requires a dedicated local aloute_quality_performance_* database");
        try(var pool=new HikariDataSource()) {
            pool.setJdbcUrl(url); pool.setUsername("aloute_test"); pool.setPassword("test-only"); pool.setMaximumPoolSize(4);
            Flyway.configure().dataSource(pool).cleanDisabled(true).load().migrate();
            var counter=new AtomicLong();
            var measured=new DelegatingDataSource(pool) {
                @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                    var connection=super.getConnection();
                    return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{java.sql.Connection.class},(proxy,method,args)-> {
                                if(method.getName().equals("prepareStatement")) counter.incrementAndGet();
                                try { return method.invoke(connection,args); }
                                catch(java.lang.reflect.InvocationTargetException e) {throw e.getCause();}
                            });
                }
            };
            var jdbc=new JdbcTemplate(measured);
            jdbc.execute("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT DO NOTHING");
            jdbc.execute("INSERT INTO users(id,username,email,password,full_name,role_id) SELECT g,'perf-'||g,'perf-'||g||'@example.invalid','test-only','Performance test '||g,(SELECT id FROM roles WHERE name='ROLE_USER') FROM generate_series(1,8009) g ON CONFLICT(id) DO NOTHING");
            jdbc.execute("""
                INSERT INTO app_document_records(collection_name,document_id,payload)
                SELECT 'posts','perf-'||lpad(g::text,8,'0'),jsonb_build_object(
                    'id','perf-'||lpad(g::text,8,'0'),'userId',((g%8000)+10)::text,'content',repeat('synthetic ',32),
                    'createdAt',CASE WHEN g%2=0 THEN to_jsonb(to_char(timestamp '2026-10-23'+g*interval '1 second','YYYY-MM-DD"T"HH24:MI:SS')||'.183036801')
                    ELSE jsonb_build_array(2026,10,23+(g/86400),((g/3600)%24),((g/60)%60),(g%60),183036800) END)
                FROM generate_series(1,40000) g ON CONFLICT DO NOTHING
                """);
            jdbc.execute("INSERT INTO friends(follower_id,followed_id) SELECT 1,g FROM generate_series(10,5009) g ON CONFLICT DO NOTHING");
            jdbc.execute("INSERT INTO friends(follower_id,followed_id) SELECT 2,g FROM generate_series(10,29) g ON CONFLICT DO NOTHING");
            jdbc.execute("ANALYZE app_document_records"); jdbc.execute("ANALYZE friends");
            var store=new PostgresDocumentStore(jdbc,new ObjectMapper().findAndRegisterModules());
            FollowedAccounts follows=(viewer,after,limit)->jdbc.queryForList(
                    "SELECT followed_id FROM friends WHERE follower_id=? AND followed_id>? ORDER BY followed_id LIMIT ?",Long.class,viewer,after,limit);
            var access=mock(ContentAccessService.class); var batch=mock(ContentAccessService.AccessBatch.class);
            when(access.forPosts(anyCollection(),any())).thenReturn(batch); when(batch.readable(anyCollection())).thenReturn(List.of());
            var mapper=mock(PostResponseMapper.class);
            when(mapper.toReadableResponses(anyList(),any(),any())).thenReturn(List.of());
            var reader=new PostFeedReader(store,access,mapper,mock(RepostPresentationService.class),follows);
            var results=new LinkedHashMap<String,Object>();
            var os=(com.sun.management.OperatingSystemMXBean)java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long cpuStart=os.getProcessCpuTime();
            results.put("scope","candidate SQL + deserialize only; authorization/hydration doubles; not HTTP latency");
            results.put("posts",40000); results.put("pool",4); results.put("samples",30);
            for(long viewer:List.of(1L,2L,3L)) {
                Runnable workload=()-> {var cards=reader.readBatch(viewer,List.of(Long.toString(viewer)),true,true,"",null,50);
                    assertTrue(cards.size()<=50);};
                results.put("following-"+(viewer==1?5000:viewer==2?20:0),measure(workload,counter,30));
                System.out.println("PERFORMANCE scenario viewer="+viewer+" "+results);
            }
            results.put("foryou",measure(()->reader.readBatch(1L,List.of(),false,false,"",null,50),counter,30));
            results.put("exists-40000-matches",measure(()->assertTrue(store.exists(new Query(),Post.class)),counter,30));
            // Retain the prior bounded-batch algorithm as an oracle/paired benchmark, not production code.
            var oldTimes=new ArrayList<Long>();var newTimes=new ArrayList<Long>();long oldQueries=0,newQueries=0;
            var order=new Random(20261024L);
            for(int i=0;i<35;i++) {
                List<Post> old;List<com.aloute.posts.api.FeedPostReader.Candidate> current;
                long before=counter.get(),start=System.nanoTime();
                if(order.nextBoolean()) {
                    old=legacyCandidates(store,follows,1L,50);
                    if(i>=5){oldTimes.add(System.nanoTime()-start);oldQueries+=counter.get()-before;}
                    before=counter.get();start=System.nanoTime();current=reader.readBatch(1L,List.of("1"),true,true,"",null,50);
                    if(i>=5){newTimes.add(System.nanoTime()-start);newQueries+=counter.get()-before;}
                } else {
                    current=reader.readBatch(1L,List.of("1"),true,true,"",null,50);
                    if(i>=5){newTimes.add(System.nanoTime()-start);newQueries+=counter.get()-before;}
                    before=counter.get();start=System.nanoTime();old=legacyCandidates(store,follows,1L,50);
                    if(i>=5){oldTimes.add(System.nanoTime()-start);oldQueries+=counter.get()-before;}
                }
                assertEquals(old.stream().map(Post::getId).toList(),current.stream().map(c->c.position().id()).toList());
            }
            var oldStats=summary(oldTimes);oldStats.put("sqlStatementsPerRequest",oldQueries/30.0);
            var newStats=summary(newTimes);newStats.put("sqlStatementsPerRequest",newQueries/30.0);
            results.put("paired-following-5000-before",oldStats);results.put("paired-following-5000-after",newStats);
            var workers=Executors.newFixedThreadPool(8);
            try {
                long start=System.nanoTime(); var tasks=new ArrayList<Future<Long>>();
                for(int i=0;i<80;i++) tasks.add(workers.submit(()->{long t=System.nanoTime();reader.readBatch(1L,List.of("1"),true,true,"",null,50);return System.nanoTime()-t;}));
                var times=new ArrayList<Long>();for(var task:tasks)times.add(task.get(60,TimeUnit.SECONDS));
                var load=summary(times);load.put("concurrency",8);load.put("requests",80);load.put("throughputPerSecond",80/((System.nanoTime()-start)/1e9));
                results.put("following-5000-concurrent",load);
            } finally {workers.shutdownNow();}
            results.put("processCpuMsForWorkload",(os.getProcessCpuTime()-cpuStart)/1e6);
            results.put("heapUsedBytesAtEnd",java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            results.put("heapMaxBytes",Runtime.getRuntime().maxMemory());
            results.put("databaseBytes",jdbc.queryForObject("SELECT pg_database_size(current_database())",Long.class));
            String phase=System.getenv().getOrDefault("ALOUTE_PERFORMANCE_PHASE","unspecified");
            Files.createDirectories(Path.of("target/performance"));
            for(long viewer:List.of(1L,2L,3L)) {
                Criteria scope=viewer==3 ? Criteria.where("userId").is("3") : Criteria.where("userId").inReadView(FollowedAccounts.READ_VIEW,viewer,viewer);
                Criteria criterion=new Criteria().andOperator(base(),scope);
                var params=new ArrayList<Object>();
                var compiler=new SqlCriteriaCompiler(new ObjectMapper().findAndRegisterModules(),Post.class);
                String where=compiler.compile(criterion,params);
                String sql="EXPLAIN (ANALYZE, BUFFERS) SELECT d.payload::text FROM app_document_records d WHERE d.collection_name='posts' AND ("+where+") ORDER BY "+compiler.sortExpression("createdAt","d")+" DESC,d.document_id DESC LIMIT 50";
                Files.write(Path.of("target/performance/"+phase+"-plan-following-"+viewer+".txt"),jdbc.queryForList(sql,String.class,params.toArray()));
            }
            var legacyIds=new ArrayList<>(follows.followedIdsAfter(1L,0L,500).stream().map(String::valueOf).toList());legacyIds.add("1");
            var legacyParams=new ArrayList<Object>();
            var compiler=new SqlCriteriaCompiler(new ObjectMapper().findAndRegisterModules(),Post.class);
            String legacyWhere=compiler.compile(new Criteria().andOperator(base(),Criteria.where("userId").in(legacyIds)),legacyParams);
            Files.write(Path.of("target/performance/legacy-plan-one-500-author-batch.txt"),jdbc.queryForList(
                    "EXPLAIN (ANALYZE, BUFFERS) SELECT d.payload::text FROM app_document_records d WHERE d.collection_name='posts' AND ("+legacyWhere+") ORDER BY "+compiler.sortExpression("createdAt","d")+" DESC,d.document_id DESC LIMIT 50",String.class,legacyParams.toArray()));
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(Path.of("target/performance/"+phase+".json").toFile(),results);
            System.out.println("PERFORMANCE "+new ObjectMapper().writeValueAsString(results));
        }
    }

    private Criteria base() {
        return new Criteria().andOperator(Criteria.where("createdAt").validDateTime(),Criteria.where("moderationStatus").ne(com.aloute.model.document.ModerationStatus.HIDDEN),
                new Criteria().orOperator(Criteria.where("repostOfId").is(null),new Criteria().andOperator(Criteria.where("repostOfId").ne(null),Criteria.where("content").nin(null,""))));
    }
    private List<Post> legacyCandidates(PostgresDocumentStore store,FollowedAccounts follows,long viewer,int limit) {
        long after=0;boolean first=true;var newest=new ArrayList<Post>();
        while(true) {
            var ids=follows.followedIdsAfter(viewer,after,500);var chunk=new ArrayList<>(ids.stream().map(String::valueOf).toList());
            if(first){chunk.add(Long.toString(viewer));first=false;}
            if(!chunk.isEmpty()) {
                newest.addAll(store.find(Query.query(new Criteria().andOperator(base(),Criteria.where("userId").in(chunk)))
                        .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("createdAt"),org.springframework.data.domain.Sort.Order.desc("id"))).limit(limit),Post.class));
                newest.sort(Comparator.comparing(Post::getCreatedAt,Comparator.reverseOrder()).thenComparing(Post::getId,Comparator.reverseOrder()));
                if(newest.size()>limit)newest.subList(limit,newest.size()).clear();
            }
            if(ids.size()<500)break;after=ids.getLast();
        }
        return newest;
    }

    private Map<String,Object> measure(Runnable work,AtomicLong counter,int count) {
        for(int i=0;i<5;i++)work.run();
        long before=counter.get();var times=new ArrayList<Long>();
        for(int i=0;i<count;i++){long start=System.nanoTime();work.run();times.add(System.nanoTime()-start);}
        var result=summary(times);result.put("sqlStatementsPerRequest",(counter.get()-before)/(double)count);return result;
    }
    private Map<String,Object> summary(List<Long> times) {
        Collections.sort(times);var result=new LinkedHashMap<String,Object>();
        for(int p:List.of(50,95,99))result.put("p"+p+"Ms",times.get((int)Math.ceil(times.size()*p/100.0)-1)/1e6);
        return result;
    }
}
