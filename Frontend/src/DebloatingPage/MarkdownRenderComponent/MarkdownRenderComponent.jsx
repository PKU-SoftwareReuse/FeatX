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

const MarkdownRendererComponent = ({content}) => (
    <div className={styles.markdownContent}>
        <ReactMarkdown
            children={content}
            remarkPlugins={[remarkGfm, remarkMath]}
            rehypePlugins={[rehypeKatex, rehypeHighlight]}
        />
    </div>

)

export default MarkdownRendererComponent
