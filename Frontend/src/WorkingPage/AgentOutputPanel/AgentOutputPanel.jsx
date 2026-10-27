import {useEffect, useMemo, useRef, useState} from 'react';
import {Alert, Button} from 'antd';
import {CheckCircleOutlined, ClockCircleOutlined, ExclamationCircleOutlined, FileTextOutlined, LoadingOutlined, DownOutlined, RightOutlined, ReloadOutlined, CodeOutlined} from '@ant-design/icons';
import API from '../../api';
import MarkdownRenderer from '../MarkdownRenderComponent/MarkdownRenderComponent';
import styles from './AgentOutputPanel.module.css';

const labels = {PREPARED:'正在准备任务', RUNNING:'智能体执行中', COMPLETED:'修改已生成，等待审阅', COMMITTED:'修改已提交', FAILED:'执行失败', DISCARDED:'任务已放弃'};
const live = status => status === 'PREPARED' || status === 'RUNNING';
function duration(ms) {
    const seconds = Math.max(0, Math.floor(ms / 1000));
    return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
}
function groupedActivities(activities) {
    const result = [];
    for (const item of activities) {
        const previous = result[result.length - 1];
        if (previous && item.status === 'complete' && previous.status === 'complete' && item.kind === previous.kind && ['search','read'].includes(item.kind)) {
            previous.count += 1;
            previous.title = item.kind === 'search' ? `已完成 ${previous.count} 次检索` : `已完成 ${previous.count} 次文件查看`;
            previous.elapsedMs = (previous.elapsedMs || 0) + (item.elapsedMs || 0);
        } else result.push({...item, count:1});
    }
    return result;
}

