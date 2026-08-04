package com.mycode.dto.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureResultTest {
    @Test
    void serializesCandidateMethodsWithSemanticFieldNames() {
        FeatureResult.CandidateMethod candidate =
                new FeatureResult.CandidateMethod("demo.Service.run()");
        candidate.addPythonCandidateMethod("demo.Service.run()");

        JsonNode json = new ObjectMapper().valueToTree(candidate);

        assertEquals("demo.Service.run()", json.get("shortSignature").asText());
        assertTrue(json.has("fullCandidateMethods"));
        assertEquals(
                "demo.Service.run()",
                json.get("fullCandidateMethods").get(0).get("fullSignature").asText()
        );
    }

    @Test
    void matchesArrayQualifiedGenericAndVarargsSignatures() {
        assertEquals(
                List.of("top.naccl.BlogApiApplication.main(String[])"),
                FeatureResult.matchingFullSignatures(
                        "top.naccl.BlogApiApplication.main( String[] args )",
                        List.of("top.naccl.BlogApiApplication.main(String[])")
                )
        );
        assertEquals(
                List.of("demo.Service.save(top.naccl.model.dto.Blog)"),
                FeatureResult.matchingFullSignatures(
                        "demo.Service.save( top.naccl.model.dto.Blog blog )",
                        List.of("demo.Service.save(top.naccl.model.dto.Blog)")
                )
        );
        assertEquals(
                List.of("demo.Service.accept(java.util.List<String[]>)"),
                FeatureResult.matchingFullSignatures(
                        "demo.Service.accept( java.util.List<String[]> values )",
                        List.of("demo.Service.accept(java.util.List<String[]>)")
                )
        );
        assertEquals(
                List.of("demo.Service.join(String...)"),
                FeatureResult.matchingFullSignatures(
                        "demo.Service.join( String... values )",
                        List.of("demo.Service.join(String...)")
                )
        );
    }

    @Test
    void matchesConstructorsWrittenAsInit() {
        assertEquals(
                List.of("top.naccl.entity.VisitLog.VisitLog(String, Integer)"),
                FeatureResult.matchingFullSignatures(
                        "top.naccl.entity.VisitLog.<init>( String uuid, Integer times )",
                        List.of("top.naccl.entity.VisitLog.VisitLog(String, Integer)")
                )
        );
    }

    @Test
    void supportsUnambiguousLegacyArraySignaturesWithoutChoosingWrongOverload() {
        assertEquals(
                List.of("demo.Application.main(String[])"),
                FeatureResult.matchingFullSignatures(
                        "demo.Application.main( String args )",
                        List.of("demo.Application.main(String[])")
                )
        );
        assertEquals(
                List.of("demo.Application.main(String)"),
                FeatureResult.matchingFullSignatures(
                        "demo.Application.main( String args )",
                        List.of("demo.Application.main(String)", "demo.Application.main(String[])")
                )
        );
        assertTrue(FeatureResult.matchingFullSignatures(
                "demo.Application.main( String args )",
                List.of("demo.Application.main(String[])", "demo.Application.main(String[][])")
        ).isEmpty());
    }
}
