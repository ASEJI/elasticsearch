/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.get.MultiGetResponse;
import org.elasticsearch.action.percolate.PercolateResponse;
import org.elasticsearch.action.percolate.PercolateSourceBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.termvectors.MultiTermVectorsResponse;
import org.elasticsearch.action.termvectors.TermVectorsRequest;
import org.elasticsearch.action.termvectors.TermVectorsResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.children.Children;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.test.ShieldIntegTestCase;

import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.*;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 */
public class DocumentLevelSecurityTests extends ShieldIntegTestCase {

    protected static final SecuredString USERS_PASSWD = new SecuredString("change_me".toCharArray());
    protected static final String USERS_PASSWD_HASHED = new String(Hasher.BCRYPT.hash(USERS_PASSWD));

    @Override
    protected String configUsers() {
        return super.configUsers() +
                "user1:" + USERS_PASSWD_HASHED + "\n" +
                "user2:" + USERS_PASSWD_HASHED + "\n" ;
    }

    @Override
    protected String configUsersRoles() {
        return super.configUsersRoles() +
                "role1:user1\n" +
                "role2:user2\n";
    }
    @Override
    protected String configRoles() {
        return super.configRoles() +
                "\nrole1:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      query: \n" +
                "        term: \n" +
                "          field1: value1\n" +
                "role2:\n" +
                "  cluster: all\n" +
                "  indices:\n" +
                "    '*':\n" +
                "      privileges: ALL\n" +
                "      query: '{\"term\" : {\"field2\" : \"value2\"}}'"; // <-- query defined as json in a string
    }

