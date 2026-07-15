package cn.edu.pku.lixutian.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitRemoteHelperTest {
    @Test
    void githubShorthandRemainsSupported() {
        String remote = GitRemoteHelper.normalize("Naccl/NBlog");

        assertEquals("https://github.com/Naccl/NBlog.git", remote);
        assertEquals("GitHub", GitRemoteHelper.provider(remote));
        assertEquals("Naccl/NBlog", GitRemoteHelper.displayName(remote));
    }

    @Test
    void gitLabHttpsRemoteKeepsNestedGroupPath() {
        String remote = GitRemoteHelper.normalize("https://gitlab.com/team/platform/project.git");

        assertEquals("https://gitlab.com/team/platform/project.git", remote);
        assertEquals("GitLab", GitRemoteHelper.provider(remote));
        assertEquals("team/platform/project", GitRemoteHelper.displayName(remote));
    }

    @Test
    void gitLinkSshRemoteIsRecognized() {
        String remote = GitRemoteHelper.normalize("git@gitlink.org.cn:owner/project.git");

        assertEquals("GitLink", GitRemoteHelper.provider(remote));
        assertEquals("owner/project", GitRemoteHelper.displayName(remote));
    }

    @Test
    void selfHostedGitRemoteUsesItsHostAsProvider() {
        String remote = GitRemoteHelper.normalize("ssh://git@git.example.edu/research/tool.git");

        assertEquals("git.example.edu", GitRemoteHelper.provider(remote));
        assertEquals("research/tool", GitRemoteHelper.displayName(remote));
    }

    @Test
    void unsupportedOrIncompleteRemoteIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GitRemoteHelper.normalize("javascript:alert(1)"));
        assertThrows(IllegalArgumentException.class, () -> GitRemoteHelper.normalize("https://gitlab.com"));
    }
}
