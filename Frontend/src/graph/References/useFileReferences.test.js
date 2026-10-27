import {act,renderHook,waitFor} from '@testing-library/react';
import useFileReferences from './useFileReferences';
import API from '../../api';
jest.mock('../../api',()=>({getGraphReferences:jest.fn()}));
const graph={nodes:[{id:'a.js'},{id:'b.js'}]};
beforeEach(()=>API.getGraphReferences.mockReset());

test('a late response from another project cannot enter the current graph',async()=>{
    let old;
    API.getGraphReferences.mockImplementation(id=>id==='old' ? new Promise(resolve=>{old=resolve;}) : Promise.resolve({projectId:'new',edges:[{id:'new'}]}));
    const {result,rerender}=renderHook(({id})=>useFileReferences(id,graph,true),{initialProps:{id:'old'}});
    rerender({id:'new'});
    await waitFor(()=>expect(result.current).toEqual([{id:'new'}]));
    await act(async()=>old({projectId:'old',edges:[{id:'old'}]}));
    expect(result.current).toEqual([{id:'new'}]);
});

test('returning from replay reuses the same graph; changed graph refreshes references',async()=>{
    API.getGraphReferences.mockResolvedValue({projectId:'p',edges:[{id:'edge'}]});
    const {result,rerender}=renderHook(({enabled,value})=>useFileReferences('p',value,enabled),{initialProps:{enabled:true,value:graph}});
    await waitFor(()=>expect(result.current).toHaveLength(1));
    rerender({enabled:false,value:graph});
    expect(result.current).toEqual([]);
    rerender({enabled:true,value:graph});
    expect(API.getGraphReferences).toHaveBeenCalledTimes(1);
    rerender({enabled:true,value:{...graph}});
    await waitFor(()=>expect(result.current).toHaveLength(1));
    expect(API.getGraphReferences).toHaveBeenCalledTimes(2);
});
