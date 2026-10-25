// Two related visual motifs: an Epic groups capabilities, a Feature is a single capability.
export function EpicIcon({className = ''}) {
    return <svg className={className} viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">
        <path d="m12 2 9 5-9 5-9-5Z" fill="#a995f3" stroke="#8766de" strokeWidth="1.2" strokeLinejoin="round"/>
        <path d="m3 11 9 5 9-5M3 16l9 5 9-5" stroke="#8766de" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"/>
        <path d="m9 6 3-1.7L15 6l-3 1.7Z" fill="white" fillOpacity=".85"/>
    </svg>;
}
export function FeatureIcon({className = ''}) {
    return <svg className={className} viewBox="0 0 24 24" width="19" height="19" fill="none" aria-hidden="true">
        <path d="M5 5h5C9 1 15 1 14 5h5v5c4-1 4 5 0 4v5h-5c1 4-5 4-4 0H5v-5c-4 1-4-5 0-4Z"
            fill="#eaf3ff" stroke="#448be7" strokeWidth="1.5" strokeLinejoin="round"/>
    </svg>;
}
export function FeatureCatalogIcon({className = ''}) {
    return <svg className={className} viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">
        <rect x="3" y="3" width="7" height="7" rx="2" fill="#e9e2ff" stroke="#8c6ddd" strokeWidth="1.5"/>
        <rect x="14" y="3" width="7" height="7" rx="2" fill="#eaf3ff" stroke="#488fe8" strokeWidth="1.5"/>
        <rect x="3" y="14" width="7" height="7" rx="2" fill="#eaf3ff" stroke="#488fe8" strokeWidth="1.5"/>
        <path d="M17.5 14v7M14 17.5h7" stroke="#8c6ddd" strokeWidth="1.8" strokeLinecap="round"/>
    </svg>;
}
export function GraphViewIcon({className = ''}) {
    return <svg className={className} viewBox="0 0 28 28" width="28" height="28" fill="none" aria-hidden="true">
        <path d="M10 14h5m0 0V6h4m-4 8v8h4" stroke="#438eea" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"/>
        <rect x="2" y="10" width="8" height="8" rx="2" fill="#dbeaff" stroke="#438eea" strokeWidth="1.5"/>
        <rect x="19" y="2" width="7" height="8" rx="2" fill="#edf5ff" stroke="#438eea" strokeWidth="1.5"/>
        <rect x="19" y="18" width="7" height="8" rx="2" fill="#edf5ff" stroke="#438eea" strokeWidth="1.5"/>
    </svg>;
}
export function AgentViewIcon({className = ''}) {
    return <svg className={className} viewBox="0 0 28 28" width="28" height="28" fill="none" aria-hidden="true">
        <path d="M14 7V4M3 13v7m22-7v7" stroke="#8b70d5" strokeWidth="1.7" strokeLinecap="round"/>
        <circle cx="14" cy="3" r="1.6" fill="#8b70d5"/>
        <rect x="6" y="8" width="16" height="17" rx="5" fill="#f1ecff" stroke="#8b70d5" strokeWidth="1.5"/>
        <path d="M10 14v2m8-2v2M11 21h6" stroke="#8b70d5" strokeWidth="2" strokeLinecap="round"/>
    </svg>;
}
