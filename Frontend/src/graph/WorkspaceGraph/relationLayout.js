import {fileGrid} from './gridLayout';

const WIDTH=290, STEP=350, ROW_GAP=56, GROUP_GAP=74;
const compare=(a,b)=>a<b ? -1 : a>b ? 1 : 0;
const heightOf=(node,heights)=>heights.get(String(node.id)) || 150+Math.min(5,(node.methods||[]).length)*22+((node.methods||[]).length>5 ? 20 : 0);

// Condense cycles before assigning levels. These are display groups, not new graph nodes.
function levelsFor(ids,next,previous) {
    let index=0;
    const indices=new Map(),low=new Map(),stack=[],active=new Set(),components=[];
    const visit=id=>{
        indices.set(id,index);low.set(id,index++);stack.push(id);active.add(id);
        for(const target of next.get(id)) {
            if(!indices.has(target)) {visit(target);low.set(id,Math.min(low.get(id),low.get(target)));}
            else if(active.has(target)) low.set(id,Math.min(low.get(id),indices.get(target)));
        }
        if(low.get(id)===indices.get(id)) {
            const members=[];let item;
            do {item=stack.pop();active.delete(item);members.push(item);} while(item!==id);
            components.push(members.sort(compare));
        }
    };
    ids.forEach(id=>{if(!indices.has(id))visit(id);});
    const group=new Map();components.forEach((members,i)=>members.forEach(id=>group.set(id,i)));
    const incoming=components.map(()=>0),outgoing=components.map(()=>new Set()),depth=components.map(()=>0);
    ids.forEach(id=>next.get(id).forEach(target=>{
        const a=group.get(id),b=group.get(target);
        if(a!==b && !outgoing[a].has(b)) {outgoing[a].add(b);incoming[b]++;}
    }));
    const queue=components.map((_,i)=>i).filter(i=>incoming[i]===0);
    for(let cursor=0;cursor<queue.length;cursor++) for(const target of outgoing[queue[cursor]]) {
        depth[target]=Math.max(depth[target],depth[queue[cursor]]+1);
        if(--incoming[target]===0)queue.push(target);
    }
    const layers=[];
    components.forEach((members,i)=>{layers[depth[i]] ||= [];layers[depth[i]].push(...members);});
    const rank=new Map();
    // A bounded forward ordering pass brings children near their parents.
    layers.forEach(layer=>{
        const center=id=>{const values=[...previous.get(id)].filter(p=>rank.has(p)).map(p=>rank.get(p));return values.length ? values.reduce((a,b)=>a+b,0)/values.length : Infinity;};
        layer.sort((a,b)=>{const ca=center(a),cb=center(b);return (ca===cb ? 0 : ca-cb)||compare(a,b);});
        layer.forEach((id,i)=>rank.set(id,i));
    });
    return layers;
}

function blockFor(layers,columns,nodeMap,heights) {
    const count=layers.reduce((sum,layer)=>sum+layer.length,0),points=new Map();
    // Fold long narrow chains into a stable serpentine layout instead of an unreadable tower.
    if(layers.length>5 && layers.every(layer=>layer.length<=2)) {
        const span=Math.min(columns,Math.ceil(count/4)),rows=Math.ceil(count/span);
        const ids=layers.flat(),rowHeights=Array.from({length:rows},()=>0);
        ids.forEach((id,i)=>{const column=Math.floor(i/rows),row=column%2 ? rows-1-i%rows : i%rows;rowHeights[row]=Math.max(rowHeights[row],heightOf(nodeMap.get(id),heights));});
        let y=0;const offsets=rowHeights.map(h=>{const top=y;y+=h+ROW_GAP;return top;});
        ids.forEach((id,i)=>{const column=Math.floor(i/rows),row=column%2 ? rows-1-i%rows : i%rows;points.set(id,{x:column*STEP,y:offsets[row]});});
        return {points,span,height:y-ROW_GAP};
    }
    const rows=layers.flatMap(layer=>Array.from({length:Math.ceil(layer.length/columns)},(_,i)=>layer.slice(i*columns,(i+1)*columns)));
    const span=Math.max(...rows.map(row=>row.length));let y=0;
    rows.forEach(row=>{
        const offset=(span-row.length)*STEP/2;
        row.forEach((id,i)=>points.set(id,{x:offset+i*STEP,y}));
        y+=Math.max(...row.map(id=>heightOf(nodeMap.get(id),heights)))+ROW_GAP;
    });
    return {points,span,height:y-ROW_GAP};
}

export function relationLayout(nodes,edges,viewport,heights=new Map()) {
    if(nodes.length<2 || nodes.length>180 || !edges?.length) return fileGrid(nodes,viewport,heights).positions;
    const nodeMap=new Map(nodes.map(node=>[String(node.id),node])),ids=[...nodeMap.keys()].sort(compare);
    const next=new Map(ids.map(id=>[id,new Set()])),previous=new Map(ids.map(id=>[id,new Set()]));
    for(const edge of edges.slice(0,20000)) {
        if(edge.source===edge.target || !nodeMap.has(edge.source)||!nodeMap.has(edge.target))continue;
        next.get(edge.source).add(edge.target);previous.get(edge.target).add(edge.source);
    }
    const seen=new Set(),groups=[];
    for(const id of ids) {
        if(seen.has(id))continue;
        const group=[id];seen.add(id);
        for(let i=0;i<group.length;i++) for(const target of [...next.get(group[i]),...previous.get(group[i])]) {
            if(!seen.has(target)){seen.add(target);group.push(target);}
        }
        groups.push(group.sort(compare));
    }
    groups.sort((a,b)=>b.length-a.length||compare(a[0],b[0]));
    const layers=groups.map(group=>levelsFor(group,next,previous));
    const width=Math.max(1,viewport.width||900),height=Math.max(1,viewport.height||600);
    const maxColumns=Math.min(8,nodes.length,Math.max(2,Math.ceil(Math.sqrt(nodes.length*width/height))+2));
    let best=null;
    for(let columns=1;columns<=maxColumns;columns++) {
        const skyline=Array.from({length:columns},()=>0),points=new Map();
        for(const layer of layers) {
            const block=blockFor(layer,columns,nodeMap,heights);
            let slot=0,top=Infinity;
            for(let start=0;start<=columns-block.span;start++) {
                const y=Math.max(...skyline.slice(start,start+block.span));
                if(y<top){top=y;slot=start;}
            }
            for(const [id,p] of block.points)points.set(id,{x:slot*(STEP+14)+p.x,y:top+p.y});
            for(let column=slot;column<slot+block.span;column++)skyline[column]=top+block.height+GROUP_GAP;
        }
        const w=Math.max(...[...points.values()].map(p=>p.x+WIDTH));
        const h=Math.max(...[...points.entries()].map(([id,p])=>p.y+heightOf(nodeMap.get(id),heights)));
        const zoom=Math.min(1,width*.9/w,height*.9/h);
        const score=zoom*(.92+.08*w*zoom/width);
        if(!best || score>best.score)best={score,points};
    }
    return best.points;
}
