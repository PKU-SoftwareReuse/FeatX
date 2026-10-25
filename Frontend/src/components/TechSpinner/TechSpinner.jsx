import styles from './TechSpinner.module.css';

export default function TechSpinner({className = '', style}) {
    return <span className={`${styles.loader} ${className}`} style={style} aria-hidden="true">
        <svg viewBox="0 0 36 36" fill="none">
            <rect className={styles.tile} x="5" y="5" width="11" height="11" rx="3.5" fill="#4f8fe7"/>
            <rect className={styles.tile} style={{animationDelay:'.18s'}} x="20" y="5" width="11" height="11" rx="3.5" fill="#75a8e9"/>
            <rect className={styles.tile} style={{animationDelay:'.54s'}} x="5" y="20" width="11" height="11" rx="3.5" fill="#60a5cc"/>
            <rect className={styles.tile} style={{animationDelay:'.36s'}} x="20" y="20" width="11" height="11" rx="3.5" fill="#6687db"/>
        </svg>
    </span>;
}

export const techSpinConfig = {indicator:<TechSpinner/>, className:styles.spin};
