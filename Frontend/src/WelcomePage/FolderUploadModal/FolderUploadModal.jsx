import {useEffect, useRef, useState} from "react";
import {Modal, Button, Input, Tree, message, Alert, Progress} from "antd";
import {InboxOutlined, FileOutlined, UploadOutlined} from "@ant-design/icons";
import API from "../../api";
import styles from "../WelcomePage.module.css";

const MAX_FILES = 2000;
const MAX_BYTES = 100 * 1024 * 1024;
const PREVIEW_LIMIT = 200;
const yieldToBrowser = () => new Promise(resolve => setTimeout(resolve, 0));
const sizeText = bytes => `${(bytes / 1024 / 1024).toFixed(2)} MiB`;

function buildTree(files) {
    const root = new Map();
    for (const {path} of files.slice(0, PREVIEW_LIMIT)) {
        let current = root;
        const parts = path.split("/");
        parts.forEach((part, index) => {
            if (!current.has(part)) current.set(part, {title: part, key: parts.slice(0, index + 1).join("/"), nodes: new Map(), leaf: index === parts.length - 1});
            current = current.get(part).nodes;
        });
    }
    const convert = nodes => [...nodes.values()].map(node => ({title: node.title, key: node.key,
        icon: node.leaf ? <FileOutlined/> : null, children: node.leaf ? undefined : convert(node.nodes)}));
    return convert(root);
}

