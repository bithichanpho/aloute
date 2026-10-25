package com.aloute.persistence.postgres.document;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Sort;
import com.aloute.model.document.Post;
import com.aloute.social.api.FollowedAccounts;
import com.fasterxml.jackson.databind.ObjectMapper;

class FollowingReadViewIT {
    @Test
    void liveReadScopeIncludesSelfPreservesNanoCursorAndTracksUnfollowWithoutCopyingEdges() {
        String url=System.getenv("ALOUTE_SCHEMA_VALIDATION_URL");
        Assumptions.assumeTrue(url!=null,"Disposable PostgreSQL required");
        if(!url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/aloute_quality_[A-Za-z0-9_]+"))throw new IllegalArgumentException("Local test DB only");
        var source=new DriverManagerDataSource(url,System.getenv("ALOUTE_SCHEMA_VALIDATION_USERNAME"),System.getenv("ALOUTE_SCHEMA_VALIDATION_PASSWORD"));
        Flyway.configure().dataSource(source).cleanDisabled(true).load().migrate();
        var jdbc=new JdbcTemplate(source);var store=new PostgresDocumentStore(jdbc,new ObjectMapper().findAndRegisterModules());
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx->{
            tx.setRollbackOnly();
            String tag="follow-it-"+UUID.randomUUID();
            Long role=jdbc.queryForObject("INSERT INTO roles(name) VALUES ('ROLE_USER') ON CONFLICT(name) DO UPDATE SET name=EXCLUDED.name RETURNING id",Long.class);
            var ids=new ArrayList<Long>();
            for(int i=0;i<3;i++)ids.add(jdbc.queryForObject("INSERT INTO users(username,email,password,full_name,role_id) VALUES (?,?,?,?,?) RETURNING id",Long.class,tag+i,tag+i+"@example.invalid","test-only",tag,role));
            jdbc.update("INSERT INTO friends(follower_id,followed_id) VALUES (?,?)",ids.get(0),ids.get(1));
            for(int i=0;i<3;i++)jdbc.update("INSERT INTO app_document_records(collection_name,document_id,payload) VALUES ('posts',?,?::jsonb)",tag+i,
                    "{\"id\":\""+tag+i+"\",\"userId\":\""+ids.get(i)+"\",\"createdAt\":"+(i==0?"[2026,10,23,0,21,23,183036801]":"\"2026-10-23T00:21:23.183036800\"")+"}");
            java.util.function.Supplier<Query> query=()->Query.query(Criteria.where("_id").in(List.of(tag+0,tag+1,tag+2)))
                    .addCriteria(Criteria.where("userId").inReadView(FollowedAccounts.READ_VIEW,ids.get(0),ids.get(0)))
                    .with(Sort.by(Sort.Order.desc("createdAt"),Sort.Order.desc("id")));
            var first=store.find(query.get().limit(1),Post.class).getFirst();assertEquals(tag+0,first.getId());
            assertEquals(List.of(tag+1),store.find(query.get().addCriteria(Criteria.where("createdAt").lt(first.getCreatedAt())),Post.class).stream().map(Post::getId).toList());
            jdbc.update("DELETE FROM friends WHERE follower_id=? AND followed_id=?",ids.get(0),ids.get(1));
            assertEquals(List.of(tag+0),store.find(query.get(),Post.class).stream().map(Post::getId).toList());
            assertTrue(store.exists(query.get().limit(0).skip(999999),Post.class)); // Historical semantics ignore pagination.
            assertFalse(store.exists(Query.query(Criteria.where("_id").is(tag+"-absent")),Post.class));
            assertEquals(3,store.count(Query.query(Criteria.where("_id").in(List.of(tag+0,tag+1,tag+2))),Post.class));
        });
    }

    @Test void readRelationCannotInjectSql() {
        assertThrows(IllegalArgumentException.class,()->Criteria.where("userId").inReadView("friends",1L,1L));
        assertThrows(IllegalArgumentException.class,()->Criteria.where("userId").inReadView("aloute_read_x; DROP TABLE users",1L,1L));
    }
}
