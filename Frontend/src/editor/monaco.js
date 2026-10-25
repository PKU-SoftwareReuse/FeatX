import * as monaco from 'monaco-editor';
import {loader} from '@monaco-editor/react';

// Bundle workers locally through Webpack. No AMD loader, CDN, or localhost URLs.
// Keep each URL literal so the bundler emits a separate Worker entrypoint.
window.MonacoEnvironment = {
    getWorker(_moduleId, label) {
        if (label === 'typescript' || label === 'javascript') {
            return new Worker(new URL('./workers/typescript.worker.js', import.meta.url), {name:'featx-typescript'});
        }
        if (label === 'json') {
            return new Worker(new URL('./workers/json.worker.js', import.meta.url), {name:'featx-json'});
        }
        if (label === 'css' || label === 'scss' || label === 'less') {
            return new Worker(new URL('./workers/css.worker.js', import.meta.url), {name:'featx-css'});
        }
        if (label === 'html' || label === 'handlebars' || label === 'razor') {
            return new Worker(new URL('./workers/html.worker.js', import.meta.url), {name:'featx-html'});
        }
        return new Worker(new URL('./workers/editor.worker.js', import.meta.url), {name:'featx-editor'});
    },
};
loader.config({monaco});
export {monaco};
