import {BASE_URL} from '../api/client';
export const projectIcons = ["projects/01-cube.svg", "projects/02-hexagon.svg", "projects/03-prism.svg", "projects/04-rings.svg", "projects/05-puzzle.svg", "projects/06-mountain.svg", "projects/07-leaf.svg", "projects/08-sun.svg", "projects/09-moon.svg", "projects/10-wave.svg", "projects/11-rocket.svg", "projects/12-planet.svg", "projects/13-compass.svg", "projects/14-telescope.svg", "projects/15-flag.svg", "projects/16-lighthouse.svg", "projects/17-bridge.svg", "projects/18-castle.svg", "projects/19-tent.svg", "projects/20-house.svg", "projects/21-key.svg", "projects/22-gear.svg", "projects/23-toolbox.svg", "projects/24-hourglass.svg", "projects/25-magnet.svg", "projects/26-spiral.svg", "projects/27-orbit.svg", "projects/28-blocks.svg", "projects/29-crystal.svg", "projects/30-paper-plane.svg"];

export const builtinIcon = index => `/assets/welcome/${projectIcons[index]}`;
export const fallbackIcon = id => builtinIcon(Array.from(String(id)).reduce((hash, char) => (hash * 31 + char.charCodeAt(0)) >>> 0, 0) % projectIcons.length);
export function projectIcon(project, icon = project.icon) {
    if (icon?.kind === 'builtin') return builtinIcon(icon.index);
    if (icon?.kind === 'upload') return icon.dataUrl;
    if (icon?.kind === 'github') return `${BASE_URL}/projects/${project.id}/icon`;
    return fallbackIcon(project.id);
}
export function githubOwner(project) {
    for (let url of [project.source?.url, project.git?.remoteUrl]) {
        if (!url) continue;
        if (/^[\w.-]+@[^/:]+:/.test(url)) url = `ssh://${url.replace(':', '/')}`;
        try {
            const parsed = new URL(url), parts = parsed.pathname.split('/').filter(Boolean);
            if (['github.com','www.github.com','ssh.github.com'].includes(parsed.hostname.toLowerCase()) && parts.length >= 2) return parts[0];
        } catch (_) { /* Not a supported repository URL. */ }
    }
    return null;
}
