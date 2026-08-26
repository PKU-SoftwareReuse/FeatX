import {render, screen} from '@testing-library/react'
import '@testing-library/jest-dom'

import MarkdownRendererComponent, {
    normalizeAgentPresentation,
    splitAgentContent,
} from './MarkdownRenderComponent'

jest.mock('react-markdown', () => ({children}) => <div>{children}</div>)
jest.mock('remark-gfm', () => () => null)
jest.mock('remark-math', () => () => null)
jest.mock('rehype-katex', () => () => null)
jest.mock('rehype-highlight', () => () => null)

test('renders completed Search/Replace output as deletion and addition sections', () => {
    render(<MarkdownRendererComponent content={`# Agent3
<<<<<<< SEARCH
private int value = 1;
=======
private int value = 2;
>>>>>>> REPLACE
`}/>)

    expect(screen.getByText(/Agent3/)).toBeInTheDocument()
    expect(screen.getByText('SEARCH / REPLACE')).toBeInTheDocument()
    expect(screen.getByText('SEARCH')).toBeInTheDocument()
    expect(screen.getByText('REPLACE')).toBeInTheDocument()
    expect(screen.getByText('private int value = 1;')).toBeInTheDocument()
    expect(screen.getByText('private int value = 2;')).toBeInTheDocument()
    expect(screen.queryByText('STREAMING')).not.toBeInTheDocument()
})

test('renders an incomplete streamed Search block without swallowing its content', () => {
    render(<MarkdownRendererComponent content={`<<<<<<< SEARCH
public void run() {
`}/>)

    expect(screen.getByText('SEARCH / REPLACE')).toBeInTheDocument()
    expect(screen.getByText('STREAMING')).toBeInTheDocument()
    expect(screen.getByText('public void run() {')).toBeInTheDocument()
    expect(screen.queryByText('REPLACE')).not.toBeInTheDocument()
})

test('renders a streamed replacement as soon as the separator arrives', () => {
    render(<MarkdownRendererComponent content={`<<<<<<< SEARCH
int oldValue;
=======
long newValue;
`}/>)

    expect(screen.getByText('STREAMING')).toBeInTheDocument()
    expect(screen.getByText('int oldValue;')).toBeInTheDocument()
    expect(screen.getByText('long newValue;')).toBeInTheDocument()
})

test('renders CREATE output as a new Java file block', () => {
    render(<MarkdownRendererComponent content={`<<<<<<< CREATE
package cn.edu.pku;
public class Foo {}
>>>>>>> CREATE
`}/>)

    expect(screen.getByText('CREATE')).toBeInTheDocument()
    expect(screen.getByText('NEW FILE')).toBeInTheDocument()
    expect(screen.getByText(/package cn\.edu\.pku/)).toBeInTheDocument()
    expect(screen.getByText(/public class Foo/)).toBeInTheDocument()
})

test('formats completed Agent1 and Agent2 JSON as structured output', () => {
    render(<MarkdownRendererComponent content={`# Stage I
{"needAdditionalFile":false,"additionalFileList":[]}
# Stage II
{"modifiedFileList":[{"filename":"cn/edu/pku/Foo.java","plan":"Edit Foo"}]}
`}/>)

    expect(screen.getAllByText('JSON')).toHaveLength(2)
    expect(screen.getAllByText('STRUCTURED OUTPUT')).toHaveLength(2)
    expect(screen.getByText(/"needAdditionalFile": false/)).toBeInTheDocument()
    expect(screen.getByText(/"filename": "cn\/edu\/pku\/Foo.java"/)).toBeInTheDocument()
})

test('removes JSON markdown fences without breaking the following stage heading', () => {
    const segments = splitAgentContent(`# === 阶段 II：修改方案规划 ===
\`\`\`json
{"modifiedFileList":[{"filename":"package/module.py","plan":"修改实现"}]}
\`\`\`
# === 阶段 III：具体文件修改 package/module.py ===
`)

    expect(segments.map((segment) => segment.type)).toEqual(['markdown', 'json', 'markdown'])
    expect(segments[0].value).toContain('阶段 II')
    expect(segments[0].value).not.toContain('```json')
    expect(segments[1]).toMatchObject({type: 'json', complete: true})
    expect(segments[2].value).toContain('阶段 III')
    expect(segments[2].value).not.toContain('```')
})

