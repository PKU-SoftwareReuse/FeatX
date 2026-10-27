import {memo} from 'react';
import {BaseEdge,getSmoothStepPath,getBezierPath} from '@xyflow/react';
import styles from './ReferenceEdge.module.css';

function ReferenceEdge({id,sourceX,sourceY,targetX,targetY,sourcePosition,targetPosition,markerStart,markerEnd,style,data}) {
    const [normal]=getSmoothStepPath({sourceX,sourceY,targetX,targetY,sourcePosition,targetPosition,borderRadius:8,offset:18});
    const [curve]=getBezierPath({sourceX,sourceY,targetX,targetY,sourcePosition,targetPosition,curvature:.18});
    // Local arcs stay close to their endpoints; never route along the whole graph boundary.
    let path=data?.distant ? curve : normal;
    if(data?.localArc==='right') {
        const x=Math.max(sourceX,targetX)+64;
        path=`M ${sourceX} ${sourceY} C ${x} ${sourceY} ${x} ${targetY} ${targetX} ${targetY}`;
    } else if(data?.localArc==='bottom') {
        const y=Math.max(sourceY,targetY)+56;
        path=`M ${sourceX} ${sourceY} C ${sourceX} ${y} ${targetX} ${y} ${targetX} ${targetY}`;
    }
    const title=(data?.evidence || []).map(item=>`${item.source}:${item.line} → ${item.target}\n${item.statement}`).join('\n\n');
    return <g className={`${styles.edge} ${data?.dropping ? styles.dropping : ''}`} style={{'--reference-duration':`${data?.duration || 2000}ms`}}>
        <title>{title || '文件引用'}</title>
        <BaseEdge id={id} path={path} markerStart={markerStart} markerEnd={markerEnd} style={style} interactionWidth={18}/>
    </g>;
}
export default memo(ReferenceEdge);
