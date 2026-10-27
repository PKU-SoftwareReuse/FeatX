import {useCallback, useEffect, useRef, useState} from 'react';
import {Button, Modal, Slider, message} from 'antd';
import {UploadOutlined, CheckOutlined, DownOutlined, UpOutlined} from '@ant-design/icons';
import {builtinIcon, fallbackIcon, githubOwner, projectIcon, projectIcons} from '../../utils/projectIcon';
import styles from './ProjectIconPicker.module.css';

export default function ProjectIconPicker({project, value, onChange, disabled, onCroppingChange}) {
    const input = useRef(null), canvas = useRef(null), generation = useRef(0);
    const [image, setImage] = useState(null);
    const [expanded, setExpanded] = useState(false);
    const [loading, setLoading] = useState(false);
    const [zoom, setZoom] = useState(1), [x, setX] = useState(0), [y, setY] = useState(0);
    const owner = project && githubOwner(project);
    useEffect(() => {onCroppingChange?.(Boolean(image) || loading);}, [image, loading, onCroppingChange]);
    useEffect(() => () => {generation.current++;}, []);
    useEffect(() => {generation.current++;setImage(null);setLoading(false);setExpanded(false);}, [project?.id]);
    const paint = useCallback(() => {
        if (!image || !canvas.current) return;
        const context = canvas.current.getContext('2d');
        const side = Math.min(image.width, image.height) / zoom;
        context.fillStyle = '#fff'; context.fillRect(0, 0, 256, 256);
        context.drawImage(image, (image.width-side)*(x+100)/200, (image.height-side)*(y+100)/200, side, side, 0, 0, 256, 256);
    }, [image, zoom, x, y]);
    useEffect(paint, [paint]);
    const readImage = async event => {
        const file = event.target.files?.[0]; event.target.value = '';
        if (!file) return;
        if (!['image/png','image/jpeg','image/webp'].includes(file.type) || file.size > 10*1024*1024) {
            message.warning('请选择 10MB 以内的 PNG、JPG 或 WebP 图片'); return;
        }
        const request = ++generation.current, url = URL.createObjectURL(file);
        setLoading(true);
        try {
            const next = new Image(); next.src = url; await next.decode();
            if (request !== generation.current) return;
            if (next.width * next.height > 40000000) throw new Error('图片尺寸过大，请选择较小的图片');
            setZoom(1);setX(0);setY(0);setImage(next);
        } catch (error) {if (request === generation.current) message.error(error.message || '无法读取图片');}
        finally {URL.revokeObjectURL(url);if (request === generation.current) setLoading(false);}
    };
    const applyCrop = () => {
        let quality = .86, dataUrl = canvas.current.toDataURL('image/jpeg', quality);
        while (dataUrl.length > 130000 && quality > .4) {
            quality -= .1; dataUrl = canvas.current.toDataURL('image/jpeg', quality);
        }
        if (dataUrl.length > 180000) {message.error('图片压缩失败，请更换图片');return;}
        onChange({kind:'upload',dataUrl});setImage(null);setExpanded(false);
    };
    if (!project) return null;
    return <div className={styles.picker}>
        <div className={styles.current}>
            <img src={projectIcon(project,value)} alt="当前项目图标" onError={event => {const url=fallbackIcon(project.id);if (!event.currentTarget.src.endsWith(url)) event.currentTarget.src=url;}}/>
            <Button className={styles.changeButton} disabled={disabled || loading} aria-expanded={expanded} onClick={() => setExpanded(open => !open)}>
                {expanded ? '收起选项' : '更换图标'}{expanded ? <UpOutlined/> : <DownOutlined/>}
            </Button>
        </div>
        <input ref={input} className={styles.fileInput} style={{display:'none'}} type="file" accept="image/png,image/jpeg,image/webp" hidden tabIndex={-1} onChange={readImage}/>
        {expanded && <div className={styles.options}>
            <div className={styles.optionsHeader}><span>选择内置图标</span>
                <Button size="small" icon={<UploadOutlined/>} disabled={disabled} loading={loading} onClick={() => input.current.click()}>上传图片</Button>
            </div>
            <div className={styles.grid} aria-label="内置项目图标">
                {projectIcons.map((name,index) => <button key={name} type="button" disabled={disabled} aria-label={`项目图标 ${index+1}`} aria-pressed={value?.kind==='builtin' && value.index===index} className={value?.kind==='builtin' && value.index===index ? styles.selected : ''} onClick={() => {onChange({kind:'builtin',index});setExpanded(false);}}>
                    <img src={builtinIcon(index)} alt=""/>{value?.kind==='builtin' && value.index===index && <CheckOutlined/>}
                </button>)}
            </div>
            <div className={styles.optionsFooter}>
                <span>上传图片支持正方形裁剪与自动压缩</span>
                {owner && <Button type="link" size="small" disabled={disabled} onClick={() => {onChange({kind:'github',owner});setExpanded(false);}}>使用 GitHub 头像</Button>}
            </div>
        </div>}
        <Modal title="裁剪项目图标" open={Boolean(image)} afterOpenChange={open => {if (open) paint();}} onCancel={() => setImage(null)} onOk={applyCrop} okText="使用此图" cancelText="取消" width={400} centered destroyOnClose>
            <div className={styles.crop}><canvas ref={canvas} width={256} height={256} aria-label="正方形裁剪预览"/>
                <label>缩放<Slider aria-label="缩放" min={1} max={4} step={.01} value={zoom} onChange={setZoom}/></label>
                <label>水平位置<Slider aria-label="水平位置" min={-100} max={100} value={x} onChange={setX}/></label>
                <label>垂直位置<Slider aria-label="垂直位置" min={-100} max={100} value={y} onChange={setY}/></label>
            </div>
        </Modal>
    </div>;
}