test('hides an opening JSON fence while its object is still streaming', () => {
    const segments = splitAgentContent(`# === Stage II ===
\`\`\`json
{"modifiedFileList":[`)

    expect(segments).toHaveLength(2)
    expect(segments[0]).toMatchObject({type: 'markdown'})
    expect(segments[0].value).not.toContain('```json')
    expect(segments[1]).toMatchObject({type: 'json', complete: false})
})

test('renders incomplete streamed JSON and respects braces inside strings', () => {
    const segments = splitAgentContent(`# Stage II
{"modifiedFileList":[{"plan":"Keep {value} and escaped \\"quote\\"","filename":"Foo.java"`)

    expect(segments).toHaveLength(2)
    expect(segments[1]).toMatchObject({type: 'json', complete: false})

    render(<MarkdownRendererComponent content={`# Stage I
{"needAdditionalFile":true,"additionalFileList":[`}/>)
    expect(screen.getByText('JSON')).toBeInTheDocument()
    expect(screen.getByText('PARTIAL OUTPUT')).toBeInTheDocument()
    expect(screen.getByText('STREAMING')).toBeInTheDocument()
    expect(screen.getByText(/needAdditionalFile/)).toBeInTheDocument()
})

test('keeps markdown around multiple protocol blocks as independent segments', () => {
    const segments = splitAgentContent(`# Stage III
<<<<<<< SEARCH
old
=======
new
>>>>>>> REPLACE
done
<<<<<<< CREATE
class Added {}
>>>>>>> CREATE
`)

    expect(segments.map((segment) => segment.type)).toEqual([
        'markdown',
        'searchReplace',
        'markdown',
        'create',
        'markdown',
    ])
    expect(segments[1]).toMatchObject({search: 'old', replacement: 'new', complete: true})
    expect(segments[3]).toMatchObject({value: 'class Added {}', complete: true})
})

test('continues to render Python file sentinels', () => {
    render(<MarkdownRendererComponent content={`<<<FEATX_PYTHON_FILE_START>>>
def main():
    return 1
<<<FEATX_PYTHON_FILE_END>>>
`}/>)

    expect(screen.getByText('PYTHON FILE')).toBeInTheDocument()
    expect(screen.getByText(/def main/)).toBeInTheDocument()
})

test('shows each primary stage once and weakens recheck and per-file headings', () => {
    const normalized = normalizeAgentPresentation(`# === 阶段 I：信息需求分析 ===
# === 阶段 I：补充上下文复核 ===
# === 阶段 II：修改方案规划 ===
# === 阶段 III：具体文件修改 top/naccl/Service.java ===
# === 阶段 III：具体文件修改 top/naccl/Mapper.java ===
`)

    expect(normalized.match(/## 阶段 I：信息需求分析/g)).toHaveLength(1)
    expect(normalized.match(/## 阶段 II：修改方案规划/g)).toHaveLength(1)
    expect(normalized.match(/## 阶段 III：具体文件修改/g)).toHaveLength(1)
    expect(normalized).toContain('#### 补充上下文复核')
    expect(normalized).toContain('#### 文件：`top/naccl/Service.java`')
    expect(normalized).toContain('#### 文件：`top/naccl/Mapper.java`')
})

test('does not normalize stage-like comments inside patch protocols', () => {
    const normalized = normalizeAgentPresentation(`## Stage III: Concrete File Modification
  <<<<<<< SEARCH
# Stage II: Modification Planning
  =======
# Stage II: Modification Planning updated
  >>>>>>> REPLACE
`)

    expect(normalized).toContain('# Stage II: Modification Planning\n  =======')
    expect(normalized).toContain('# Stage II: Modification Planning updated')
})
