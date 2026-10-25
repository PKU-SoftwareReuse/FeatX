// Decorative, stable per path; the icon does not imply a file type.
export function iconForFile(path = '') {
    const hash = Array.from(path).reduce((value, char) => Math.imul(value ^ char.charCodeAt(0), 16777619) >>> 0, 2166136261);
    return `/assets/workspace/files/file-${hash % 8 + 1}.svg`;
}
