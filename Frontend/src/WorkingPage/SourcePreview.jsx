import {useEffect, useRef} from 'react';
import {Editor, loader} from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
loader.config({monaco});
export default function SourcePreview({file}) {
    const editor = useRef(null);
    useEffect(() => {editor.current?.revealLine(1);}, [file.path]);
    return <div style={{height:'100%',minHeight:240,display:'flex',flexDirection:'column'}}>
        <div style={{padding:'9px 12px',borderBottom:'1px solid #edf2f8',fontSize:12,color:'#6d819c',overflowWrap:'anywhere'}}>{file.path} · 只读</div>
        <div style={{flex:1,minHeight:0}}><Editor path={`source/${file.projectId}/${file.path}`} value={file.content} onMount={instance => {editor.current = instance;}}
            options={{readOnly:true,domReadOnly:true,automaticLayout:true,minimap:{enabled:false},fontSize:12,scrollBeyondLastLine:false,padding:{top:12},wordWrap:'off'}}/></div>
    </div>;
}
