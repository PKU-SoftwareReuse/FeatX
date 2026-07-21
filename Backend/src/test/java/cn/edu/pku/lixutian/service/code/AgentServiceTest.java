package cn.edu.pku.lixutian.service.code;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServiceTest {
    private final AgentService service = new AgentService();

    @Test
    void zeroLimitPreservesCompleteContext() {
        String context = "complete-current-feature-codemap";

        assertEquals(context, service.boundedPromptSection(context, 0));
    }

    @Test
    void positiveLimitStillBoundsOptionalContext() {
        String bounded = service.boundedPromptSection("abcdefghijklmnopqrstuvwxyz", 12);

        assertTrue(bounded.contains("context truncated by FeatX"));
        assertThrows(IllegalArgumentException.class, () -> service.boundedPromptSection("context", -1));
    }
}
