package cn.edu.pku.lixutian.helper;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GitRemoteHelper {
    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "ssh", "git");
    private static final Pattern SCP_REMOTE = Pattern.compile("^[^@\\s]+@([^:\\s]+):(.+)$");
    private static final Pattern GITHUB_SHORTHAND = Pattern.compile(
            "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?$"
    );

    private GitRemoteHelper() {
    }

    public static String normalize(String remote) {
        if (remote == null || remote.isBlank()) {
            throw new IllegalArgumentException("Git repository URL is required.");
        }

        String value = remote.trim();
        if (SCP_REMOTE.matcher(value).matches()) {
            return value;
        }
        if (GITHUB_SHORTHAND.matcher(value).matches()) {
            return "https://github.com/" + stripGitSuffix(value) + ".git";
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null
                    ? ""
                    : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!URL_SCHEMES.contains(scheme) || uri.getHost() == null || uri.getHost().isBlank()) {
                throw invalidRemote();
            }
            if (uri.getPath() == null || uri.getPath().isBlank() || uri.getPath().equals("/")) {
                throw invalidRemote();
            }
            return value;
        } catch (URISyntaxException e) {
            throw invalidRemote();
        }
    }

    public static String provider(String remote) {
        String host = extractHost(remote);
        if (host == null || host.isBlank()) {
            return "Git";
        }

        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("github.com")) return "GitHub";
        if (normalizedHost.equals("gitlab.com") || normalizedHost.contains("gitlab")) return "GitLab";
        if (normalizedHost.contains("gitlink")) return "GitLink";
        if (normalizedHost.equals("gitee.com")) return "Gitee";
        if (normalizedHost.equals("bitbucket.org")) return "Bitbucket";
        return host;
    }

    public static String displayName(String remote) {
        if (remote == null || remote.isBlank()) {
            return "";
        }

        String value = remote.trim();
        Matcher scpMatcher = SCP_REMOTE.matcher(value);
        if (scpMatcher.matches()) {
            return cleanPath(scpMatcher.group(2));
        }
        if (GITHUB_SHORTHAND.matcher(value).matches()) {
            return stripGitSuffix(value);
        }

        try {
            URI uri = new URI(value);
            return cleanPath(uri.getPath());
        } catch (URISyntaxException e) {
            return value;
        }
    }

    private static String extractHost(String remote) {
        if (remote == null) return null;

        Matcher scpMatcher = SCP_REMOTE.matcher(remote.trim());
        if (scpMatcher.matches()) {
            return scpMatcher.group(1);
        }
        if (GITHUB_SHORTHAND.matcher(remote.trim()).matches()) {
            return "github.com";
        }

        try {
            return new URI(remote.trim()).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String cleanPath(String path) {
        if (path == null) return "";
        String value = path.replace('\\', '/').replaceAll("^/+|/+$", "");
        return stripGitSuffix(value);
    }

    private static String stripGitSuffix(String value) {
        return value.replaceFirst("(?i)\\.git$", "");
    }

    private static IllegalArgumentException invalidRemote() {
        return new IllegalArgumentException(
                "Use a GitHub owner/repository shorthand or a valid HTTP(S), SSH, or Git repository URL."
        );
    }
}
