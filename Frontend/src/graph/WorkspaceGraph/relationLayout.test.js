import {relationLayout} from './relationLayout';
const viewport={width:480,height:650};
const nodes=Array.from({length:12},(_,i)=>({id:String(i),methods:['a','b']}));
const edge=(source,target)=>({source:String(source),target:String(target)});
const heights=new Map(nodes.map((node,i)=>[node.id,180+(i%3)*45]));
function noOverlap(points) {
    const entries=[...points];
    for(let i=0;i<entries.length;i++)for(let j=i+1;j<entries.length;j++) {
        const [aid,a]=entries[i],[bid,b]=entries[j];
        expect(a.x+290<=b.x || b.x+290<=a.x || a.y+heights.get(aid)<=b.y || b.y+heights.get(bid)<=a.y).toBe(true);
    }
}
test('branching groups, cycles, and isolated nodes have stable non-overlapping positions',()=>{
    const edges=[edge(0,1),edge(0,2),edge(1,3),edge(2,3),edge(4,5),edge(5,4),edge(6,7)];
    const points=relationLayout(nodes,edges,viewport,heights);
    expect(points.size).toBe(nodes.length);noOverlap(points);
    expect(points.get('0').y).toBeLessThan(points.get('3').y);
    expect(relationLayout([...nodes].reverse(),[...edges].reverse(),viewport,heights)).toEqual(points);
});
test('long chains fold without overlap; empty graphs are valid',()=>{
    const edges=nodes.slice(1).map((_,i)=>edge(i,i+1));
    const points=relationLayout(nodes,edges,viewport,heights);
    noOverlap(points);
    expect(new Set([...points.values()].map(p=>p.x)).size).toBeGreaterThan(1);
    expect(relationLayout([],[],viewport).size).toBe(0);
});