export default function FolderUploadModal({reloadGetProjectsInfo}) {
    const [visible, setVisible] = useState(false);
    const [files, setFiles] = useState([]);
    const [tree, setTree] = useState([]);
    const [name, setName] = useState("");
    const [status, setStatus] = useState("empty");
    const [error, setError] = useState("");
    const [scan, setScan] = useState({count: 0, bytes: 0});
    const [upload, setUpload] = useState({loaded: 0, percent: null});
    const [dragging, setDragging] = useState(false);
    const input = useRef(null);
    const generation = useRef(0);
    const uploadingRef = useRef(false);
    const uploading = status === "uploading";
    const reading = status === "reading";

    useEffect(() => () => {generation.current += 1;}, []);

    function reset() {
        generation.current += 1;
        setFiles([]); setTree([]); setError(""); setScan({count: 0, bytes: 0});
        setUpload({loaded: 0, percent: null}); setStatus("empty"); setName(""); setDragging(false);
        if (input.current) input.current.value = "";
    }

    // Both entry points produce the same {file, path} list. No per-file Upload callbacks.
    async function collect(producer, folderName) {
        if (uploadingRef.current) return;
        const token = ++generation.current;
        setStatus("reading"); setFiles([]); setTree([]); setError(""); setScan({count: 0, bytes: 0});
        const selected = [];
        let bytes = 0;
        const check = () => {
            if (token !== generation.current) throw new Error("READ_CANCELLED");
        };
        const add = async (file, path) => {
            check();
            if (selected.length >= MAX_FILES) throw new Error("文件数量超过 2000 个。请移除不需要上传的依赖或构建产物后重新选择；本次未上传任何文件。");
            bytes += file.size;
            if (bytes > MAX_BYTES) throw new Error("文件总大小超过 100 MiB。请减少上传内容后重新选择；本次未上传任何文件。");
            selected.push({file, path});
            if (selected.length % 32 === 0) {
                setScan({count: selected.length, bytes});
                await yieldToBrowser();
                check();
            }
        };
        try {
            await yieldToBrowser();
            check();
            await producer(add, check);
            check();
            if (!selected.length) throw new Error("文件夹中没有可上传的文件，请重新选择。");
            setFiles(selected); setTree(buildTree(selected)); setName(folderName);
            setScan({count: selected.length, bytes}); setStatus("ready");
        } catch (err) {
            if (token !== generation.current) return;
            setScan({count: selected.length, bytes}); setError(err.message || "无法读取文件夹，请检查文件访问权限后重试。"); setStatus("error");
        }
    }

    function selectFolder(event) {
        const list = event.target.files;
        if (!list?.length) {setStatus("empty"); return;}
        const root = list[0].webkitRelativePath.split("/")[0];
        collect(async (add, check) => {
            if (list.length > MAX_FILES) throw new Error(`所选文件夹包含 ${list.length.toLocaleString("zh-CN")} 个文件，超过 2000 个限制。请减少内容后重新选择；本次未上传任何文件。`);
            for (let i = 0; i < list.length; i++) {
                check();
                const file = list[i];
                const path = file.webkitRelativePath;
                if (!path.startsWith(`${root}/`)) throw new Error("无法获取文件夹相对路径，请重新选择文件夹。");
                await add(file, path.slice(root.length + 1));
            }
        }, root);
    }

    function dropFolder(event) {
        event.preventDefault(); event.stopPropagation(); setDragging(false);
        if (uploadingRef.current) return;
        // Capture entries during the drop event, before the browser clears DataTransfer.
        const entries = Array.from(event.dataTransfer.items || [])
            .filter(item => item.kind === "file").map(item => item.webkitGetAsEntry?.());
        if (entries.length !== 1 || !entries[0]?.isDirectory) {
            generation.current += 1;
            setFiles([]); setTree([]); setStatus("error");
            setError("请一次拖入一个项目文件夹；若浏览器不支持目录拖放，请使用“选择文件夹”。");
            return;
        }
        const root = entries[0];
        collect(async (add, check) => {
            async function visit(entry, relativePath) {
                check();
                if (entry.isFile) {
                    const file = await new Promise((resolve, reject) => entry.file(resolve, reject));
                    await add(file, relativePath);
                } else if (entry.isDirectory) {
                    const reader = entry.createReader();
                    // A directory may be returned in multiple batches (including >100 entries).
                    while (true) {
                        check();
                        const batch = await new Promise((resolve, reject) => reader.readEntries(resolve, reject));
                        if (!batch.length) break;
                        for (const child of batch) await visit(child, relativePath ? `${relativePath}/${child.name}` : child.name);
                        await yieldToBrowser();
                    }
                }
            }
            await visit(root, "");
        }, root.name);
    }

    function chooseFolder() {
        if (uploadingRef.current) return;
        reset();
        setStatus("selecting");
        input.current.click();
    }

    async function submit() {
        if (uploadingRef.current || status !== "ready" || !name.trim()) return;
        uploadingRef.current = true;
        setStatus("uploading"); setError(""); setUpload({loaded: 0, percent: null});
        try {
            await yieldToBrowser();
            const form = new FormData();
            files.forEach(({file, path}) => {form.append("files", file); form.append("paths", path);});
            form.append("folderName", name.trim());
            const project = await API.uploadProject(form, event => {
                setUpload({loaded: event.loaded, percent: event.total ? Math.min(100, Math.round(event.loaded / event.total * 100)) : null});
            });
            setVisible(false); reset();
            if (project?.summaryError) message.warning(project.summaryError);
            else message.success("项目上传成功，正在生成功能摘要。");
            reloadGetProjectsInfo();
        } catch (err) {
            const detail = err.response?.data?.message;
            setError(err.response?.status === 413 ? "上传内容超过服务器大小限制。" : err.code === "ECONNABORTED"
                ? "等待服务器响应超时，请先刷新项目列表确认是否导入成功，再决定是否重试。"
                : detail || err.message || "上传失败，请重试。");
            setStatus("ready");
        } finally {
            uploadingRef.current = false;
        }
    }

    return <>
        <Button icon={<UploadOutlined/>} className={styles.confirmButton}
            onClick={() => {reset(); setVisible(true);}}>上传文件夹</Button>
        <Modal title="上传项目" open={visible} width={640} maskClosable={false}
            onCancel={() => {if (!uploadingRef.current) {reset(); setVisible(false);}}}
            onOk={submit} okText="上传并生成摘要" cancelText="关闭"
            okButtonProps={{disabled: status !== "ready" || !name.trim() || uploading}}
            cancelButtonProps={{disabled: uploading}} closable={!uploading} keyboard={!uploading} confirmLoading={uploading}>
            <input ref={input} type="file" webkitdirectory="" directory="" multiple hidden onChange={selectFolder}/>
            <div onDragOver={event => {event.preventDefault(); if (!uploading) setDragging(true);}}
                onDragLeave={() => setDragging(false)} onDrop={dropFolder}
                style={{padding:24, textAlign:"center", border:`1px dashed ${dragging ? "#1677ff" : "#bbcee5"}`, borderRadius:12, background:dragging ? "#e6f4ff" : "#f8fbff"}}>
                <InboxOutlined style={{fontSize:38, color:"#2183f7"}}/>
                <p>将一个项目文件夹拖到这里</p>
                <Button onClick={chooseFolder} disabled={uploading}>{files.length ? "重新选择文件夹" : "选择文件夹"}</Button>
                <p style={{fontSize:12, color:"#8190a5", marginBottom:0}}>最多 2000 个文件、100 MiB；不会自动过滤 .git 或其他目录。</p>
            </div>
            <div role="status" aria-live="polite" style={{margin:"16px 0"}}>
                {status === "selecting" && <Alert type="info" showIcon message="等待文件夹选择" description="选定后，浏览器需要收集文件列表；大型目录可能耗时，请避免直接选择包含大量依赖或构建产物的目录。"/>}
                {reading && <Alert type="info" showIcon message={`正在读取：${scan.count} 个文件 · ${sizeText(scan.bytes)}`}
                    description="正在读取文件列表，尚未上传。" action={<Button size="small" onClick={reset}>取消读取</Button>}/>}
                {files.length > 0 && <p>已选择 {files.length} 个文件 · {sizeText(scan.bytes)}</p>}
                {error && <Alert type="error" showIcon message={error}/>}
                {uploading && <>
                    <Progress percent={upload.percent ?? 0} showInfo={upload.percent != null} status="active"/>
                    <p>{upload.percent === 100 ? "文件已传输，服务器正在保存项目、处理 Git 并启动摘要，请稍候……" : `正在上传，已传输 ${sizeText(upload.loaded)}……`}</p>
                </>}
            </div>
            {tree.length > 0 && <details style={{marginBottom:16}}>
                <summary style={{cursor:"pointer", color:"#6382a5"}}>查看文件预览{files.length > PREVIEW_LIMIT ? `（仅预览前 ${PREVIEW_LIMIT} 个，上传包含全部 ${files.length} 个文件）` : ""}</summary>
                <Tree treeData={tree} showIcon height={220}/>
            </details>}
            <Input aria-label="项目名称" placeholder="请输入项目名称" value={name} disabled={uploading} onChange={event => setName(event.target.value)}/>
        </Modal>
    </>;
}
