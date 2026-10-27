import {mergeReferences,selectReferences,focusReferences,freezeReferences,referenceSnapshots} from './referenceGraph';
const nodes=Array.from({length:20},(_,i)=>({id:`f${i}`}));
const points=new Map(nodes.map((node,i)=>[node.id,{x:(i%4)*350,y:Math.floor(i/4)*316}]));
const candidates=nodes.flatMap(a=>nodes.filter(b=>a!==b).map(b=>({id:`${a.id}-${b.id}`,source:a.id,target:b.id,rank:3,
    evidence:[{source:a.id,line:2,statement:`import '${b.id}'`}]})));

test('dense candidates are stable, capped, bidirectionally merged and nonempty',()=>{
    const chosen=selectReferences(candidates,nodes,points);
    expect(chosen.length).toBeGreaterThan(0);expect(chosen.length).toBeLessThanOrEqual(24);
    const degree=new Map();
    chosen.forEach(edge=>{expect(edge.forward&&edge.backward).toBe(true);[edge.source,edge.target].forEach(id=>degree.set(id,(degree.get(id)||0)+1));});
    expect(Math.max(...degree.values())).toBeLessThanOrEqual(4);
    expect(selectReferences([...candidates].reverse(),nodes,points)).toEqual(chosen);
    const focused=focusReferences(chosen,candidates,nodes,points,'f0');
    expect(focused.filter(edge=>edge.source==='f0'||edge.target==='f0')).toHaveLength(8);
});

test('only existing endpoints are used; no evidence means no fabricated edges',()=>{
    expect(selectReferences([],nodes,points)).toEqual([]);
    expect(mergeReferences([{source:'f0',target:'missing'},{source:'f0',target:'f0'}],new Set(['f0']))).toEqual([]);
});

test('preview freezes references per stage and records stable display layouts',()=>{
    const graph=items=>({nodes:items,edges:[]});
    const data={stages:{initial:graph(nodes.slice(0,3)),expanded:graph(nodes),pruned:graph(nodes.slice(0,2))}};
    const frozen=freezeReferences(data,candidates);
    expect(frozen.stages.pruned.edges).toHaveLength(2);
    expect(data.stages.expanded.edges).toHaveLength(0);
    const snapshot=referenceSnapshots(frozen,{width:500,height:650},new Map());
    expect(snapshot.referenceDisplay.pruned).toHaveLength(1);
    expect(snapshot.referenceLayouts.expanded).toHaveLength(20);
    expect(JSON.parse(JSON.stringify(snapshot))).toEqual(snapshot);
});
