import React, {useEffect, useRef, useState} from "react";
import {Modal, Button, Input, Upload, Tree, message, Tag} from "antd";
import {InboxOutlined, FileOutlined, UploadOutlined} from "@ant-design/icons";
import API from "../../api";
import styles from "../WelcomePage.module.css";
import {useLanguage} from "../../i18n/LanguageContext";


const {Dragger} = Upload;

const SOURCE_TREE_LIMIT = 1000;

const UPLOAD_COPY = {
    zh: {
        unsupported: "请选择包含文件的项目文件夹。",
        incomplete: "请选择项目文件夹，并输入代码仓库名称。",
        succeeded: "上传成功；可在项目设置中发起分析。",
        failed: "上传失败。",
        tooLarge: "上传失败：项目超过当前服务器上传大小限制。",
        failedWithReason: (reason) => `上传失败：${reason}`,
        openButton: "上传新代码仓库",
        title: "上传项目（不限语言）",
        dragHint: "将项目文件夹拖到此处，或点击选择文件夹（最多 2000 个文件、100 MiB；请移除密钥）",
        selectedFiles: (count) => `已选择 ${count} 个项目文件`,
        showingFiles: (count) => `仅显示前 ${count} 个文件`,
        reading: "正在读取文件夹……",
        repositoryName: "请输入代码仓库名称",
    },
    en: {
        unsupported: "Select a project folder containing files.",
        incomplete: "Please select a project folder and enter a repo name.",
        succeeded: "Upload succeeded; submit analysis from project settings.",
        failed: "Upload failed.",
        tooLarge: "Upload failed: project is larger than the current server upload limit.",
        failedWithReason: (reason) => `Upload failed: ${reason}`,
        openButton: "Upload New Repo",
        title: "Upload a project (any language)",
        dragHint: "Drag a project folder here or click to select it (2000 files / 100 MiB max; remove secrets first)",
        selectedFiles: (count) => `${count} project files selected`,
        showingFiles: (count) => `Showing first ${count} files`,
        reading: "Reading folder...",
        repositoryName: "Please enter repo's name",
    },
};

const normalizePath = (path) => (path || "").replace(/\\/g, "/");

const getRawPath = (file) => normalizePath(file.originFileObj?.webkitRelativePath || file.name);

const sharedTopLevel = (paths) => {
    if (!paths.length) return "";
    const first = paths[0].split("/")[0];
    if (!first) return "";
    return paths.every(path => path.startsWith(`${first}/`)) ? first : "";
};

const stripTopLevel = (path, topLevel) => {
    if (!topLevel) return path;
    return path === topLevel ? "" : path.startsWith(`${topLevel}/`) ? path.substring(topLevel.length + 1) : path;
};

const normalizeProjectPath = (path, topLevel) => {
    return stripTopLevel(normalizePath(path), topLevel)
        .split("/")
        .filter(part => part && part !== ".")
        .join("/");
};

const buildFileTree = (paths) => {
    const root = Object.create(null);

    paths.forEach((path) => {
        const parts = normalizePath(path).split("/").filter(Boolean);
        let current = root;
        parts.forEach((part, index) => {
            if (!current[part]) {
                current[part] = index === parts.length - 1 ? null : Object.create(null);
            }
            current = current[part];
        });
    });

    const toTreeData = (node, path = "") =>
        Object.entries(node).map(([key, value]) => {
            const fullPath = path ? `${path}/${key}` : key;
            return {
                title: key,
                key: fullPath,
                icon: value ? null : <FileOutlined/>,
                children: value ? toTreeData(value, fullPath) : null,
            };
        });

    return toTreeData(root);
};

const analyzeFiles = (fileList) => {
    const files = fileList.filter(file => file.originFileObj);
    const rawPaths = files.map(getRawPath);
    const topLevel = sharedTopLevel(rawPaths);
    const projectFiles = files.map(file => ({file, path: normalizeProjectPath(getRawPath(file), topLevel)}))
        .filter(entry => entry.path);
    return {
        projectType: projectFiles.length ? "PROJECT" : null,
        projectFiles,
        treeData: buildFileTree(projectFiles.slice(0, SOURCE_TREE_LIMIT).map(entry => entry.path)),
        defaultFolderName: topLevel,
    };
};

