import {useEffect, useRef, useState} from 'react';
import {Button, Tooltip} from 'antd';
import {SearchOutlined, AlignLeftOutlined, LockOutlined} from '@ant-design/icons';
import {Editor} from '@monaco-editor/react';
import '../editor/monaco';
import styles from './SourcePreview.module.css';

function registerTheme(instance) {
    instance.editor.defineTheme('featx-source', {
        base:'vs', inherit:true,
        rules:[
            {token:'',foreground:'334761'},
            {token:'comment',foreground:'64748B',fontStyle:'italic'},
            {token:'keyword',foreground:'3F65AF'},
            {token:'string',foreground:'287969'},
            {token:'number',foreground:'87569F'},
            {token:'tag',foreground:'3D6C9F'},
            {token:'attribute.name',foreground:'706092'},
            {token:'attribute.value',foreground:'287969'},
            {token:'delimiter',foreground:'5B6E87'},
        ],
        colors:{
            'editor.background':'#FBFDFF',
            'editor.foreground':'#334761',
            'editorGutter.background':'#FBFDFF',
            'editorLineNumber.foreground':'#74859C',
            'editorLineNumber.activeForeground':'#3F628F',
            'editor.selectionBackground':'#DBEAFC',
            'editor.inactiveSelectionBackground':'#EAF1FA',
            'editor.lineHighlightBackground':'#F2F7FD',
            'editor.lineHighlightBorder':'#00000000',
            'editorCursor.foreground':'#538FE2',
            'editorIndentGuide.background1':'#EAF0F8',
            'editorIndentGuide.activeBackground1':'#CADBF0',
            'editorWidget.background':'#FFFFFF',
            'editorWidget.border':'#DDE8F5',
            'editor.findMatchBackground':'#D5E7FC',
            'editor.findMatchHighlightBackground':'#E9F1FC',
            'focusBorder':'#A7C9F3',
            'scrollbarSlider.background':'#BACBDD55',
            'scrollbarSlider.hoverBackground':'#A3BBD488',
            'scrollbarSlider.activeBackground':'#8BAACAAA',
        },
    });
}

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
            <Editor path={`source/${file.projectId}/${file.path}`} value={file.content} beforeMount={registerTheme} theme="featx-source"
                loading={<div className={styles.loading}>正在打开源码…</div>}
                onMount={instance => {editor.current = instance; setReady(true);}}
                options={{readOnly:true,domReadOnly:true,automaticLayout:true,minimap:{enabled:false},fontSize:13,lineHeight:22,
                    fontFamily:"'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
                    scrollBeyondLastLine:false,padding:{top:16,bottom:16},wordWrap:wrap ? 'on' : 'off',
                    glyphMargin:false,lineNumbersMinChars:3,lineDecorationsWidth:12,renderLineHighlight:'none',
                    overviewRulerLanes:0,hideCursorInOverviewRuler:true,stickyScroll:{enabled:false},
                    unicodeHighlight:{ambiguousCharacters:false,nonBasicASCII:false},
                    scrollbar:{verticalScrollbarSize:7,horizontalScrollbarSize:7,useShadows:false},smoothScrolling:true}}/>
        </div>
        <div className={styles.status}><span>UTF-8</span><span>{file.content.split('\n').length.toLocaleString('zh-CN')} 行</span><span className={styles.statusHint}>源文件 · 不会修改代码</span></div>
    </div>;
}
