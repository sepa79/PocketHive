package io.pockethive.swarm.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolvedSutEnvironmentTest {

    @Test
    void trimsAndCopiesEndpointData() {
        Map<String, ResolvedSutEndpoint> endpoints = new LinkedHashMap<>();
        endpoints.put(
            "payments",
            new ResolvedSutEndpoint(
                " payments ",
                " HTTPS ",
                " https://api.testenv.company.com ",
                " api.testenv.company.com:443 ",
                " internal-payments-alb.aws.local:8443 "));

        ResolvedSutEnvironment environment = new ResolvedSutEnvironment(
            " reltest-payments ",
            " Payments Reltest ",
            " sandbox ",
            endpoints);

        endpoints.clear();

        assertEquals("reltest-payments", environment.sutId());
        assertEquals("Payments Reltest", environment.name());
        assertEquals("sandbox", environment.type());
        assertEquals(1, environment.endpoints().size());
        ResolvedSutEndpoint endpoint = environment.endpoints().get("payments");
        assertEquals("payments", endpoint.endpointId());
        assertEquals("HTTPS", endpoint.kind());
        assertEquals("https://api.testenv.company.com", endpoint.clientBaseUrl());
        assertEquals("api.testenv.company.com:443", endpoint.clientAuthority());
        assertEquals("internal-payments-alb.aws.local:8443", endpoint.upstreamAuthority());
    }

    @Test
    void rejectsBlankAuthorityFields() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new ResolvedSutEndpoint("payments", "HTTPS", "https://api.testenv.company.com", "", "upstream:443"));
    }
}
