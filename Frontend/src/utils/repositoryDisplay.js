// Presentation only: clone URLs stay unchanged in project records and import requests.
export function repositoryDisplay(project) {
    if (project.source?.kind === 'upload' && !project.gitLink) return {platform: 'local', label: '本地项目', href: null};
    const raw = project.gitLink || project.githubLink || project.source?.url || project.git?.remoteUrl || '';
    if (!raw) return {platform: 'git', label: 'Git 仓库', href: null};
    const normalized = /^[\w.-]+@[^/:]+:.+$/.test(raw) ? `ssh://${raw.replace(':', '/')}` : raw;
    try {
        const parsed = new URL(normalized);
        const host = parsed.hostname.toLowerCase().replace(/\.$/, '');
        const providers = {
            'github.com': ['github', 'GitHub', 'github.com'],
            'www.github.com': ['github', 'GitHub', 'github.com'],
            'ssh.github.com': ['github', 'GitHub', 'github.com'],
            'gitlab.com': ['gitlab', 'GitLab', 'gitlab.com'],
            'www.gitlab.com': ['gitlab', 'GitLab', 'gitlab.com'],
            'gitlink.org.cn': ['gitlink', 'GitLink', 'www.gitlink.org.cn'],
            'www.gitlink.org.cn': ['gitlink', 'GitLink', 'www.gitlink.org.cn'],
            'gitee.com': ['git', 'Gitee', 'gitee.com'],
            'bitbucket.org': ['git', 'Bitbucket', 'bitbucket.org'],
        };
        const [platform, platformName, webHost] = providers[host] || ['git', host, null];
        const path = parsed.pathname.replace(/^\/+|\/+$/g, '').replace(/\.git$/, '');
        const isWeb = ['http:', 'https:'].includes(parsed.protocol);
        // HTTP remains the exact chosen page. SSH uses a known platform web address only.
        const href = isWeb ? raw : parsed.protocol === 'ssh:' && webHost ? `https://${webHost}/${path}` : null;
        return {platform, label: `${platformName} · ${path || '仓库'}`, href};
    } catch (_) {
        return {platform: 'git', label: 'Git 仓库', href: null};
    }
}
