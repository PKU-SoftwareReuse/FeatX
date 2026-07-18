import React, {useEffect, useRef} from "react";
import {DiffEditor, loader} from "@monaco-editor/react";
import * as monaco from "monaco-editor";

import styles from "./GitDiffEditor.module.css";

loader.config({monaco});

const GitDiffEditor = ({file, value, onChange, onSave}) => {
    const changeSubscriptionRef = useRef(null);
    const onChangeRef = useRef(onChange);
    const onSaveRef = useRef(onSave);

    useEffect(() => {
        onChangeRef.current = onChange;
        onSaveRef.current = onSave;
    }, [onChange, onSave]);

    useEffect(() => () => {
        changeSubscriptionRef.current?.dispose();
    }, []);

    if (!file) return null;

    const handleMount = (editor) => {
        changeSubscriptionRef.current?.dispose();
        const originalEditor = editor.getOriginalEditor();
        const modifiedEditor = editor.getModifiedEditor();
        originalEditor.updateOptions({readOnly: true, domReadOnly: true});
        modifiedEditor.updateOptions({readOnly: false, domReadOnly: false});
        changeSubscriptionRef.current = modifiedEditor.onDidChangeModelContent(() => {
            onChangeRef.current?.(modifiedEditor.getValue());
        });
        modifiedEditor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
            onSaveRef.current?.(modifiedEditor.getValue());
        });
    };

    return (
        <div className={styles.editorSurface}>
            <DiffEditor
                height="100%"
                language={file.language || "plaintext"}
                original={file.originalContent || ""}
                modified={value ?? file.modifiedContent ?? ""}
                onMount={handleMount}
                theme="vs"
                loading={<div className={styles.loading}>Loading editor...</div>}
                options={{
                    automaticLayout: true,
                    enableSplitViewResizing: true,
                    fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
                    fontSize: 13,
                    glyphMargin: true,
                    lineNumbers: "on",
                    minimap: {enabled: false},
                    originalEditable: false,
                    renderIndicators: true,
                    renderOverviewRuler: true,
                    renderSideBySide: true,
                    scrollBeyondLastLine: false,
                    smoothScrolling: true,
                    wordWrap: "off",
                }}
            />
        </div>
    );
};

export default GitDiffEditor;