const FolderUploadModal = ({reloadGetProjectsInfo}) => {
    const {language} = useLanguage();
    const copy = UPLOAD_COPY[language];
    const [visible, setVisible] = useState(false);
    const [projectFiles, setProjectFiles] = useState([]);
    const [folderName, setFolderName] = useState("");
    const [uploaded, setUploaded] = useState(false);
    const [treeData, setTreeData] = useState([]);
    const [projectType, setProjectType] = useState(null);
    const [status, setStatus] = useState("empty");
    const [uploading, setUploading] = useState(false);
    const debounceRef = useRef(null);
    const hasUploadedRef = useRef(false);

    useEffect(() => () => {
        if (debounceRef.current) {
            clearTimeout(debounceRef.current);
        }
    }, []);

    const scheduleAnalysis = (files) => {
        if (debounceRef.current) {
            clearTimeout(debounceRef.current);
        }

        const fileList = (files || []).map(file => ({
            uid: file.uid,
            name: file.name,
            originFileObj: file,
        }));

        debounceRef.current = setTimeout(() => {
            const analysis = analyzeFiles(fileList);
            setProjectType(analysis.projectType);
            setProjectFiles(analysis.projectFiles || []);
            setTreeData(analysis.treeData);

            if (analysis.defaultFolderName) {
                setFolderName(currentName => currentName || analysis.defaultFolderName);
            }

            if (!analysis.projectType) {
                setStatus("unsupported");
                message.warning(copy.unsupported);
                return;
            }

            const bytes = analysis.projectFiles.reduce((sum, entry) => sum + entry.file.originFileObj.size, 0);
            if (analysis.projectFiles.length > 2000 || bytes > 100 * 1024 * 1024) {
                setStatus("unsupported");
                message.warning(language === "zh" ? "最多上传 2000 个文件、100 MiB。" : "Maximum 2000 files and 100 MiB.");
                return;
            }
            setStatus("ready");
        }, 300);
    };

    const props = {
        multiple: true,
        directory: true,
        beforeUpload: (file, fileList) => {
            if (!hasUploadedRef.current) {
                hasUploadedRef.current = true;
                setUploaded(true);
                setStatus("processing");
                scheduleAnalysis(fileList);
            }
            return Upload.LIST_IGNORE;
        },
    };

    const handleOk = async () => {
        if (uploading) {
            return;
        }

        if (!projectFiles.length || !folderName.trim() || !projectType || status !== "ready") {
            message.warning(copy.incomplete);
            return;
        }

        const projectData = new FormData();
        projectFiles.forEach(({file, path}) => {
            projectData.append("files", file.originFileObj);
            projectData.append("paths", path);
        });
        projectData.append("folderName", folderName);

        setUploading(true);
        API.uploadProject(projectData).then(() => {
            handleCancel();
            message.success(copy.succeeded);
            reloadGetProjectsInfo();
        }).catch(error => {
            const statusCode = error.response?.status;
            if (statusCode === 413) {
                message.error(copy.tooLarge);
            } else {
                const serverMessage = error.response?.data?.message || error.response?.data;
                message.error(serverMessage ? copy.failedWithReason(serverMessage) : copy.failed);
            }
            console.log(error);
        }).finally(() => {
            setUploading(false);
        });
    };

    const handleCancel = () => {
        setVisible(false);
        setUploaded(false);
        setProjectFiles([]);
        setTreeData([]);
        setFolderName("");
        setProjectType(null);
        setStatus("empty");
        setUploading(false);
        hasUploadedRef.current = false;
        if (debounceRef.current) {
            clearTimeout(debounceRef.current);
        }
    };

    return (
        <>
            <Button icon={<UploadOutlined/>}
                    className={styles.confirmButton}
                    onClick={() => API.capabilities?.upload === false
                        ? API.notifyUnavailable(language === "zh" ? "文件夹上传" : "Folder upload")
                        : setVisible(true)}>
                {copy.openButton}
            </Button>
            <Modal
                title={copy.title}
                open={visible}
                onOk={handleOk}
                onCancel={handleCancel}
                okButtonProps={{disabled: status !== "ready" || uploading}}
                confirmLoading={uploading}
                width={640}
                maskClosable={false}
            >
                <div style={{display: uploaded ? "none" : "block"}}>
                    <Dragger
                        {...props}
                        showUploadList={false}
                    >
                        <p className="ant-upload-drag-icon">
                            <InboxOutlined/>
                        </p>
                        <p className="ant-upload-text">{copy.dragHint}</p>
                    </Dragger>
                </div>

                <div style={{marginTop: 16, marginBottom: 16}}>
                    {projectType && (
                        <div style={{display: "flex", gap: 8, alignItems: "center", marginBottom: 12}}>
                            <Tag color="blue">{language === "zh" ? "项目文件" : "Project files"}</Tag>
                            <span>{copy.selectedFiles(projectFiles.length.toLocaleString(language === "zh" ? "zh-CN" : "en-US"))}</span>
                            {projectFiles.length > SOURCE_TREE_LIMIT && (
                                <span>{copy.showingFiles(SOURCE_TREE_LIMIT.toLocaleString(language === "zh" ? "zh-CN" : "en-US"))}</span>
                            )}
                        </div>
                    )}
                    {status === "processing" && <p>{copy.reading}</p>}
                    {treeData.length > 0 && (
                        <Tree treeData={treeData} defaultExpandAll showIcon height={320}/>
                    )}
                </div>

                <div style={{display: "flex", flexDirection: "column", gap: "10px"}}>
                    <Input
                        placeholder={copy.repositoryName}
                        value={folderName}
                        onChange={(e) => setFolderName(e.target.value)}
                    />
                </div>
            </Modal>
        </>
    );
};

export default FolderUploadModal;
