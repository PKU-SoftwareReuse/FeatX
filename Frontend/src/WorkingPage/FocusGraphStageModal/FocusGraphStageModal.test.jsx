import {fireEvent, render, screen} from '@testing-library/react'
import '@testing-library/jest-dom'

import {LanguageProvider} from '../../i18n/LanguageContext'
import FocusGraphStageModal from './FocusGraphStageModal'

jest.mock('@ant-design/icons', () => ({
    PlayCircleOutlined: () => <span data-testid="play-icon"/>,
}))

jest.mock('antd', () => ({
    Modal: ({title, open, children}) => open ? (
        <section role="dialog">
            <h2>{title}</h2>
            {children}
        </section>
    ) : null,
    Segmented: ({options, value, onChange}) => (
        <div data-testid="stage-segmented">
            {options.map((option) => (
                <button
                    type="button"
                    key={option.value}
                    aria-pressed={option.value === value}
                    onClick={() => onChange(option.value)}
                >
                    {option.label}
                </button>
            ))}
        </div>
    ),
    Button: ({children, onClick}) => <button type="button" onClick={onClick}>{children}</button>,
    Tag: ({children}) => <span data-testid="active-stage-tag">{children}</span>,
}))

jest.mock('vis-network/standalone/esm/vis-network', () => {
    class DataSet {
        constructor(items = []) {
            this.items = new Map()
            this.add(items)
        }

        add(items) {
            const values = Array.isArray(items) ? items : [items]
            values.filter(Boolean).forEach((item) => this.items.set(item.id, item))
        }

        update(items) {
            const values = Array.isArray(items) ? items : [items]
            values.filter(Boolean).forEach((item) => {
                this.items.set(item.id, {...this.items.get(item.id), ...item})
            })
        }

        get(id) {
            return this.items.get(id)
        }

        remove(ids) {
            const values = Array.isArray(ids) ? ids : [ids]
            values.forEach((id) => this.items.delete(id))
        }

        clear() {
            this.items.clear()
        }
    }

    class Network {
        moveNode() {}
        moveTo() {}
        destroy() {}
    }

    return {DataSet, Network}
})

const stages = [
    {
        id: 'initial',
        label: 'Initial Graph',
        description: 'English initial description.',
        nodes: [{id: 'a', label: 'a'}],
        edges: [],
    },
    {
        id: 'expanded',
        label: 'Expanded Graph',
        description: 'English expanded description.',
        nodes: [{id: 'a', label: 'a'}, {id: 'b', label: 'b'}],
        edges: [{from: 'a', to: 'b', type: 'Call'}],
    },
    {
        id: 'reasoning',
        label: 'Reasoning Graph',
        description: 'English reasoning description.',
        nodes: [{id: 'b', label: 'b'}],
        edges: [],
    },
]

const renderModal = (language) => {
    window.localStorage.setItem('featx-language', language)
    return render(
        <LanguageProvider>
            <FocusGraphStageModal open onClose={jest.fn()} stages={stages}/>
        </LanguageProvider>
    )
}

afterEach(() => {
    window.localStorage.clear()
})

test('localizes all graph-stage controls for the Chinese interface', () => {
    renderModal('zh')

    expect(screen.getByRole('heading', {name: '推理图构建阶段'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '初始图'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '扩展图'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '推理图'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '切换到 初始图'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '切换到 扩展图'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: '切换到 推理图'})).toBeInTheDocument()
    expect(screen.getByText('重播动画')).toBeInTheDocument()
    expect(screen.getByText('method 节点')).toBeInTheDocument()
    expect(screen.getByText('class 节点')).toBeInTheDocument()
    expect(screen.queryByText('English initial description.')).not.toBeInTheDocument()
})

test('switches directly to a stage when its summary card is clicked', () => {
    renderModal('zh')

    const reasoningCard = screen.getByRole('button', {name: '切换到 推理图'})
    expect(reasoningCard).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(reasoningCard)

    expect(reasoningCard).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('active-stage-tag')).toHaveTextContent('推理图')
    expect(screen.getByText('节点 1 · 边 0')).toBeInTheDocument()
})

test('keeps English labels when the web interface is English', () => {
    renderModal('en')

    expect(screen.getByRole('heading', {name: 'Reasoning Graph Stages'})).toBeInTheDocument()
    expect(screen.getByRole('button', {name: 'Switch to Expanded Graph'})).toBeInTheDocument()
    expect(screen.getByText('Replay')).toBeInTheDocument()
})
