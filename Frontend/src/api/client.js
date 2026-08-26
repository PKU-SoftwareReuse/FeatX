import axios from "axios";

export const BASE_URL = process.env.REACT_APP_API_BASE_URL || "http://127.0.0.1:8080";

const WORKSPACE_ID_KEY = "featx.workspaceId";
const REPOSITORY_ID_KEY = "featx.repositoryId";

const readSessionValue = (key) => {
    try {
        return window.sessionStorage.getItem(key);
    } catch (error) {
        return null;
    }
};

const writeSessionValue = (key, value) => {
    try {
        if (value == null || value === "") {
            window.sessionStorage.removeItem(key);
        } else {
            window.sessionStorage.setItem(key, String(value));
        }
    } catch (error) {
        // Requests can still use the current backend workspace when storage is unavailable.
    }
};

const createWorkspaceId = () => {
    if (typeof window.crypto?.randomUUID === "function") {
        return window.crypto.randomUUID();
    }
    return `workspace-${Date.now()}-${Math.random().toString(16).slice(2)}`;
};

const claimWorkspaceId = (storedWorkspaceId) => {
    let claimedWorkspaceId = storedWorkspaceId || createWorkspaceId();
    const documentId = createWorkspaceId();
    try {
        const claimKey = `featx.workspaceClaim.${claimedWorkspaceId}`;
        if (window.localStorage.getItem(claimKey)) {
            claimedWorkspaceId = createWorkspaceId();
        }
        const activeClaimKey = `featx.workspaceClaim.${claimedWorkspaceId}`;
        window.localStorage.setItem(activeClaimKey, documentId);
        window.addEventListener("unload", () => {
            if (window.localStorage.getItem(activeClaimKey) === documentId) {
                window.localStorage.removeItem(activeClaimKey);
            }
        });
    } catch (error) {
        // sessionStorage still isolates normal tabs when shared storage is unavailable.
    }
    return claimedWorkspaceId;
};

const workspaceId = (() => {
    const claimed = claimWorkspaceId(readSessionValue(WORKSPACE_ID_KEY));
    writeSessionValue(WORKSPACE_ID_KEY, claimed);
    return claimed;
})();

export const getSelectedRepositoryId = () => readSessionValue(REPOSITORY_ID_KEY);

export const setSelectedRepositoryId = (repositoryId) => {
    writeSessionValue(REPOSITORY_ID_KEY, repositoryId);
};

export const http = axios.create();

http.interceptors.request.use((config) => {
    const repositoryId = getSelectedRepositoryId();
    config.headers = config.headers || {};
    config.headers["X-FeatX-Workspace-Id"] = workspaceId;
    if (repositoryId) {
        config.headers["X-FeatX-Repo-Id"] = repositoryId;
    }
    return config;
});

export const eventSourceQuery = (params) => {
    const query = new URLSearchParams(params);
    query.set("workspaceId", workspaceId);
    const repositoryId = getSelectedRepositoryId();
    if (repositoryId) query.set("repoId", repositoryId);
    return query;
};
