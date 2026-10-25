import {useCallback} from 'react';
import {flushSync} from 'react-dom';
import {useNavigate} from 'react-router-dom';

let transition = null;
export const hasPageTransition = () => Boolean(transition);

export default function usePageNavigation() {
    const navigate = useNavigate();
    return useCallback((to, options) => {
        if (!document.startViewTransition || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
            navigate(to, options);
            return;
        }
        transition?.skipTransition();
        const next = document.startViewTransition(() => {
            flushSync(() => navigate(to, options));
        });
        transition = next;
        next.finished.catch(() => {}).finally(() => {
            if (transition === next) transition = null;
        });
    }, [navigate]);
}
