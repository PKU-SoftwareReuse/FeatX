import {Tag} from 'antd';
import styles from './ProjectTag.module.css';
import {projectTagColors} from './projectTagColors';

export default function ProjectTag({children, className = '', color = 'blue', style, ...props}) {
    const palette = projectTagColors[color] || projectTagColors.blue;
    return <Tag {...props} style={{...style,background:palette.background,color:palette.text}} className={`${styles.tag} ${className}`}><span className={styles.label}>{children}</span></Tag>;
}
