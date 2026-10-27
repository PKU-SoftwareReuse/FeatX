import {useEffect,useRef,useState} from 'react';
import API from '../../api';
const EMPTY = [];

export default function useFileReferences(projectId,graph,enabled) {
    const [result,setResult]=useState(null);
    const serial=useRef(0);
    const retained=useRef(null);
    useEffect(()=>{
        const id=++serial.current,controller=new AbortController();
        if(!enabled) return ()=>controller.abort();
        if(retained.current && retained.current.projectId===projectId && retained.current.graph===graph) {
            setResult(retained.current);return ()=>controller.abort();
        }
        setResult(null);
        if(!projectId || (graph?.nodes?.length||0)<2) return ()=>controller.abort();
        API.getGraphReferences(projectId,graph.nodes.map(node=>node.funcFile || String(node.id)),controller.signal)
            .then(data=>{if(serial.current===id && !controller.signal.aborted && data.projectId===projectId) {
                retained.current={projectId,graph,edges:data.edges || []};
                setResult(retained.current);
            }})
            .catch(()=>{}); // References are optional; never block the nodes or agent.
        return ()=>controller.abort();
    },[projectId,graph,enabled]);
    return enabled && result && result.projectId===projectId && result.graph===graph ? result.edges : EMPTY;
}
