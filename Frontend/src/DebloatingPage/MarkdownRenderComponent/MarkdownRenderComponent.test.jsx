import {render, screen} from '@testing-library/react'
import '@testing-library/jest-dom'

import MarkdownRendererComponent, {splitAgentContent} from './MarkdownRenderComponent'

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
