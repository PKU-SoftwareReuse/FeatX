import React, {useEffect, useMemo, useState} from "react";
import {Select} from "antd";
import {parse} from "diff2html";

import styles from "./CodeDiffComponent.module.css";

const GitDiffEditor = React.lazy(() => import("../GitDiffEditor/GitDiffEditor"));

const stripDiffPrefix = (content = "") => content.length > 0 ? content.slice(1) : "";

const normalizePath = (path) => {
    if (!path || path === "/dev/null") return "";
    return path.replace(/^[ab]\//, "");
};

const inferLanguage = (path, parsedLanguage) => {
    if (parsedLanguage && parsedLanguage !== "plaintext") return parsedLanguage;
    const extension = path.split(".").pop()?.toLowerCase();
    return {
        java: "java",
        js: "javascript",
        jsx: "javascript",
        json: "json",
        kt: "kotlin",
        md: "markdown",
        py: "python",
        ts: "typescript",
        tsx: "typescript",
        xml: "xml",
        yml: "yaml",
        yaml: "yaml",
    }[extension] || "plaintext";
};

const buildFileContents = (file) => {
    const originalLines = [];
    const modifiedLines = [];

    file.blocks.forEach((block) => {
        block.lines.forEach((line) => {
            const content = stripDiffPrefix(line.content);
            if (line.oldNumber != null) originalLines[line.oldNumber - 1] = content;
            if (line.newNumber != null) modifiedLines[line.newNumber - 1] = content;
        });
    });

    const originalLength = originalLines.length;
    const modifiedLength = modifiedLines.length;
    const normalizeLines = (lines, length) => Array.from(
        {length},
        (_, index) => lines[index] ?? ""
    ).join("\n");

    return {
        originalContent: normalizeLines(originalLines, originalLength),
        modifiedContent: normalizeLines(modifiedLines, modifiedLength),
    };
};

export const buildReadOnlyDiffFiles = (diffText, isPlainCode = false) => {
    if (!diffText) return [];
    if (isPlainCode) {
        return [{
            key: "preview",
            path: "Preview",
            language: "plaintext",
            originalContent: diffText,
            modifiedContent: diffText,
            status: "",
        }];
    }

    try {
        const parsedFiles = parse(diffText);
        if (parsedFiles.length === 0) throw new Error("No diff files found");

        return parsedFiles.map((file, index) => {
            const path = normalizePath(file.newName) || normalizePath(file.oldName) || `File ${index + 1}`;
            const contents = file.blocks.length > 0
                ? buildFileContents(file)
                : {
                    originalContent: file.isBinary ? "Binary file" : "",
                    modifiedContent: file.isBinary ? "Binary file" : "",
                };
            return {
                key: `${index}:${path}`,
                path,
                language: inferLanguage(path, file.language),
                status: file.isNew ? "A" : file.isDeleted ? "D" : file.isRename ? "R" : "M",
                ...contents,
            };
        });
    } catch (error) {
        console.error("Error parsing diff:", error);
        return [{
            key: "raw-patch",
            path: "Git Patch",
            language: "diff",
            originalContent: "",
            modifiedContent: diffText,
            status: "M",
        }];
    }
};

const CodeDiffComponent = ({diffText, isPlainCode = false, showFileHeader = true}) => {
    const files = useMemo(
        () => buildReadOnlyDiffFiles(diffText, isPlainCode),
        [diffText, isPlainCode]
    );
    const [selectedKey, setSelectedKey] = useState(null);

    useEffect(() => {
        setSelectedKey((current) => files.some((file) => file.key === current)
            ? current
            : files[0]?.key || null);
    }, [files]);

    const selectedFile = files.find((file) => file.key === selectedKey) || files[0];
    if (!selectedFile) return null;

    return (
        <div className={`diff-container ${styles.diffArea}`}>
            {showFileHeader && (
                <div className={styles.fileToolbar}>
                    {selectedFile.status && (
                        <span className={styles.fileStatus}>{selectedFile.status}</span>
                    )}
                    {files.length > 1 ? (
                        <Select
                            className={styles.fileSelect}
                            size="small"
                            value={selectedFile.key}
                            onChange={setSelectedKey}
                            aria-label="Changed file"
                            options={files.map((file) => ({
                                value: file.key,
                                label: file.path,
                            }))}
                            popupMatchSelectWidth={false}
                        />
                    ) : (
                        <span className={styles.filePath} title={selectedFile.path}>
                            {selectedFile.path}
                        </span>
                    )}
                    {files.length > 1 && (
                        <span className={styles.fileCount}>{files.length}</span>
                    )}
                </div>
            )}
            <div className={styles.editorArea}>
                <React.Suspense fallback={<div className={styles.loading}>Loading preview...</div>}>
                    <GitDiffEditor
                        file={selectedFile}
                        value={selectedFile.modifiedContent}
                        readOnly
                    />
                </React.Suspense>
            </div>
        </div>
    );
};

export default CodeDiffComponent;
