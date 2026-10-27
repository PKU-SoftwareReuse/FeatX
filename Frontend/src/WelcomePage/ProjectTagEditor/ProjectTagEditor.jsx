import {Popover, Select, message} from 'antd';
import styles from './ProjectTagEditor.module.css';
import ProjectTag from '../../components/ProjectTag';
import {projectTagColors} from '../../components/projectTagColors';

const colors = ['blue','cyan','green','purple','magenta','orange','gold','red'];
const names = ['蓝色','青色','绿色','紫色','品红','橙色','金色','红色'];
const key = name => name.toLocaleLowerCase();
export function tagCatalog(projects) {
    const tags = new Map();
    for (const project of projects || []) for (const tag of project.tags || []) {
        if (!tags.has(key(tag.name))) tags.set(key(tag.name), tag);
    }
    return [...tags.values()].sort((a,b)=>a.name.localeCompare(b.name,'zh-CN'));
}
export const tagMatches = (tags, selected) => !selected.length || (tags || []).some(tag=>selected.includes(key(tag.name)));
export const tagKey = key;

export default function ProjectTagEditor({value = [], onChange, catalog = [], disabled}) {
    const change = selected => {
        const seen = new Set(), next = [];
        for (const input of selected) {
            const name = input.trim(), normalized = key(name);
            if (!name || seen.has(normalized)) continue;
            if (name.length > 24) {message.warning('标签名称最多 24 个字符');return;}
            seen.add(normalized);
            const known = value.find(tag=>key(tag.name)===normalized) || catalog.find(tag=>key(tag.name)===normalized);
            const hash = Array.from(name).reduce((sum,char)=>sum+char.codePointAt(0),0);
            // Avoid colors already assigned to other names anywhere in the project list.
            const existing = tagCatalog([{tags:[...catalog,...value,...next]}]);
            const usage = colors.map(color => existing.filter(tag=>tag.color===color).length);
            const leastUsed = Math.min(...usage);
            const choices = colors.filter((color,index) => usage[index] === leastUsed);
            next.push(known || {name,color:choices[hash % choices.length]});
        }
        if (next.length > 12) {message.warning('每个项目最多添加 12 个标签');return;}
        onChange(next);
    };
    return <div>
        <Select mode="tags" aria-label="项目标签" disabled={disabled} value={value.map(tag=>tag.name)} onChange={change}
            placeholder="选择已有标签，或输入新标签后回车" allowClear style={{width:'100%'}}
            options={tagCatalog([{tags:[...value,...catalog]}]).map(tag=>({value:tag.name,label:tag.name}))}
            tagRender={({value:name,closable,onClose}) => <ProjectTag color={value.find(tag=>tag.name===name)?.color || 'blue'} closable={closable} onClose={onClose} onMouseDown={event=>{event.preventDefault();event.stopPropagation();}}>{name}</ProjectTag>}/>
        {value.length > 0 && <div className={styles.colors}>
            <span>标签颜色</span>
            {value.map(tag=><Popover key={tag.name} trigger="click" title={`“${tag.name}”在本项目的颜色`} content={<div className={styles.palette}>
                {colors.map((color,index)=><button type="button" key={color} disabled={disabled} aria-label={`设为${names[index]}`} aria-pressed={tag.color===color} style={{background:projectTagColors[color].background,color:projectTagColors[color].text}} onClick={()=>onChange(value.map(item=>item.name===tag.name ? {...item,color} : item))}>{tag.color===color ? '✓' : ''}</button>)}
            </div>}><button type="button" className={styles.colorButton} disabled={disabled} aria-label={`修改标签“${tag.name}”的颜色`}><ProjectTag color={tag.color}>{tag.name}</ProjectTag></button></Popover>)}
        </div>}
    </div>;
}
