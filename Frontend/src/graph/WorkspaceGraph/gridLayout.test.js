import {fileGrid} from './gridLayout';
const nodes = count => Array.from({length:count}, (_, i) => ({id:String(i)}));

test('fits columns to the available aspect ratio, including a narrow source drawer layout', () => {
    const items = nodes(18);
    const wide = fileGrid(items, {width:1400,height:650});
    const narrow = fileGrid(items, {width:500,height:650});
    expect(wide.columns).toBeGreaterThan(3);
    expect(narrow.columns).toBeLessThan(wide.columns);
    expect(wide.positions.size).toBe(18);
    expect(fileGrid(items, {width:1400,height:650})).toEqual(wide);
    for (const [layout, width] of [[wide,1400],[narrow,500]]) {
        const points = [...layout.positions.values()];
        const w = Math.max(...points.map(p=>p.x)) + 290;
        const h = Math.max(...points.map(p=>p.y)) + 280;
        const scale = Math.min(1,width*.9/w,650*.9/h);
        expect(w*scale/width).toBeGreaterThan(.8);
    }
});

test('uses actual row heights without overlaps and handles empty/single graphs', () => {
    expect(fileGrid([], {width:500,height:600}).positions.size).toBe(0);
    expect(fileGrid(nodes(1), {width:500,height:600}).columns).toBe(1);
    const items = nodes(6), heights = new Map(items.map(n=>[n.id,400]));
    const result = fileGrid(items,{width:500,height:1000},heights);
    const points = [...result.positions.values()];
    for (let i=0;i<points.length;i++) for(let j=i+1;j<points.length;j++) {
        if(points[i].x===points[j].x) expect(Math.abs(points[i].y-points[j].y)).toBeGreaterThanOrEqual(436);
    }
});
