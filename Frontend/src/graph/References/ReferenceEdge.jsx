import {memo} from 'react';
import {BaseEdge,getSmoothStepPath} from '@xyflow/react';
import styles from './ReferenceEdge.module.css';

// A short orthogonal detour for distant rows/columns; no global routing solver.
function rounded(points) {
    let path=`M ${points[0][0]} ${points[0][1]}`;
    for(let i=1;i<points.length-1;i++) {
        const a=points[i-1],b=points[i],c=points[i+1];
        const before=Math.hypot(b[0]-a[0],b[1]-a[1]),after=Math.hypot(c[0]-b[0],c[1]-b[1]);
        const r=Math.min(7,before/2,after/2);
        if(!r) {path+=` L ${b[0]} ${b[1]}`;continue;}
        const x=b[0]-(b[0]-a[0])*r/before,y=b[1]-(b[1]-a[1])*r/before;
        const nx=b[0]+(c[0]-b[0])*r/after,ny=b[1]+(c[1]-b[1])*r/after;
        path+=` L ${x} ${y} Q ${b[0]} ${b[1]} ${nx} ${ny}`;
    }
    return path+` L ${points.at(-1)[0]} ${points.at(-1)[1]}`;
}
function ReferenceEdge({id,sourceX,sourceY,targetX,targetY,sourcePosition,targetPosition,markerStart,markerEnd,style,data}) {
    const [normal]=getSmoothStepPath({sourceX,sourceY,targetX,targetY,sourcePosition,targetPosition,borderRadius:8,offset:18});
    const path=Number.isFinite(data?.detourX) ? rounded([[sourceX,sourceY],[sourceX,sourceY-20],
        [data.detourX,sourceY-20],[data.detourX,targetY-20],[targetX,targetY-20],[targetX,targetY]]) : normal;
    const title=(data?.evidence || []).map(item=>`${item.source}:${item.line} → ${item.target}\n${item.statement}`).join('\n\n');
    return <g className={`${styles.edge} ${data?.dropping ? styles.dropping : ''}`} style={{'--reference-duration':`${data?.duration || 2000}ms`}}>
        <title>{title || '文件引用'}</title>
        <BaseEdge id={id} path={path} markerStart={markerStart} markerEnd={markerEnd} style={style} interactionWidth={18}/>
    </g>;
}
export default memo(ReferenceEdge);
