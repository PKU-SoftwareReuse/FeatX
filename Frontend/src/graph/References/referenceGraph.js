import {fileGrid} from '../WorkspaceGraph/gridLayout';
import {relationLayout} from '../WorkspaceGraph/relationLayout';

const order = (a,b) => a < b ? -1 : a > b ? 1 : 0;
export function mergeReferences(candidates, ids) {
    const pairs = new Map();
    for (const edge of candidates || []) {
        if (!ids.has(edge.source) || !ids.has(edge.target) || edge.source === edge.target) continue;
        const [source,target] = [edge.source,edge.target].sort(order);
        const id = `reference:${JSON.stringify([source,target])}`;
        const pair = pairs.get(id) || {id,source,target,forward:false,backward:false,rank:0,evidence:[]};
        pair.forward ||= edge.source === source;
        pair.backward ||= edge.source !== source;
        pair.rank = Math.max(pair.rank, edge.rank || 1);
        pair.evidence.push(...(edge.evidence || []).slice(0,3).map(item=>({...item,target:edge.target})));
        pair.evidence = pair.evidence.slice(0,6);
        pairs.set(id,pair);
    }
    return [...pairs.values()].map(edge=>({...edge,evidence:edge.evidence.sort((a,b)=>order(a.source,b.source)||a.line-b.line||order(a.statement,b.statement))})).sort((a,b)=>order(a.id,b.id));
}

function cost(edge, points) {
    const a = points.get(edge.source) || {x:0,y:0}, b = points.get(edge.target) || {x:0,y:0};
    const ax=a.x+145, ay=a.y+140, bx=b.x+145, by=b.y+140;
    const dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy;
    let crossings=0;
    for (const [id,p] of points) {
        if(id===edge.source || id===edge.target || !length) continue;
        const x=p.x+145,y=p.y+140,t=((x-ax)*dx+(y-ay)*dy)/length;
        if(t>0 && t<1 && Math.hypot(x-ax-t*dx,y-ay-t*dy)<120) crossings++;
    }
    return Math.hypot(dx,dy)+crossings*400;
}

export function selectReferences(candidates, nodes, points) {
    const ids = new Set(nodes.map(node=>String(node.id)));
    // A bounded shortlist protects the UI even for unusually dense source references.
    const pairs = mergeReferences(candidates,ids).sort((a,b)=>b.rank-a.rank || order(a.id,b.id)).slice(0,1200);
    const costs = new Map(pairs.map(edge=>[edge.id,cost(edge,points)]));
    const degrees = new Map(), selected = [];
    const limit = Math.min(40,Math.ceil(nodes.length*1.2));
    while (selected.length<limit && pairs.length) {
        let best=-1;
        const coverage = edge => Number(!degrees.get(edge.source))+Number(!degrees.get(edge.target));
        const compare = (a,b) => b.rank-a.rank || coverage(b)-coverage(a) || costs.get(a.id)-costs.get(b.id) || order(a.id,b.id);
        for(let i=0;i<pairs.length;i++) {
            const edge=pairs[i];
            if((degrees.get(edge.source)||0)>=4 || (degrees.get(edge.target)||0)>=4) continue;
            if(best<0 || compare(edge,pairs[best])<0) best=i;
        }
        if(best<0) break;
        const edge=pairs.splice(best,1)[0];selected.push(edge);
        degrees.set(edge.source,(degrees.get(edge.source)||0)+1);
        degrees.set(edge.target,(degrees.get(edge.target)||0)+1);
    }
    return selected;
}

export function focusReferences(selected, candidates, nodes, points, focus) {
    if(!focus) return selected;
    const related=mergeReferences(candidates,new Set(nodes.map(n=>String(n.id))))
        .filter(e=>e.source===focus || e.target===focus).map(edge=>({...edge,cost:cost(edge,points)}))
        .sort((a,b)=>b.rank-a.rank || a.cost-b.cost || order(a.id,b.id)).slice(0,8);
    return [...selected.filter(e=>e.source!==focus && e.target!==focus),...related];
}

export function extendPositions(nodes, initial, viewport, edges=[]) {
    const points=new Map(initial), columns=fileGrid(nodes,viewport).columns;
    let slot=0;
    for(const node of nodes) {
        const id=String(node.id);
        if(points.has(id)) continue;
        const neighbors=edges.filter(edge=>edge.source===id || edge.target===id)
            .map(edge=>points.get(edge.source===id ? edge.target : edge.source)).filter(Boolean);
        const near=neighbors.flatMap(anchor=>[{x:anchor.x,y:anchor.y+330},{x:anchor.x+370,y:anchor.y},
            {x:anchor.x-370,y:anchor.y},{x:anchor.x,y:anchor.y-330}]);
        const free=near.filter(p=>![...points.values()].some(other=>Math.abs(other.x-p.x)<330&&Math.abs(other.y-p.y)<300));
        const distance=p=>neighbors.reduce((sum,anchor)=>sum+Math.hypot(p.x-anchor.x,p.y-anchor.y),0);
        free.sort((a,b)=>distance(a)-distance(b)||a.y-b.y||a.x-b.x);
        if(free.length){points.set(id,free[0]);continue;}
        let point,collision;
        do {
            point={x:(slot%columns)*370,y:Math.floor(slot/columns)*330};slot++;
            collision=false;
            for(const occupied of points.values()) {
                if(Math.abs(occupied.x-point.x)<330 && Math.abs(occupied.y-point.y)<290) {collision=true;break;}
            }
        } while(collision);
        points.set(id,point);
    }
    return points;
}

export function referenceSnapshots(data, viewport, initialPoints) {
    const initial=relationLayout(data.stages.initial.nodes,data.stages.initial.edges,viewport);
    for(const [id,point] of initialPoints) if(initial.has(id)) initial.set(id,point);
    const layouts={initial,expanded:extendPositions(data.stages.expanded.nodes,initial,viewport,data.stages.expanded.edges),
        pruned:relationLayout(data.stages.pruned.nodes,data.stages.pruned.edges,viewport)};
    return {
        referenceLayouts:Object.fromEntries(Object.entries(layouts).map(([stage,points])=>[stage,[...points]])),
        referenceDisplay:Object.fromEntries(Object.entries(layouts).map(([stage,points])=>
            [stage,selectReferences(data.stages[stage].edges,data.stages[stage].nodes,points)])),
    };
}

export function freezeReferences(data, candidates, partial=false) {
    const stages=Object.fromEntries(Object.entries(data.stages).map(([name,graph])=>{
        const ids=new Set(graph.nodes.map(node=>String(node.id)));
        return [name,{...graph,edges:candidates.filter(edge=>ids.has(edge.source)&&ids.has(edge.target))}];
    }));
    return {...data,stages,referenceCandidates:candidates,referencesLocked:true,referencesPartial:partial};
}
