import {useEffect,useRef,useState} from 'react';
import {http,projectPath} from '../api/client';

export const visibleLanguages = data => (data?.items || []).filter(item=>item.percent>=.1).sort((a,b)=>b.percent-a.percent);
export function summaryLanguages(data) {
    const languages=visibleLanguages(data);
    const two=languages[1]?.percent>20 || languages.every(item=>item.percent<20);
    return languages.slice(0,two ? 2 : 1);
}

// Load independently of filtered cards: hiding a card must not prevent its language from being discovered.
export function useLanguageCatalog(projects) {
    const [catalog,setCatalog]=useState({});
    const cache=useRef({});
    const key=JSON.stringify((projects || []).map(project=>project.id).sort());
    useEffect(()=>{
        const ids=JSON.parse(key),controller=new AbortController();
        cache.current=Object.fromEntries(ids.filter(id=>cache.current[id]).map(id=>[id,cache.current[id]]));
        setCatalog({...cache.current});
        const queue=ids.filter(id=>!cache.current[id]);let cursor=0;
        const load=async()=>{
            while(cursor<queue.length && !controller.signal.aborted) {
                const id=queue[cursor++];let entry;
                try {
                    const response=await http.get(`${projectPath(id)}/languages`,{signal:controller.signal});
                    entry={data:response.data,failed:false};
                } catch (_) {
                    if(controller.signal.aborted)return;
                    entry={data:null,failed:true};
                }
                if(controller.signal.aborted)return;
                cache.current[id]=entry;
                setCatalog(current=>({...current,[id]:entry}));
            }
        };
        for(let i=0;i<Math.min(4,queue.length);i++)load();
        return ()=>controller.abort();
    },[key]);
    return catalog;
}