function DetailLog({runId, status, visible}) {
    const [cursor, setCursor] = useState(0);
    const [history, setHistory] = useState([]);
    const [page, setPage] = useState(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const [refresh, setRefresh] = useState(0);
    const latest = useRef(null);
    useEffect(() => {
        if (!visible) return;
        let active = true, pending = false;
        setLoading(true);
        latest.current = null;
        const load = async () => {
            if (pending) return;
            pending = true;
            try {
                const value = await API.getAgentLogs(runId, cursor);
                if (!active) return;
                latest.current = value; setPage(value); setError('');
            } catch (err) {
                if (active) setError(err.message || '无法读取详细日志');
            } finally {pending = false; if (active) setLoading(false);}
        };
        load();
        const timer = live(status) ? setInterval(() => {if (!latest.current?.hasMore) load();}, 2000) : null;
        return () => {active = false; if (timer) clearInterval(timer);};
    }, [runId, cursor, status, refresh, visible]);
    return <div className={styles.logs}>
        <header><span>完整命令与输出 · 按页加载</span><Button size="small" type="text" icon={<ReloadOutlined/>} loading={loading} onClick={() => setRefresh(n => n + 1)}>刷新</Button></header>
        {error && <Alert type="error" showIcon message={error}/>}
        <pre aria-label="详细执行日志">{loading && !page ? '正在读取日志…' : page?.text || '暂时没有详细输出。'}</pre>
        <footer><span>日志不自动滚动，避免打断阅读</span><div>
            <Button size="small" disabled={!history.length || loading} onClick={() => {setCursor(history[history.length - 1]); setHistory(values => values.slice(0, -1));}}>上一页</Button>
            <Button size="small" disabled={!page?.hasMore || loading} onClick={() => {setHistory(values => [...values,cursor]);setCursor(page.nextCursor);}}>下一页</Button>
        </div></footer>
    </div>;
}

export default function AgentOutputPanel({runId, snapshot, activities = [], connectionError, onShowGraph, onOpenFile, isVisible = true}) {
    const [expanded, setExpanded] = useState(false);
    const [fullReport, setFullReport] = useState(false);
    const [allFiles, setAllFiles] = useState(false);
    const [logsOpen, setLogsOpen] = useState(false);
    const [now, setNow] = useState(Date.now());
    const status = snapshot?.status;
    const running = live(status);
    useEffect(() => {
        if (!running) return;
        const timer = setInterval(() => setNow(Date.now()), 1000);
        return () => clearInterval(timer);
    }, [running]);
    const records = useMemo(() => groupedActivities(activities), [activities]);
    const active = activities.filter(item => item.status === 'running');
    const current = active[active.length - 1];
    const end = Date.parse(snapshot?.finishedAt || snapshot?.acceptedAt || '') || now;
    const start = Date.parse(snapshot?.startedAt || snapshot?.createdAt || '') || end;
    const files = status === 'COMMITTED' ? snapshot?.committedPaths || snapshot?.changedPaths || [] : status === 'COMPLETED' ? snapshot?.changedPaths || [] : [];
    const report = snapshot?.resultSummary || '';
    const visible = expanded ? records : records.slice(-5);
    return <div className={styles.panel}>
        <section className={`${styles.overview} ${status === 'FAILED' ? styles.failed : ''}`}>
            <div className={styles.statusIcon}>{running ? <LoadingOutlined/> : status === 'FAILED' ? <ExclamationCircleOutlined/> : ['COMPLETED','COMMITTED'].includes(status) ? <CheckCircleOutlined/> : <ClockCircleOutlined/>}</div>
            <div className={styles.overviewText}><h3>{labels[status] || '正在读取任务状态'}</h3>
                <p title={snapshot?.request}>{snapshot?.request || '正在接收执行信息…'}</p>
                <div className={styles.meta}><span>{snapshot?.modelLabel || '智能体任务'}</span>{(snapshot?.startedAt || snapshot?.createdAt) && <span>用时 {duration(end - start)}</span>}</div>
            </div>
        </section>
        {connectionError && <Alert type="warning" showIcon message="状态连接暂时异常，正在等待恢复" description={connectionError}/>}
        {status === 'FAILED' && <Alert type="error" showIcon message="任务未能完成" description={snapshot.failureMessage || '请展开日志查看具体原因'}/>}
        {status === 'DISCARDED' && <Alert type="info" showIcon message="本次任务已放弃" description="可查看执行记录；本次未提交修改的处置以任务日志为准。"/>}
        {running && <div className={styles.current} role="status"><LoadingOutlined/><div><strong>{current?.title || '智能体正在处理任务'}</strong><small>{active.length > 1 ? `另有 ${active.length - 1} 项操作正在执行` : '执行过程完整保留在详细日志中'}</small></div></div>}
        {files.length > 0 && <section className={styles.card}>
            <header><h4>{status === 'COMMITTED' ? '已提交文件' : '涉及文件'} <span>{files.length}</span></h4>{status === 'COMPLETED' && <Button type="link" size="small" onClick={onShowGraph}>前往图谱审阅</Button>}</header>
            {(allFiles ? files : files.slice(0,5)).map(path => <button key={path} className={styles.file} onClick={() => onOpenFile(path)} title={path}><FileTextOutlined/><span>{path}</span><RightOutlined/></button>)}
            {files.length > 5 && <Button type="link" size="small" onClick={() => setAllFiles(value => !value)}>{allFiles ? '收起文件' : `查看全部 ${files.length} 个文件`}</Button>}
        </section>}
        {report && !running && <section className={styles.card}>
            <header><h4>智能体说明</h4><span className={styles.note}>执行结束不等于检查通过</span></header>
            <div className={`${styles.report} ${fullReport ? '' : styles.reportFolded}`}><MarkdownRenderer content={report}/></div>
            <Button type="link" size="small" onClick={() => setFullReport(value => !value)}>{fullReport ? '收起说明' : '查看完整说明'}</Button>
        </section>}
        <section className={styles.card}>
            <header><h4>{running ? '最近活动' : '执行记录'}</h4><span className={styles.note}>{activities.length} 项操作</span></header>
            {!visible.length && <p className={styles.empty}>{running ? '等待执行活动…' : snapshot?.activityVersion ? '本次没有工具活动记录' : '历史任务未生成简短活动，可展开详细日志查看。'}</p>}
            <ol className={styles.activityList}>{visible.map(item => {
                const interrupted = !running && item.status === 'running';
                const warning = item.status === 'warning' || interrupted;
                return <li key={item.id} className={warning ? styles.warning : ''}>
                    <span className={styles.activityIcon}>{warning ? <ExclamationCircleOutlined/> : item.status === 'running' ? <LoadingOutlined/> : <CheckCircleOutlined/>}</span>
                    <div><span>{item.title}</span>{warning && interrupted && <small>未收到这项操作的完成记录</small>}</div>
                    {item.status !== 'running' && item.elapsedMs > 0 && <small className={styles.elapsed}>{duration(item.elapsedMs)}</small>}
                </li>;
            })}</ol>
            {records.length > 5 && <Button type="link" size="small" onClick={() => setExpanded(value => !value)}>{expanded ? '仅显示最近 5 条' : `展开全部活动（${records.length} 条）`}</Button>}
        </section>
        <div className={styles.logSection}>
            <Button type="text" icon={<CodeOutlined/>} onClick={() => setLogsOpen(value => !value)} aria-expanded={logsOpen}>{logsOpen ? '收起详细日志' : '查看详细日志'} {logsOpen ? <DownOutlined/> : <RightOutlined/>}</Button>
            {logsOpen && <DetailLog runId={runId} status={status} visible={isVisible}/>}
        </div>
    </div>;
}
