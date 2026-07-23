package cn.edu.pku.lixutian.service.code;

import cn.edu.pku.lixutian.service.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModifyAgentServiceTest {
    private static final String ORIGINAL = """
            package cn.edu.pku;

            import java.util.List;

            public class Foo {
                private int value = 1;
                private List<String> names;
            }
            """;

    private final ModifyAgentService service = new ModifyAgentService();

    @Test
    void appliesMinimalSearchReplaceAndPreservesUnrelatedContent() {
        String result = service.applyAgent3Result("""
                <<<<<<< SEARCH
                private int value = 1;
                =======
                private int value = 2;
                >>>>>>> REPLACE
                """, "cn/edu/pku/Foo.java", ORIGINAL, false);

        assertTrue(result.contains("private int value = 2;"));
        assertTrue(result.contains("import java.util.List;"));
        assertTrue(result.contains("private List<String> names;"));
    }

    @Test
    void appliesMultipleBlocksInOrder() {
        String result = service.applyAgent3Result("""
                <<<<<<< SEARCH
                private int value = 1;
                =======
                private int value = 2;
                >>>>>>> REPLACE

                <<<<<<< SEARCH
                private int value = 2;
                =======
                private long value = 2L;
                >>>>>>> REPLACE
                """, "cn/edu/pku/Foo.java", ORIGINAL, false);

        assertTrue(result.contains("private long value = 2L;"));
    }

    @Test
    void rejectsSearchTextThatDoesNotMatch() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        private int missing = 1;
                        =======
                        private int missing = 2;
                        >>>>>>> REPLACE
                        """, "cn/edu/pku/Foo.java", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("did not match"));
    }

    @Test
    void rejectsSearchTextThatIsNotUnique() {
        String original = """
                class Foo {
                    int value;
                    int value;
                }
                """;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        int value;
                        =======
                        long value;
                        >>>>>>> REPLACE
                        """, "cn/edu/pku/Foo.java", original, false)
        );

        assertTrue(error.getMessage().contains("more than once"));
    }

    @Test
    void rejectsSearchReplaceBlocksThatProduceNoChange() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        private int value = 1;
                        =======
                        private int value = 1;
                        >>>>>>> REPLACE
                        """, "cn/edu/pku/Foo.java", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("produced no changes"));
    }

    @Test
    void acceptsTheExplicitNoChangesProtocolWithoutInventingADiff() {
        String result = service.applyAgent3Result(
                "NO_CHANGES_REQUIRED",
                "cn/edu/pku/Foo.java",
                ORIGINAL,
                false
        );

        assertEquals(ORIGINAL, result);
    }

    @Test
    void rejectsAReplacementThatBreaksJavaSyntax() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        private int value = 1;
                        =======
                        private int value = 1
                        >>>>>>> REPLACE
                        """, "cn/edu/pku/Foo.java", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("invalid"));
    }

    @Test
    void rejectsReplacingTheCompleteExistingFile() {
        String protocol = SearchReplacePatch.SEARCH_START + "\n"
                + ORIGINAL + "\n"
                + SearchReplacePatch.SEPARATOR + "\n"
                + "class Foo {}\n"
                + SearchReplacePatch.REPLACE_END;

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result(protocol, "cn/edu/pku/Foo.java", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("complete existing file"));
    }

    @Test
    void createsANewFileOnlyThroughCreateMode() {
        String result = service.applyAgent3Result("""
                <<<<<<< CREATE
                package cn.edu.pku;

                public class Foo {
                }
                >>>>>>> CREATE
                """, "cn/edu/pku/Foo.java", "", true);

        assertTrue(result.contains("package cn.edu.pku;"));
        assertTrue(result.contains("public class Foo"));
    }

    @Test
    void appliesSearchReplaceToANonJavaProjectFile() {
        String result = service.applyAgent3Result("""
                <<<<<<< SEARCH
                feature: disabled
                =======
                feature: enabled
                >>>>>>> REPLACE
                """, "application.yml", "feature: disabled\n", false);

        assertTrue(result.contains("feature: enabled"));
    }

    @Test
    void rejectsATypeThatDoesNotMatchTheTargetPath() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< CREATE
                        package cn.edu.pku;
                        public class Bar {}
                        >>>>>>> CREATE
                        """, "cn/edu/pku/Foo.java", "", true)
        );
    }

    @Test
    void retriesGenerationSearchAndSyntaxFailuresBeforeSucceeding() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        AgentEventSink emitter = mock(AgentEventSink.class);
        service.llmClient = llmClient;
        when(llmClient.streamGenerateWithPrompt(anyString(), same(emitter), eq("model")))
                .thenThrow(new IOException("temporary provider failure"))
                .thenReturn("""
                        <<<<<<< SEARCH
                        missing text
                        =======
                        replacement
                        >>>>>>> REPLACE
                        """)
                .thenReturn("""
                        <<<<<<< SEARCH
                        private int value = 1;
                        =======
                        private int value = 1
                        >>>>>>> REPLACE
                        """)
                .thenReturn("""
                        <<<<<<< SEARCH
                        private int value = 1;
                        =======
                        private int value = 2;
                        >>>>>>> REPLACE
                        """);

        String result = service.requestAndApplyAgent3(
                "prompt",
                emitter,
                "model",
                "cn/edu/pku/Foo.java",
                ORIGINAL,
                false,
                AgentLanguage.EN
        );

        assertTrue(result.contains("private int value = 2;"));
        verify(llmClient, times(4)).streamGenerateWithPrompt(anyString(), same(emitter), eq("model"));
    }

    @Test
    void stopsAfterFiveRetries() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        AgentEventSink emitter = mock(AgentEventSink.class);
        service.llmClient = llmClient;
        when(llmClient.streamGenerateWithPrompt(anyString(), same(emitter), eq("model")))
                .thenThrow(new IOException("provider unavailable"));

        IOException error = assertThrows(
                IOException.class,
                () -> service.requestAndApplyAgent3(
                        "prompt",
                        emitter,
                        "model",
                        "cn/edu/pku/Foo.java",
                        ORIGINAL,
                        false,
                        AgentLanguage.EN
                )
        );

        assertTrue(error.getMessage().contains("after 6 attempts"));
        verify(llmClient, times(6)).streamGenerateWithPrompt(anyString(), same(emitter), eq("model"));
    }
}
