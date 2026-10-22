package com.ascs.shortener;

import com.ascs.Verifies;
import com.ascs.shortener.web.ContractController;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI document in shortener-api is the service's contract: clients (the orchestrator's tool gateway
 * included) are generated from it. This test fails the build when the controllers and the contract drift apart.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractTest {

    private static Map<String, Object> spec;

    @Autowired
    MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @BeforeAll
    static void loadContract() throws Exception {
        try (InputStream in = new ClassPathResource(ContractController.CONTRACT).getInputStream()) {
            spec = new Yaml().load(in);
        }
    }

    @Test
    @Verifies({"AC-10.1"})
    void theServiceServesItsContract() throws Exception {
        String served = mvc.perform(get("/api/v1/openapi.yaml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/yaml"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(new ClassPathResource(ContractController.CONTRACT).getContentAsString(StandardCharsets.UTF_8), served);
        assertTrue(served.startsWith("openapi: 3."));
    }

    @Test
    @Verifies({"AC-10.2"})
    void everyDocumentedOperationIsImplementedAndEveryEndpointIsDocumented() {
        assertEquals(documentedOperations(), implementedOperations());
    }

    @Test
    @Verifies({"AC-10.3"})
    void responseBodiesMatchTheDocumentedSchemas() throws Exception {
        String created = mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com/contract\",\"ttlSeconds\":60}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertConforms("CreateLinkResponse", JsonPath.read(created, "$"));
        String code = JsonPath.read(created, "$.code");
        mvc.perform(get("/" + code)).andExpect(status().isFound());

        String stats = mvc.perform(get("/api/v1/links/" + code + "/stats")).andReturn().getResponse().getContentAsString();
        assertConforms("LinkStats", JsonPath.read(stats, "$"));
        String audit = mvc.perform(get("/api/v1/links/" + code + "/audit")).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> events = JsonPath.read(audit, "$");
        events.forEach(e -> assertConforms("AuditEvent", e));
        assertTrue(events.size() >= 2, "create and redirect are audited");
    }

    /**
     * The fields sent are exactly the documented ones (nulls are serialized, so optional fields are present too),
     * and every required field has a value.
     */
    @SuppressWarnings("unchecked")
    private static void assertConforms(String schemaName, Map<String, Object> body) {
        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components"))
                .get("schemas")).get(schemaName);
        Set<String> documented = ((Map<String, Object>) schema.get("properties")).keySet();
        assertEquals(new TreeSet<>(documented), new TreeSet<>(body.keySet()), schemaName + ": documented vs. sent fields");
        ((List<String>) schema.get("required")).forEach(f -> assertTrue(body.get(f) != null, schemaName + "." + f + " is required"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> documentedOperations() {
        Set<String> ops = new TreeSet<>();
        ((Map<String, Map<String, Object>>) spec.get("paths")).forEach((path, operations) ->
                operations.keySet().forEach(method -> ops.add(method.toUpperCase(Locale.ROOT) + " " + path)));
        return ops;
    }

    /** Endpoints of this service's own controllers; path-variable regexes are dropped ({code:[0-9A-Za-z]+} -> {code}). */
    private Set<String> implementedOperations() {
        Set<String> ops = new TreeSet<>();
        mappings.getHandlerMethods().forEach((RequestMappingInfo info, org.springframework.web.method.HandlerMethod handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.ascs.shortener")) return;
            for (String pattern : info.getPatternValues()) {
                for (var method : info.getMethodsCondition().getMethods()) {
                    ops.add(method.name() + " " + pattern.replaceAll("\\{(\\w+):[^}]*}", "{$1}"));
                }
            }
        });
        return ops;
    }
}
