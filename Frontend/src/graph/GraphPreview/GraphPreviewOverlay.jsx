import {useEffect, useRef} from 'react';
import styles from './GraphPreviewOverlay.module.css';

const titles = {retrieving:'检索相关功能',expanding:'扩展关联文件与元素',expanded:'候选图谱已展开',reversing:'开始筛选代码元素',pruning:'裁剪相关度较低的元素',final:'最终关联图谱',transition:'即将展示智能体输出'};
export default function GraphPreviewOverlay({preview}) {
    const root = useRef(null);
    const canvas = useRef(null);
    const settings = useRef(preview);
    settings.current = preview;
    useEffect(() => {
        const element = root.current;
        const surface = canvas.current;
        const context = surface.getContext('2d');
        if (!context) return;
        let frame, width = 0, height = 0, last = 0, angle = 0, travel = 0, velocity = 1;
        let particles = [], burstId = null;
        const captureParticles = () => {
            const bounds = element.getBoundingClientRect();
            const targets = [...element.parentElement.querySelectorAll('[data-prune-target]')]
                .map(target => target.getBoundingClientRect())
                .filter(rect => rect.right > bounds.left && rect.left < bounds.right && rect.bottom > bounds.top && rect.top < bounds.bottom);
            const quota = Math.max(1, Math.floor(1200 / Math.max(1, targets.length)));
            particles = targets.flatMap(rect => {
                const left = Math.max(rect.left, bounds.left) - bounds.left;
                const top = Math.max(rect.top, bounds.top) - bounds.top;
                const w = Math.min(rect.right, bounds.right) - bounds.left - left;
                const h = Math.min(rect.bottom, bounds.bottom) - bounds.top - top;
                const count = Math.min(quota, Math.max(12, Math.ceil(w * h / 85)));
                return Array.from({length:count}, () => {
                    const x = Math.random() * w, y = Math.random() * h;
                    return {x:left+x,y:top+y,delay:.06+(1-x/Math.max(w,1))*.42,
                        dx:25+Math.random()*65,dy:-15-Math.random()*55,
                        size:.8+Math.random()*1.8,hue:205+Math.random()*45};
                });
            });
        };
        const measure = () => {
            width = element.clientWidth; height = element.clientHeight;
            const ratio = Math.min(window.devicePixelRatio || 1, 2);
            surface.width = Math.round(width * ratio); surface.height = Math.round(height * ratio);
            context.setTransform(ratio,0,0,ratio,0,0);
        };
        const observer = new ResizeObserver(measure); observer.observe(element); measure();
        const draw = now => {
            frame = requestAnimationFrame(draw);
            if (last && now - last < 33) return;
            const delta = Math.min((now - (last || now)) / 1000, .08); last = now;
            const config = settings.current;
            velocity += ((config.direction || 1) - velocity) * Math.min(1, delta * 8);
            if (!config.reduced) {angle += velocity * delta * 90; travel += velocity * delta * 1.8;}
            element.style.setProperty('--preview-angle', `${angle}deg`);
            context.clearRect(0,0,width,height);
            if (config.reduced) return;
            if (config.phase === 'pruning' && burstId !== config.id) {
                burstId = config.id;
                captureParticles();
            }
            // A translucent moving color wash, with readable file cards beneath it.
            const wash = context.createLinearGradient(0, 0, width, height);
            wash.addColorStop(0, `hsla(${200 + Math.sin(travel / 2) * 35},90%,65%,.045)`);
            wash.addColorStop(.5, 'rgba(158,116,255,.065)');
            wash.addColorStop(1, 'rgba(255,167,112,.045)');
            context.fillStyle = wash;
            context.fillRect(0, 0, width, height);
            const gap = Math.max(20, Math.sqrt(width * height / 1400));
            for (let x=gap/2; x<width; x+=gap) for (let y=gap/2; y<height; y+=gap) {
                const wave = (Math.sin(x / 115 + y / 160 - travel) + 1) / 2;
                const hue = 190 + (x / Math.max(width,1)) * 260 + (y / Math.max(height,1)) * 70 + Math.sin(travel / 2) * 25;
                context.fillStyle = `hsla(${hue},85%,52%,${.045 + wave * .115})`;
                context.beginPath(); context.arc(x,y + Math.sin(x / 140 - travel) * 4, 1 + wave * 2.2,0,Math.PI*2);context.fill();
            }
            if (config.phase === 'pruning') {
                const progress = (now - config.phaseStartedAt) / Math.max(config.phaseDuration, 1);
                for (const particle of particles) {
                    const life = (progress - particle.delay) / .48;
                    if (life < 0 || life >= 1) continue;
                    const alpha = Math.min(1, life * 12) * (1 - life) * .72;
                    context.fillStyle = `hsla(${particle.hue},55%,56%,${alpha})`;
                    const size = particle.size * (1 - life * .6);
                    context.fillRect(particle.x + particle.dx * life,
                        particle.y + particle.dy * life + Math.sin(life * 6) * 3, size, size);
                }
            }
        };
        frame = requestAnimationFrame(draw);
        return () => {cancelAnimationFrame(frame);observer.disconnect();};
    }, [preview.id]);
    const data = preview.data;
    const stage = data?.stages[['final','transition'].includes(preview.phase) ? 'pruned' : preview.phase === 'retrieving' ? 'initial' : 'expanded'];
    return <div ref={root} data-preview-phase={preview.phase} data-preview-direction={preview.direction} className={`${styles.overlay} ${preview.phase === 'transition' && !preview.replaying ? styles.leaving : ''}`} aria-live="polite">
        <canvas ref={canvas} className={styles.dots} aria-hidden="true"/>
        <div className={styles.caption}>
            <span className={styles.signal}/><div><strong>{preview.replaying ? preview.phase === 'transition' ? '回看完成' : `回看 · ${titles[preview.phase]}` : titles[preview.phase]}</strong><small>
                {stage ? `${stage.fileCount} 个文件 · ${stage.elementCount} 个元素${data.relatedFeatureCount ? ` · ${data.relatedFeatureCount} 个相关功能` : ''}` : '基于现有功能描述进行本地匹配'}
            </small></div>
        </div>
        {data?.notices?.length > 0 && <div className={styles.notice}>{data.notices.join(' ')}</div>}
    </div>;
}
