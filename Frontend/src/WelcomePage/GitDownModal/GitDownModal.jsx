import {useEffect, useRef, useState} from "react";
import {Alert, Button, Input, message, Modal, Tag} from "antd";
import {ArrowRightOutlined, CheckOutlined, GithubOutlined, LinkOutlined, LoadingOutlined} from "@ant-design/icons";
import API from "../../api";
import welcomeStyles from "../WelcomePage.module.css";
import styles from "./GitDownModal.module.css";

export default function GitDownModal({reloadGetProjectsInfo}) {
    const [visible, setVisible] = useState(false);
    const [url, setUrl] = useState("");
    const [commit, setCommit] = useState("");
    const [name, setName] = useState("");
    const [verified, setVerified] = useState(null);
    const [phase, setPhase] = useState("idle");
    const [error, setError] = useState("");
    const [elapsed, setElapsed] = useState(0);
    const generation = useRef(0);
    const busy = useRef(false);
    const nameEdited = useRef(false);
    const urlInput = useRef(null);
    const checking = phase === "checking";
    const importing = phase === "importing";

    useEffect(() => () => {generation.current += 1;}, []);
    useEffect(() => {
        if (!checking && !importing) return;
        const started = Date.now();
        setElapsed(0);
        const timer = setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
        return () => clearInterval(timer);
    }, [checking, importing]);

    function reset() {
        generation.current += 1;
        busy.current = false; nameEdited.current = false;
        setUrl(""); setCommit(""); setName(""); setVerified(null); setError(""); setPhase("idle"); setElapsed(0);
    }
    function close() {
        if (importing) return;
        reset(); setVisible(false);
    }
    function invalidate() {
        generation.current += 1;
        setVerified(null); setError(""); setPhase("idle");
    }

    async function verify() {
        if (busy.current) return;
        if (!url.trim()) {setError("请先粘贴仓库、分支或标签链接。"); urlInput.current?.focus(); return;}
        if (commit.trim() && !/^[a-f\d]{7,64}$/i.test(commit.trim())) {
            setError("Commit ID 应为 7–64 位十六进制字符；不指定版本时可以留空。"); return;
        }
        const token = ++generation.current;
        busy.current = true; setPhase("checking"); setError(""); setVerified(null);
        try {
            const result = await API.gitDownRepo(url.trim(), commit.trim());
            if (token !== generation.current) return;
            setVerified(result);
            if (!nameEdited.current) setName(result.repoName || "");
            setPhase("ready");
        } catch (err) {
            if (token === generation.current) {setError(err.message || "仓库连接失败，请检查链接后重试。"); setPhase("idle");}
        } finally {
            if (token === generation.current) busy.current = false;
        }
    }

    async function importProject() {
        if (busy.current || !verified || !name.trim()) return;
        busy.current = true; setPhase("importing"); setError("");
        try {
            const project = await API.gitRepo(url.trim(), commit.trim(), name.trim());
            reset(); setVisible(false);
            if (project?.summaryError) message.warning(`项目已导入，但摘要尚未启动：${project.summaryError}`);
            else message.success("项目导入成功，正在生成功能摘要。");
            reloadGetProjectsInfo();
        } catch (err) {
            setError(err.code === "ECONNABORTED" || !err.response
                ? "未收到导入结果。请先刷新项目列表确认是否已经导入，避免重复提交。"
                : err.message || "导入失败，请检查仓库地址和版本后重试。");
            setPhase("ready");
        } finally {busy.current = false;}
    }

    const refLabel = verified?.refType === "tag" ? "标签" : "分支";
    return <>
        <Button icon={<GithubOutlined/>} className={welcomeStyles.confirmButton} onClick={() => {reset(); setVisible(true);}}>Git 导入</Button>
        <Modal open={visible} onCancel={close} width={600} centered maskClosable={false} keyboard={!importing}
            closable={!importing} className={styles.modal} afterOpenChange={open => {if (open) urlInput.current?.focus();}}
            title={<div className={styles.title}><span className={styles.titleIcon}><GithubOutlined/></span><div><h2>从 Git 导入项目</h2><p>连接你的代码仓库，开始整理功能摘要</p></div></div>}
            footer={<div className={styles.footer}>
                <span>{importing ? "导入完成后将自动生成摘要" : verified ? "确认后才会下载仓库" : "验证链接不会下载项目"}</span>
                <div><Button onClick={close} disabled={importing}>取消</Button>
                    <Button type="primary" loading={checking || importing} disabled={verified ? !name.trim() : !url.trim()}
                        onClick={verified ? importProject : verify}>
                        {importing ? "正在导入" : checking ? "正在验证" : verified ? "导入并生成摘要" : "验证仓库"}
                        {!checking && !importing && <ArrowRightOutlined/>}
                    </Button></div>
            </div>}>
            <div className={styles.steps} aria-label="导入流程">
                <div className={styles.currentStep}><span>{verified ? <CheckOutlined/> : "1"}</span>连接仓库</div>
                <div className={styles.stepLine}/>
                <div className={verified ? styles.currentStep : styles.nextStep}><span>2</span>确认导入</div>
            </div>
            <div className={styles.field}>
                <label htmlFor="git-import-url">仓库链接</label>
                <Input id="git-import-url" ref={urlInput} size="large" prefix={<LinkOutlined/>} allowClear placeholder="https://github.com/owner/repository"
                    value={url} disabled={checking || importing} onChange={event => {setUrl(event.target.value); invalidate();}}
                    onPressEnter={() => {if (!verified) verify();}}/>
                <p className={styles.hint}>直接粘贴仓库、分支或标签链接，无需另外选择分支。</p>
            </div>
            <details className={styles.advanced}>
                <summary>指定提交版本 <span>可选</span>{commit.trim() && <code>{commit.trim().slice(0, 10)}</code>}</summary>
                <div className={styles.advancedBody}>
                    <label htmlFor="git-import-commit">Commit ID</label>
                    <Input id="git-import-commit" placeholder="留空使用链接指向的版本" value={commit} disabled={checking || importing}
                        onChange={event => {setCommit(event.target.value); invalidate();}}/>
                    <p className={styles.hint}>填写后优先使用该提交。提交是否存在将在下载仓库后检查。</p>
                </div>
            </details>
            {checking && <div className={styles.activity} role="status"><LoadingOutlined/><div><strong>正在连接远程仓库 <small>{elapsed} 秒</small></strong><p>检查仓库地址，并识别链接对应的分支或标签。</p></div></div>}
            {verified && <div className={styles.verified}>
                <div className={styles.verifiedHeading}><CheckOutlined/><strong>仓库连接成功</strong><Tag>{commit.trim() ? "指定提交" : verified.refName ? `${refLabel} · ${verified.refName}` : "默认分支"}</Tag></div>
                <div className={styles.repositoryUrl} title={verified.repositoryUrl || url}>{verified.repositoryUrl || url}</div>
                <div className={styles.field}>
                    <label htmlFor="git-import-name">项目名称</label>
                    <Input id="git-import-name" size="large" value={name} placeholder="给项目起一个名字" disabled={importing}
                        onChange={event => {nameEdited.current = true; setName(event.target.value);}}
                        onPressEnter={importProject}/>
                    <p className={styles.hint}>仅用于 FeatX 内展示，不会修改远程仓库。</p>
                </div>
            </div>}
            {importing && <div className={styles.activity} role="status"><LoadingOutlined/><div><strong>正在下载并准备项目 <small>{elapsed} 秒</small></strong><p>服务器正在下载仓库、检出版本并准备摘要任务。较大的仓库可能需要几分钟，请勿重复提交。</p></div></div>}
            {error && <Alert className={styles.error} type="error" showIcon message={error}/>}
        </Modal>
    </>;
}
