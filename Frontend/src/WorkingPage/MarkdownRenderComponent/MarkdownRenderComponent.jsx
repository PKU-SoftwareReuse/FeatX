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
const SEARCH_START = '<<<<<<< SEARCH'
const SEARCH_SEPARATOR = '======='
const REPLACE_END = '>>>>>>> REPLACE'
const CREATE_START = '<<<<<<< CREATE'
const CREATE_END = '>>>>>>> CREATE'
const JSON_FENCE = '```json'

const cleanHeadingText = (line) => line
    .replace(/^#{1,6}\s*/, '')
    .replace(/^===\s*/, '')
    .replace(/\s*===\s*$/, '')
    .trim()

export const normalizeAgentPresentation = (content = '') => {
    const seenStages = new Set()
    const normalized = []
    let insideProtocol = false

    content.split('\n').forEach((line) => {
        if (line.trim() === SEARCH_START
            || line.trim() === CREATE_START
            || line.trim() === PYTHON_FILE_START) {
            insideProtocol = true
            normalized.push(line)
            return
        }
        if (insideProtocol) {
            normalized.push(line)
            if (line.trim() === REPLACE_END
                || line.trim() === CREATE_END
                || line.trim() === PYTHON_FILE_END) {
                insideProtocol = false
            }
            return
        }

        const heading = cleanHeadingText(line)
        const chineseStage = heading.match(/^阶段\s*(I|II|III)\s*[：:]\s*(.*)$/i)
        const englishStage = heading.match(/^Stage\s*(I|II|III)\s*[：:]\s*(.*)$/i)
        const stageMatch = chineseStage || englishStage
        if (!stageMatch || !/^#{1,6}\s/.test(line)) {
            normalized.push(line)
            return
        }

        const language = chineseStage ? 'CN' : 'EN'
        const stage = stageMatch[1].toUpperCase()
        const detail = stageMatch[2].trim()
        if (stage === 'I' && /^(补充上下文复核|Additional Context Recheck)$/i.test(detail)) {
            normalized.push(language === 'CN' ? '#### 补充上下文复核' : '#### Additional Context Recheck')
            return
        }

        const primaryDetailMatches = stage === 'I'
            ? /^(信息需求分析|Information Requirement Analysis)$/i.test(detail)
            : stage === 'II'
                ? /^(修改方案规划|Modification Planning)$/i.test(detail)
                : /^(具体文件修改|Concrete File Modification)(?:\s+.*)?$/i.test(detail)
        if (!primaryDetailMatches) {
            normalized.push(line)
            return
        }

        if (stage === 'III') {
            const prefix = language === 'CN' ? '具体文件修改' : 'Concrete File Modification'
            const filename = detail.startsWith(prefix) ? detail.slice(prefix.length).trim() : ''
            if (!seenStages.has(stage)) {
                seenStages.add(stage)
                normalized.push(language === 'CN'
                    ? '## 阶段 III：具体文件修改'
                    : '## Stage III: Concrete File Modification')
            }
            if (filename) {
                normalized.push(language === 'CN'
                    ? `#### 文件：\`${filename}\``
                    : `#### File: \`${filename}\``)
            }
            return
        }

        if (seenStages.has(stage)) return
        seenStages.add(stage)
        if (stage === 'I') {
            normalized.push(language === 'CN'
                ? '## 阶段 I：信息需求分析'
                : '## Stage I: Information Requirement Analysis')
        } else {
            normalized.push(language === 'CN'
                ? '## 阶段 II：修改方案规划'
                : '## Stage II: Modification Planning')
        }
    })

    return normalized.join('\n')
}

const trimCodeEdges = (code) => code
    .replace(/^[\r\n]+/, '')
    .replace(/[\r\n]+$/, '')

const findJsonStart = (content, cursor) => {
    for (let index = content.indexOf('{', cursor); index >= 0; index = content.indexOf('{', index + 1)) {
        let next = index + 1
        while (next < content.length && /\s/.test(content[next])) next += 1
        if (next >= content.length || content[next] === '"' || content[next] === '}') {
            return index
        }
    }
    return -1
}

const findJsonEnd = (content, start) => {
    let depth = 0
    let inString = false
    let escaped = false

    for (let index = start; index < content.length; index += 1) {
        const character = content[index]
        if (inString) {
            if (escaped) {
                escaped = false
            } else if (character === '\\') {
                escaped = true
            } else if (character === '"') {
                inString = false
            }
            continue
        }

        if (character === '"') {
            inString = true
        } else if (character === '{') {
            depth += 1
        } else if (character === '}') {
            depth -= 1
            if (depth === 0) return index + 1
        }
    }
    return -1
}

const nextProtocolStart = (content, cursor) => {
    const candidates = [
        {type: 'pythonCode', marker: PYTHON_FILE_START},
        {type: 'searchReplace', marker: SEARCH_START},
        {type: 'create', marker: CREATE_START},
    ]
        .map((candidate) => ({
            ...candidate,
            index: content.indexOf(candidate.marker, cursor),
        }))
        .filter((candidate) => candidate.index >= 0)
    const jsonIndex = findJsonStart(content, cursor)
    if (jsonIndex >= 0) {
        candidates.push({type: 'json', marker: '{', index: jsonIndex})
    }
    candidates.sort((left, right) => left.index - right.index)

    return candidates[0] || null
}

const jsonFenceStart = (content, cursor, jsonStart) => {
    const fence = content.lastIndexOf(JSON_FENCE, jsonStart)
    if (fence < cursor) return -1
    return content.slice(fence + JSON_FENCE.length, jsonStart).trim() === '' ? fence : -1
}

const afterClosingJsonFence = (content, jsonEnd) => {
    let cursor = jsonEnd
    while (cursor < content.length && /\s/.test(content[cursor])) cursor += 1
    return content.startsWith('```', cursor) ? cursor + 3 : jsonEnd
}

export const splitAgentContent = (rawContent = '') => {
    const content = normalizeAgentPresentation(rawContent)
    const segments = []
    let cursor = 0

    while (cursor < content.length) {
        const protocol = nextProtocolStart(content, cursor)
        if (!protocol) {
            segments.push({type: 'markdown', value: content.slice(cursor)})
            break
        }

        const fencedJsonStart = protocol.type === 'json'
            ? jsonFenceStart(content, cursor, protocol.index)
            : -1
        const segmentStart = fencedJsonStart >= 0 ? fencedJsonStart : protocol.index

        if (segmentStart > cursor) {
            segments.push({type: 'markdown', value: content.slice(cursor, segmentStart)})
        }

        const bodyStart = protocol.index + protocol.marker.length
        if (protocol.type === 'json') {
            const end = findJsonEnd(content, protocol.index)
            if (end < 0) {
                segments.push({
                    type: 'json',
                    value: content.slice(protocol.index),
                    complete: false,
                })
                break
            }
            segments.push({
                type: 'json',
                value: content.slice(protocol.index, end),
                complete: true,
            })
            cursor = fencedJsonStart >= 0 ? afterClosingJsonFence(content, end) : end
            continue
        }
        if (protocol.type === 'pythonCode') {
            const end = content.indexOf(PYTHON_FILE_END, bodyStart)
            if (end < 0) {
                segments.push({
                    type: 'pythonCode',
                    value: trimCodeEdges(content.slice(bodyStart)),
                    complete: false,
                })
                break
            }
            segments.push({
                type: 'pythonCode',
                value: trimCodeEdges(content.slice(bodyStart, end)),
                complete: true,
            })
            cursor = end + PYTHON_FILE_END.length
            continue
        }

        if (protocol.type === 'create') {
            const end = content.indexOf(CREATE_END, bodyStart)
            if (end < 0) {
                segments.push({
                    type: 'create',
                    value: trimCodeEdges(content.slice(bodyStart)),
                    complete: false,
                })
                break
            }
            segments.push({
                type: 'create',
                value: trimCodeEdges(content.slice(bodyStart, end)),
                complete: true,
            })
            cursor = end + CREATE_END.length
            continue
        }

        const separator = content.indexOf(SEARCH_SEPARATOR, bodyStart)
        if (separator < 0) {
            segments.push({
                type: 'searchReplace',
                search: trimCodeEdges(content.slice(bodyStart)),
                replacement: null,
                complete: false,
            })
            break
        }

        const replacementStart = separator + SEARCH_SEPARATOR.length
        const end = content.indexOf(REPLACE_END, replacementStart)
        if (end < 0) {
            segments.push({
                type: 'searchReplace',
                search: trimCodeEdges(content.slice(bodyStart, separator)),
                replacement: trimCodeEdges(content.slice(replacementStart)),
                complete: false,
            })
            break
        }

        segments.push({
            type: 'searchReplace',
            search: trimCodeEdges(content.slice(bodyStart, separator)),
            replacement: trimCodeEdges(content.slice(replacementStart, end)),
            complete: true,
        })
        cursor = end + REPLACE_END.length
    }

    return segments
}

const StreamingBadge = () => (
    <span className={styles.protocolStreaming}>STREAMING</span>
)

const ProtocolCode = ({label, value, tone}) => (
    <section className={`${styles.protocolSection} ${styles[tone]}`}>
        <div className={styles.protocolLabel}>{label}</div>
        <pre className={styles.protocolCode}>
            <code>{value === '' ? '∅' : value}</code>
        </pre>
    </section>
)

const SearchReplaceBlock = ({segment}) => (
    <div className={styles.protocolBlock} data-agent-protocol="search-replace">
        <div className={styles.protocolHeader}>
            <span>SEARCH / REPLACE</span>
            {!segment.complete && <StreamingBadge/>}
        </div>
        <ProtocolCode label="SEARCH" value={segment.search} tone="protocolSearch"/>
        {segment.replacement !== null && (
            <ProtocolCode label="REPLACE" value={segment.replacement} tone="protocolReplace"/>
        )}
    </div>
)

const CreateBlock = ({segment}) => (
    <div className={styles.protocolBlock} data-agent-protocol="create">
        <div className={styles.protocolHeader}>
            <span>CREATE</span>
            {!segment.complete && <StreamingBadge/>}
        </div>
        <ProtocolCode label="NEW FILE" value={segment.value} tone="protocolCreate"/>
    </div>
)

const formatJson = (value, complete) => {
    if (!complete) return trimCodeEdges(value)
    try {
        return JSON.stringify(JSON.parse(value), null, 2)
    } catch (error) {
        return trimCodeEdges(value)
    }
}

const JsonBlock = ({segment}) => (
    <div className={styles.protocolBlock} data-agent-protocol="json">
        <div className={styles.protocolHeader}>
            <span>JSON</span>
            {!segment.complete && <StreamingBadge/>}
        </div>
        <ProtocolCode
            label={segment.complete ? 'STRUCTURED OUTPUT' : 'PARTIAL OUTPUT'}
            value={formatJson(segment.value, segment.complete)}
            tone="protocolJson"
        />
    </div>
)

const MarkdownRendererComponent = ({content}) => (
    <div className={styles.markdownContent}>
        {splitAgentContent(content).map((segment, index) => {
            if (segment.type === 'pythonCode') {
                return (
                    <div key={index} className={styles.protocolBlock} data-agent-protocol="python-file">
                        <div className={styles.protocolHeader}>
                            <span>PYTHON FILE</span>
                            {!segment.complete && <StreamingBadge/>}
                        </div>
                        <pre className={styles.agentCodeBlock}>
                            <code>{segment.value}</code>
                        </pre>
                    </div>
                )
            }
            if (segment.type === 'searchReplace') {
                return <SearchReplaceBlock key={index} segment={segment}/>
            }
            if (segment.type === 'create') {
                return <CreateBlock key={index} segment={segment}/>
            }
            if (segment.type === 'json') {
                return <JsonBlock key={index} segment={segment}/>
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
