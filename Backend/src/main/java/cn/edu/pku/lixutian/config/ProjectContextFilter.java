package cn.edu.pku.lixutian.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Binds a browser-tab workspace to its selected project for one HTTP request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ProjectContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String workspaceId = firstText(
                request.getHeader(ProjectState.WORKSPACE_HEADER),
                request.getParameter("workspaceId")
        );
        if (workspaceId == null) {
            filterChain.doFilter(request, response);
            return;
        }

        Integer repositoryHint = parseInteger(firstText(
                request.getHeader(ProjectState.REPOSITORY_HEADER),
                request.getParameter("repoId")
        ));
        try (ProjectState.Scope ignored = ProjectState.bindRequest(workspaceId, repositoryHint)) {
            filterChain.doFilter(request, response);
        }
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private Integer parseInteger(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
