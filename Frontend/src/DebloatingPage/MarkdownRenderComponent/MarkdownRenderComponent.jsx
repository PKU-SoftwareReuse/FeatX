// MarkdownRenderer.jsx
import React from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import rehypeHighlight from 'rehype-highlight'
import 'katex/dist/katex.min.css'
import 'highlight.js/styles/github.css'
import styles from './MarkdownRenderComponent.module.css'

const PYTHON_FILE_START = '<<<FEATX_PYTHON_FILE_START>>>'
const PYTHON_FILE_END = '<<<FEATX_PYTHON_FILE_END>>>'

const trimCodeEdges = (code) => code
    .replace(/^[\r\n]+/, '')
    .replace(/[\r\n]+$/, '')

const splitAgentContent = (content = '') => {
    const segments = []
    let cursor = 0

    while (cursor < content.length) {
        const start = content.indexOf(PYTHON_FILE_START, cursor)
        if (start < 0) {
            segments.push({type: 'markdown', value: content.slice(cursor)})
            break
        }

        if (start > cursor) {
            segments.push({type: 'markdown', value: content.slice(cursor, start)})
        }

        const codeStart = start + PYTHON_FILE_START.length
        const end = content.indexOf(PYTHON_FILE_END, codeStart)
        if (end < 0) {
            segments.push({type: 'code', value: trimCodeEdges(content.slice(codeStart))})
            break
        }

        segments.push({type: 'code', value: trimCodeEdges(content.slice(codeStart, end))})
        cursor = end + PYTHON_FILE_END.length
    }

    return segments
}

const MarkdownRendererComponent = ({content}) => (
    <div className={styles.markdownContent}>
        {splitAgentContent(content).map((segment, index) => {
            if (segment.type === 'code') {
                return (
                    <pre key={index} className={styles.agentCodeBlock}>
                        <code>{segment.value}</code>
                    </pre>
                )
            }
            return (
                <ReactMarkdown
                    key={index}
                    children={segment.value}
                    remarkPlugins={[remarkGfm, remarkMath]}
                    rehypePlugins={[rehypeKatex, rehypeHighlight]}
                />
            )
        })}
    </div>

)

export default MarkdownRendererComponent
