import {useEffect, useRef, useState} from 'react';
import {Button, Tooltip} from 'antd';
import {SearchOutlined, AlignLeftOutlined, LockOutlined} from '@ant-design/icons';
import {Editor} from '@monaco-editor/react';
import '../editor/monaco';
import {EDITOR_THEME_NAME, EDITOR_TYPOGRAPHY, registerEditorTheme} from '../editor/theme';
import styles from './SourcePreview.module.css';

export default function SourcePreview({file}) {
    const editor = useRef(null);
    const [wrap, setWrap] = useState(false);
    const [ready, setReady] = useState(false);
    useEffect(() => {editor.current?.revealLine(1);}, [file.path]);
    return <div className={styles.preview}>
        <div className={styles.toolbar}>
            <span className={styles.readOnly}><LockOutlined/> 只读预览</span>
            <div className={styles.actions}>
                <Tooltip title="查找文件内容"><Button type="text" size="small" aria-label="查找文件内容" disabled={!ready} icon={<SearchOutlined/>} onClick={() => editor.current?.getAction('actions.find')?.run()}/></Tooltip>
                <Tooltip title={wrap ? '关闭自动换行' : '开启自动换行'}><Button type="text" size="small" aria-label="自动换行" aria-pressed={wrap} className={wrap ? styles.active : ''} icon={<AlignLeftOutlined/>} onClick={() => setWrap(value => !value)}>换行</Button></Tooltip>
            </div>
        </div>
        <div className={styles.editor}>
            <Editor path={`source/${file.projectId}/${file.path}`} value={file.content} beforeMount={registerEditorTheme} theme={EDITOR_THEME_NAME}
                loading={<div className={styles.loading}>正在打开源码…</div>}
                onMount={instance => {editor.current = instance; setReady(true);}}
                options={{readOnly:true,domReadOnly:true,automaticLayout:true,minimap:{enabled:false},...EDITOR_TYPOGRAPHY,
                    scrollBeyondLastLine:false,padding:{top:16,bottom:16},wordWrap:wrap ? 'on' : 'off',
                    glyphMargin:false,lineNumbersMinChars:3,lineDecorationsWidth:12,renderLineHighlight:'none',
                    overviewRulerLanes:0,hideCursorInOverviewRuler:true,stickyScroll:{enabled:false},
                    unicodeHighlight:{ambiguousCharacters:false,nonBasicASCII:false},
                    scrollbar:{verticalScrollbarSize:7,horizontalScrollbarSize:7,useShadows:false},smoothScrolling:true}}/>
        </div>
        <div className={styles.status}><span>UTF-8</span><span>{file.content.split('\n').length.toLocaleString('zh-CN')} 行</span><span className={styles.statusHint}>源文件 · 不会修改代码</span></div>
    </div>;
}
