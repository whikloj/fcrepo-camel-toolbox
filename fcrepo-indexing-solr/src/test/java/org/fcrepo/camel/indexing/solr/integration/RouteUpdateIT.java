/**
 * Copyright 2015 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.camel.indexing.solr.integration;

import static com.jayway.awaitility.Awaitility.await;
import static org.fcrepo.camel.RdfNamespaces.REPOSITORY;
import static org.hamcrest.Matchers.equalTo;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.Produce;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWithRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.blueprint.CamelBlueprintTestSupport;
import org.apache.camel.util.ObjectHelper;
import org.fcrepo.camel.JmsHeaders;
import org.fcrepo.camel.FcrepoClient;
import org.fcrepo.camel.FcrepoResponse;

import org.junit.Test;

/**
 * Test the route workflow.
 *
 * @author Aaron Coburn
 * @since 2015-04-10
 */
public class RouteUpdateIT extends CamelBlueprintTestSupport {

    @EndpointInject(uri = "mock:result")
    protected MockEndpoint resultEndpoint;

    @Produce(uri = "direct:start")
    protected ProducerTemplate template;

    private String fullPath = "";

    @Override
    protected void doPreSetup() throws Exception {
        final String webPort = System.getProperty("fcrepo.dynamic.test.port", "8080");
        final FcrepoClient client = new FcrepoClient(null, null, null, true);
        final FcrepoResponse res = client.post(
                URI.create("http://localhost:" + webPort + "/fcrepo/rest"),
                ObjectHelper.loadResourceAsStream("indexable.ttl"), "text/turtle");
        fullPath = res.getLocation().toString();
        TestUtils.httpPost("http://localhost:" + webPort + "/solr/testCore/update?commit=true",
                "<delete><query>*:*</query></delete>", "application/xml");
    }

    @Override
    public boolean isUseAdviceWith() {
        return true;
    }

    @Override
    public boolean isUseRouteBuilder() {
        return false;
    }

    @Override
    protected String getBlueprintDescriptor() {
        return "/OSGI-INF/blueprint/blueprint.xml";
    }

    @Override
    protected Properties useOverridePropertiesWithPropertiesComponent() {
        final String webPort = System.getProperty("fcrepo.dynamic.test.port", "8080");
        final String jmsPort = System.getProperty("fcrepo.dynamic.jms.port", "61616");

        final Properties props = new Properties();
        props.put("indexing.predicate", "true");
        props.put("fcrepo.baseUrl", "localhost:" + webPort + "/fcrepo/rest");
        props.put("solr.baseUrl", "localhost:" + webPort + "/solr/testCore");
        props.put("solr.commitWithin", "100");
        props.put("input.stream", "direct:start");
        return props;
    }

    @Test
    public void testAddedEventRouter() throws Exception {
        final String webPort = System.getProperty("fcrepo.dynamic.test.port", "8080");
        final String jmsPort = System.getProperty("fcrepo.dynamic.jms.port", "61616");
        final String path = fullPath.replaceFirst("http://localhost:[0-9]+/fcrepo/rest", "");
        final String solrEndpoint = "mock:http4:localhost:" + webPort + "/solr/testCore/update";
        final String fcrepoEndpoint = "mock:fcrepo:localhost:" + webPort + "/fcrepo/rest";
        final String url = "http://localhost:" + webPort + "/solr/testCore/select?q=*&wt=json";

        context.getRouteDefinition("FcrepoSolrRouter").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                mockEndpoints("*");
            }
        });
        context.getRouteDefinition("FcrepoSolrIndexer").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                mockEndpoints("*");
            }
        });
        context.getRouteDefinition("FcrepoSolrUpdater").adviceWith(context, new AdviceWithRouteBuilder() {
            @Override
            public void configure() throws Exception {
                mockEndpoints("*");
            }
        });
        context.start();

        final Map<String, Object> headers = new HashMap<>();
        headers.put(JmsHeaders.IDENTIFIER, path);
        headers.put(JmsHeaders.BASE_URL, "http://localhost:" + webPort + "/fcrepo/rest");
        headers.put(JmsHeaders.EVENT_TYPE, REPOSITORY + "NODE_ADDED");
        headers.put(JmsHeaders.TIMESTAMP, 1428360320168L);
        headers.put(JmsHeaders.PROPERTIES, "");

        getMockEndpoint(solrEndpoint).expectedMessageCount(1);
        getMockEndpoint(solrEndpoint).expectedHeaderReceived(Exchange.HTTP_RESPONSE_CODE, 200);
        getMockEndpoint("mock://direct:delete.solr").expectedMessageCount(0);
        getMockEndpoint("mock://direct:update.solr").expectedMessageCount(1);
        getMockEndpoint(fcrepoEndpoint).expectedMessageCount(2);

        template.sendBodyAndHeaders("direct:start", "", headers);

        assertMockEndpointsSatisfied();

        await().until(TestUtils.solrCount(url), equalTo(1));
    }
}
