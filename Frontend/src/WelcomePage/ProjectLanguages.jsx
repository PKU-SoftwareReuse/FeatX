import {useEffect, useState} from 'react';
import {Popover} from 'antd';
import {http, projectPath} from '../api/client';
import styles from './ProjectLanguages.module.css';

const colors = {JavaScript:'#f1e05a',TypeScript:'#3178c6',Python:'#3572a5',Java:'#b07219',Vue:'#41b883',
    HTML:'#e34c26',CSS:'#563d7c',SCSS:'#c6538c','C++':'#f34b7d',C:'#555555','C#':'#178600',Go:'#00add8',
    Rust:'#dea584',Ruby:'#701516',PHP:'#4f5d95',Kotlin:'#a97bff',Swift:'#f05138',Dart:'#00b4ab',Shell:'#89e051',
    Svelte:'#ff3e00',Scala:'#c22d40',SQL:'#e38c00',Dockerfile:'#384d54',Makefile:'#427819'};
const colorOf = name => colors[name] || `hsl(${Array.from(name).reduce((sum,char)=>sum*31+char.charCodeAt(0),0)%360},50%,45%)`;
const percent = item => item.percent < .1 ? '<0.1%' : `${item.percent.toFixed(1)}%`;
export default function ProjectLanguages({projectId}) {
    const [data,setData] = useState(null), [failed,setFailed] = useState(false);
    useEffect(()=>{
        const controller = new AbortController();setData(null);setFailed(false);
        http.get(`${projectPath(projectId)}/languages`,{signal:controller.signal}).then(response=>setData(response.data))
            .catch(()=>{if(!controller.signal.aborted)setFailed(true);});
        return ()=>controller.abort();
    },[projectId]);
    const visibleLanguages = (data?.items || []).filter(item => item.percent >= 0.1);
    const title = visibleLanguages.length ? <div className={styles.details}>
        <strong>代码语言</strong>
        <div className={styles.bar}>{visibleLanguages.map(item=><span key={item.name} style={{background:colorOf(item.name),flexGrow:item.bytes}}/>)}</div>
        <div className={styles.list}>{visibleLanguages.map(item=><div key={item.name}><i style={{background:colorOf(item.name)}}/><span>{item.name}</span><small>{item.files} 个文件</small><b>{percent(item)}</b></div>)}</div>
        {data.incomplete && <p>部分文件未能读取。</p>}
    </div> : failed ? '语言统计暂不可用' : data ? '未识别到支持统计的源码文件' : '正在统计代码语言';
    return <Popover content={title} overlayClassName={styles.popover} placement="bottomRight" autoAdjustOverflow trigger={['hover','focus']} mouseLeaveDelay={0.15}>
        <button type="button" className={styles.languages} aria-label="查看项目代码语言占比">
            {visibleLanguages.length ? visibleLanguages.slice(0,2).map(item=><span key={item.name}><i style={{background:colorOf(item.name)}}/>{item.name}</span>)
                : <span className={styles.empty}>{failed ? '统计不可用' : data ? '暂无语言信息' : '统计中…'}</span>}
        </button>
    </Popover>;
}