    public void testSimpleQuery() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        SearchResponse response = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .setQuery(randomBoolean() ? QueryBuilders.termQuery("field1", "value1") : QueryBuilders.matchAllQuery())
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "1");
        response = client().prepareSearch("test")
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .setQuery(randomBoolean() ? QueryBuilders.termQuery("field2", "value2") : QueryBuilders.matchAllQuery())
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "2");
    }

    public void testGetApi() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );

        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .get();

        Boolean realtime = randomFrom(true, false, null);
        GetResponse response = client().prepareGet("test", "type1", "1")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(true));
        assertThat(response.getId(), equalTo("1"));
        response = client().prepareGet("test", "type1", "2")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(true));
        assertThat(response.getId(), equalTo("2"));

        response = client().prepareGet("test", "type1", "1")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(false));
        response = client().prepareGet("test", "type1", "2")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(false));
    }

    public void testMGetApi() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );

        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .get();

        Boolean realtime = randomFrom(true, false, null);
        MultiGetResponse response = client().prepareMultiGet()
                .add("test", "type1", "1")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getResponses()[0].isFailed(), is(false));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(true));
        assertThat(response.getResponses()[0].getResponse().getId(), equalTo("1"));

        response = client().prepareMultiGet()
                .add("test", "type1", "2")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.getResponses()[0].isFailed(), is(false));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(true));
        assertThat(response.getResponses()[0].getResponse().getId(), equalTo("2"));

        response = client().prepareMultiGet()
                .add("test", "type1", "1")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.getResponses()[0].isFailed(), is(false));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(false));

        response = client().prepareMultiGet()
                .add("test", "type1", "2")
                .setRealtime(realtime)
                .setRefresh(true)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getResponses()[0].isFailed(), is(false));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(false));
    }

    public void testTVApi() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string,term_vector=with_positions_offsets_payloads", "field2", "type=string,term_vector=with_positions_offsets_payloads")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        Boolean realtime = randomFrom(true, false, null);
        TermVectorsResponse response = client().prepareTermVectors("test", "type1", "1")
                .setRealtime(realtime)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(true));
        assertThat(response.getId(), is("1"));

        response = client().prepareTermVectors("test", "type1", "2")
                .setRealtime(realtime)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(true));
        assertThat(response.getId(), is("2"));

        response = client().prepareTermVectors("test", "type1", "1")
                .setRealtime(realtime)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(false));

        response = client().prepareTermVectors("test", "type1", "2")
                .setRealtime(realtime)
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.isExists(), is(false));
    }

    public void testMTVApi() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string,term_vector=with_positions_offsets_payloads", "field2", "type=string,term_vector=with_positions_offsets_payloads")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        Boolean realtime = randomFrom(true, false, null);
        MultiTermVectorsResponse response = client().prepareMultiTermVectors()
                .add(new TermVectorsRequest("test", "type1", "1").realtime(realtime))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getResponses().length, equalTo(1));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(true));
        assertThat(response.getResponses()[0].getResponse().getId(), is("1"));

        response = client().prepareMultiTermVectors()
                .add(new TermVectorsRequest("test", "type1", "2").realtime(realtime))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.getResponses().length, equalTo(1));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(true));
        assertThat(response.getResponses()[0].getResponse().getId(), is("2"));

        response = client().prepareMultiTermVectors()
                .add(new TermVectorsRequest("test", "type1", "1").realtime(realtime))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.getResponses().length, equalTo(1));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(false));

        response = client().prepareMultiTermVectors()
                .add(new TermVectorsRequest("test", "type1", "2").realtime(realtime))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getResponses().length, equalTo(1));
        assertThat(response.getResponses()[0].getResponse().isExists(), is(false));
    }

    public void testGlobalAggregation() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type1", "2").setSource("field2", "value2")
                .setRefresh(true)
                .get();

        SearchResponse response = client().prepareSearch("test")
                .addAggregation(AggregationBuilders.global("global").subAggregation(AggregationBuilders.terms("field2").field("field2")))
                .get();
        assertHitCount(response, 2);
        assertSearchHits(response, "1", "2");

        Global globalAgg = response.getAggregations().get("global");
        assertThat(globalAgg.getDocCount(), equalTo(2l));
        Terms termsAgg = globalAgg.getAggregations().get("field2");
        assertThat(termsAgg.getBuckets().get(0).getKeyAsString(), equalTo("value2"));
        assertThat(termsAgg.getBuckets().get(0).getDocCount(), equalTo(1l));

        response = client().prepareSearch("test")
                .addAggregation(AggregationBuilders.global("global").subAggregation(AggregationBuilders.terms("field2").field("field2")))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "1");

        globalAgg = response.getAggregations().get("global");
        assertThat(globalAgg.getDocCount(), equalTo(1l));
        termsAgg = globalAgg.getAggregations().get("field2");
        assertThat(termsAgg.getBuckets().size(), equalTo(0));
    }

    public void testChildrenAggregation() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping("type1", "field1", "type=string", "field2", "type=string")
                        .addMapping("type2", "_parent", "type=type1", "field3", "type=string")
        );
        client().prepareIndex("test", "type1", "1").setSource("field1", "value1")
                .setRefresh(true)
                .get();
        client().prepareIndex("test", "type2", "2").setSource("field3", "value3")
                .setParent("1")
                .setRefresh(true)
                .get();

        SearchResponse response = client().prepareSearch("test")
                .setTypes("type1")
                .addAggregation(AggregationBuilders.children("children").childType("type2")
                        .subAggregation(AggregationBuilders.terms("field3").field("field3")))
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "1");

        Children children = response.getAggregations().get("children");
        assertThat(children.getDocCount(), equalTo(1l));
        Terms termsAgg = children.getAggregations().get("field3");
        assertThat(termsAgg.getBuckets().get(0).getKeyAsString(), equalTo("value3"));
        assertThat(termsAgg.getBuckets().get(0).getDocCount(), equalTo(1l));

        response = client().prepareSearch("test")
                .setTypes("type1")
                .addAggregation(AggregationBuilders.children("children").childType("type2")
                        .subAggregation(AggregationBuilders.terms("field3").field("field3")))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertHitCount(response, 1);
        assertSearchHits(response, "1");

        children = response.getAggregations().get("children");
        assertThat(children.getDocCount(), equalTo(0l));
        termsAgg = children.getAggregations().get("field3");
        assertThat(termsAgg.getBuckets().size(), equalTo(0));
    }

    public void testParentChild() {
        assertAcked(prepareCreate("test")
                .addMapping("parent")
                .addMapping("child", "_parent", "type=parent", "field1", "type=string", "field2", "type=string"));
        ensureGreen();

        // index simple data
        client().prepareIndex("test", "parent", "p1").setSource("field1", "value1").get();
        client().prepareIndex("test", "child", "c1").setSource("field2", "value2").setParent("p1").get();
        client().prepareIndex("test", "child", "c2").setSource("field2", "value2").setParent("p1").get();
        refresh();

        SearchResponse searchResponse = client().prepareSearch("test")
                .setQuery(hasChildQuery("child", matchAllQuery()))
                .get();
        assertHitCount(searchResponse, 1l);
        assertThat(searchResponse.getHits().totalHits(), equalTo(1l));
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("p1"));

        searchResponse = client().prepareSearch("test")
                .setQuery(hasParentQuery("parent", matchAllQuery()))
                .addSort("_id", SortOrder.ASC)
                .get();
        assertHitCount(searchResponse, 2l);
        assertThat(searchResponse.getHits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.getHits().getAt(1).id(), equalTo("c2"));

        // Both user1 and user2 can't see field1 and field2, no parent/child query should yield results:
        searchResponse = client().prepareSearch("test")
                .setQuery(hasChildQuery("child", matchAllQuery()))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertHitCount(searchResponse, 0l);

        searchResponse = client().prepareSearch("test")
                .setQuery(hasChildQuery("child", matchAllQuery()))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertHitCount(searchResponse, 0l);

        searchResponse = client().prepareSearch("test")
                .setQuery(hasParentQuery("parent", matchAllQuery()))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertHitCount(searchResponse, 0l);

        searchResponse = client().prepareSearch("test")
                .setQuery(hasParentQuery("parent", matchAllQuery()))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertHitCount(searchResponse, 0l);
    }

    public void testPercolateApi() {
        assertAcked(client().admin().indices().prepareCreate("test")
                        .addMapping(".percolator", "field1", "type=string", "field2", "type=string")
        );
        client().prepareIndex("test", ".percolator", "1")
                .setSource("{\"query\" : { \"match_all\" : {} }, \"field1\" : \"value1\"}")
                .setRefresh(true)
                .get();

        // Percolator without a query just evaluates all percolator queries that are loaded, so we have a match:
        PercolateResponse response = client().preparePercolate()
                .setDocumentType("type")
                .setPercolateDoc(new PercolateSourceBuilder.DocBuilder().setDoc("{}"))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getCount(), equalTo(1l));
        assertThat(response.getMatches()[0].getId().string(), equalTo("1"));

        // Percolator with a query on a document that the current user can see. Percolator will have one query to evaluate, so there is a match:
        response = client().preparePercolate()
                .setDocumentType("type")
                .setPercolateQuery(termQuery("field1", "value1"))
                .setPercolateDoc(new PercolateSourceBuilder.DocBuilder().setDoc("{}"))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getCount(), equalTo(1l));
        assertThat(response.getMatches()[0].getId().string(), equalTo("1"));

        // Percolator with a query on a document that the current user can't see. Percolator will not have queries to evaluate, so there is no match:
        response = client().preparePercolate()
                .setDocumentType("type")
                .setPercolateQuery(termQuery("field1", "value1"))
                .setPercolateDoc(new PercolateSourceBuilder.DocBuilder().setDoc("{}"))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user2", USERS_PASSWD))
                .get();
        assertThat(response.getCount(), equalTo(0l));

        assertAcked(client().admin().indices().prepareClose("test"));
        assertAcked(client().admin().indices().prepareOpen("test"));
        ensureGreen("test");

        // Ensure that the query loading that happens at startup has permissions to load the percolator queries:
        response = client().preparePercolate()
                .setDocumentType("type")
                .setPercolateDoc(new PercolateSourceBuilder.DocBuilder().setDoc("{}"))
                .putHeader(BASIC_AUTH_HEADER, basicAuthHeaderValue("user1", USERS_PASSWD))
                .get();
        assertThat(response.getCount(), equalTo(1l));
        assertThat(response.getMatches()[0].getId().string(), equalTo("1"));
    }

}
