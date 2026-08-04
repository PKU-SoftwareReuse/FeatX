package com.mycode.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContextFilterTest {
    @Test
    void requestHeadersBindTheWorkspaceAndRepository(@TempDir Path projectRoot) throws Exception {
        ProjectState project = ProjectState.loadRepository(901, projectRoot.toString(), "JAVA");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ProjectState.WORKSPACE_HEADER, "filter-workspace");
        request.addHeader(ProjectState.REPOSITORY_HEADER, "901");
        AtomicBoolean invoked = new AtomicBoolean();

        new ProjectContextFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    invoked.set(true);
                    assertEquals("filter-workspace", ProjectState.currentWorkspaceId());
                    assertSame(project, ProjectState.getInstance());
                }
        );

        assertTrue(invoked.get());
        try (ProjectState.Scope ignored = ProjectState.bindWorkspace("filter-workspace")) {
            assertSame(project, ProjectState.getInstance());
        }
    }

    @Test
    void eventSourceQueryParametersBindTheSameContext(@TempDir Path projectRoot) throws Exception {
        ProjectState project = ProjectState.loadRepository(902, projectRoot.toString(), "PYTHON");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("workspaceId", "filter-sse");
        request.setParameter("repoId", "902");

        new ProjectContextFilter().doFilter(
                request,
                new MockHttpServletResponse(),
                (servletRequest, servletResponse) -> {
                    assertEquals("filter-sse", ProjectState.currentWorkspaceId());
                    assertSame(project, ProjectState.getInstance());
                }
        );
    }
}
