import React, {useEffect, useLayoutEffect, useRef} from "react";
import {DiffEditor} from "@monaco-editor/react";
import {monaco} from "../../editor/monaco";

import styles from "./GitDiffEditor.module.css";


const GitDiffEditor = ({file, value, onChange, onSave, readOnly = false}) => {
    const changeSubscriptionRef = useRef(null);
    const editorRef = useRef(null);
    const readOnlyRef = useRef(readOnly);
    readOnlyRef.current = readOnly;
    const onChangeRef = useRef(onChange);
    const onSaveRef = useRef(onSave);
    const controlledValueRef = useRef("");

    controlledValueRef.current = value ?? file?.modifiedContent ?? "";

    useEffect(() => {
        onChangeRef.current = onChange;
        onSaveRef.current = onSave;
    }, [onChange, onSave]);

    useLayoutEffect(() => () => {
        changeSubscriptionRef.current?.dispose();
        changeSubscriptionRef.current = null;
        const editor = editorRef.current;
        editorRef.current = null;
        if (!editor) return;
        const models = editor.getModel?.();
        // Detach the diff model before disposing text buffers. The React wrapper
        // otherwise disposes the buffers first, while DiffEditorWidget still uses them.
        editor.setModel?.(null);
        const ownedModels = new Set([models?.original, models?.modified]);
        for (const model of ownedModels) {
            if (model && !model.isDisposed()) model.dispose();
        }
        // The React wrapper remains responsible for disposing its editor widget.
    }, []);

    if (!file) return null;

    const handleMount = (editor) => {
        changeSubscriptionRef.current?.dispose();
        editorRef.current = editor;
        const originalEditor = editor.getOriginalEditor();
        const modifiedEditor = editor.getModifiedEditor();
        originalEditor.updateOptions({
            readOnly: true,
            domReadOnly: true,
            folding: false,
            glyphMargin: false,
            lineDecorationsWidth: 0,
            lineNumbers: "off",
            lineNumbersMinChars: 0,
        });
        const originalEditorNode = originalEditor.getDomNode();
        if (originalEditorNode) {
            originalEditorNode.style.visibility = "hidden";
        }
        modifiedEditor.updateOptions({
            readOnly,
            domReadOnly: readOnly,
            lineNumbers: "on",
            lineNumbersMinChars: 4,
        });
        {
            changeSubscriptionRef.current = modifiedEditor.onDidChangeModelContent(() => {
                if (readOnlyRef.current) return;
                const editorValue = modifiedEditor.getValue();
                if (editorValue === controlledValueRef.current) return;
                onChangeRef.current?.(editorValue);
            });
            modifiedEditor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => {
                if (!readOnlyRef.current) onSaveRef.current?.(modifiedEditor.getValue());
            });
        }
    };

    return (
        <div className={styles.editorSurface}>
            <DiffEditor
                height="100%"
                language={file.language || "plaintext"}
                original={file.originalContent || ""}
                modified={value ?? file.modifiedContent ?? ""}
                onMount={handleMount}
                keepCurrentOriginalModel
                keepCurrentModifiedModel
                theme="vs"
                loading={<div className={styles.loading}>正在打开差异…</div>}
                options={{
                    automaticLayout: true,
                    enableSplitViewResizing: false,
                    fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
                    fontSize: 12,
                    glyphMargin: true,
                    lineNumbers: "on",
                    minimap: {enabled: false},
                    originalEditable: false,
                    readOnly,
                    domReadOnly: readOnly,
                    renderMarginRevertIcon: !readOnly,
                    renderIndicators: true,
                    renderOverviewRuler: true,
                    renderSideBySide: false,
                    scrollBeyondLastLine: false,
                    smoothScrolling: true,
                    wordWrap: "off",
                }}
            />
        </div>
    );
};

export default GitDiffEditor;
