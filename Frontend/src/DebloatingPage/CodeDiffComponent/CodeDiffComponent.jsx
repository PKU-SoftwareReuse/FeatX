// CodeDiffComponent.jsx

import React, { useEffect, useRef } from "react";
import { html } from "diff2html";
import "diff2html/bundles/css/diff2html.min.css";
import "./codediff.css"
import styles from "./CodeDiffComponent.module.css"

const CodeDiffComponent = ({ diffText, isPlainCode = false }) => {
    const containerRef = useRef();

    useEffect(() => {
        // console.log('CodeDiffComponent received:', { diffText: diffText?.substring(0, 100) + '...', isPlainCode });
        if (containerRef.current && diffText) {
            if (isPlainCode) {
                // 如果是普通代码，直接显示
                console.log('Displaying as plain code');
                containerRef.current.innerHTML = `<pre><code class="language-java">${diffText}</code></pre>`;
            } else {
                // 如果是diff格式，使用diff2html处理
                try {
                    const diffHtml = html(diffText, {
                        inputFormat: "diff",
                        showFiles: true,
                        matching: "lines",
                        outputFormat: "line-by-line",
                        drawFileList: false,
                    });
                    containerRef.current.innerHTML = diffHtml;
                } catch (error) {
                    console.error('Error parsing diff:', error);
                    // 如果diff解析失败，尝试作为普通代码显示
                    containerRef.current.innerHTML = `<pre><code class="language-java">${diffText}</code></pre>`;
                }
            }
        }
    }, [diffText, isPlainCode]);

    return (
        <div
            ref={containerRef}
            className={"diff-container " + styles.diffArea}
        />
    );
};

export default CodeDiffComponent;