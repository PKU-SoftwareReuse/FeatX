package com.mycode.service.code;

import com.mycode.service.llm.LlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PythonModifyAgentServiceTest {
    private static final String ORIGINAL = """
            import os

            def greet(name):
                return f"Hello, {name}"

            def untouched():
                return os.getcwd()
            """;

    private final PythonModifyAgentService service = new PythonModifyAgentService();

    @Test
    void appliesMinimalSearchReplaceAndPreservesUnrelatedPythonCode() {
        String result = service.applyAgent3Result("""
                <<<<<<< SEARCH
                def greet(name):
                    return f"Hello, {name}"
                =======
                def greet(name):
                    print("hello world")
                    return f"Hello, {name}"
                >>>>>>> REPLACE
                """, "package/module.py", ORIGINAL, false);

        assertTrue(result.contains("print(\"hello world\")"));
        assertTrue(result.contains("def untouched():"));
        assertTrue(result.contains("import os"));
    }

    @Test
    void rejectsPythonSearchReplaceBlocksThatProduceNoChange() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        def greet(name):
                            return f"Hello, {name}"
                        =======
                        def greet(name):
                            return f"Hello, {name}"
                        >>>>>>> REPLACE
                        """, "package/module.py", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("produced no changes"));
    }

    @Test
    void rejectsPythonSearchReplaceBlocksThatBreakSyntax() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> service.applyAgent3Result("""
                        <<<<<<< SEARCH
                        def greet(name):
                            return f"Hello, {name}"
                        =======
                        def greet(name)
                            return f"Hello, {name}"
                        >>>>>>> REPLACE
                        """, "package/module.py", ORIGINAL, false)
        );

        assertTrue(error.getMessage().contains("syntax is invalid"));
    }

    @Test
    void createsANewPythonFileThroughCreateMode() {
        String result = service.applyAgent3Result("""
                <<<<<<< CREATE
                def main():
                    print("hello world")
                >>>>>>> CREATE
                """, "package/new_module.py", "", true);

        assertTrue(result.contains("def main():"));
    }

    @Test
    void retriesANoChangePythonPatchBeforePublishingTheCandidate() throws Exception {
        LlmClient llmClient = mock(LlmClient.class);
        AgentEventSink eventSink = mock(AgentEventSink.class);
        service.llmClient = llmClient;
        when(llmClient.streamGenerateWithPrompt(anyString(), same(eventSink), eq("model")))
                .thenReturn("""
                        <<<<<<< SEARCH
                        def greet(name):
                            return f"Hello, {name}"
                        =======
                        def greet(name):
                            return f"Hello, {name}"
                        >>>>>>> REPLACE
                        """)
                .thenReturn("""
                        <<<<<<< SEARCH
                        def greet(name):
                            return f"Hello, {name}"
                        =======
                        def greet(name):
                            print("hello world")
                            return f"Hello, {name}"
                        >>>>>>> REPLACE
                        """);

        String result = service.requestAndApplyAgent3(
                "prompt",
                eventSink,
                "model",
                "package/module.py",
                ORIGINAL,
                false,
                AgentLanguage.CN
        );

        assertTrue(result.contains("print(\"hello world\")"));
        verify(llmClient, times(2)).streamGenerateWithPrompt(anyString(), same(eventSink), eq("model"));
    }
}
