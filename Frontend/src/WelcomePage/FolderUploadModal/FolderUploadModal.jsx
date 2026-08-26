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
        unsupported: "仅支持 Java 和 Python 项目。",
        incomplete: "请选择 Java 或 Python 项目，并输入代码仓库名称。",
        succeeded: "上传成功。",
        failed: "上传失败。",
        tooLarge: "上传失败：项目超过当前服务器上传大小限制。",
        failedWithReason: (reason) => `上传失败：${reason}`,
        openButton: "上传新代码仓库",
        title: "上传 Java 或 Python 项目",
        dragHint: "将项目文件夹拖到此处，或点击选择文件夹",
        selectedFiles: (count) => `已选择 ${count} 个项目文件`,
        showingFiles: (count) => `仅显示前 ${count} 个文件`,
        reading: "正在读取文件夹……",
        repositoryName: "请输入代码仓库名称",
    },
    en: {
        unsupported: "Only Java and Python projects are supported.",
        incomplete: "Please select a Java or Python project and enter a repo name.",
        succeeded: "Upload succeeded.",
        failed: "Upload failed.",
        tooLarge: "Upload failed: project is larger than the current server upload limit.",
        failedWithReason: (reason) => `Upload failed: ${reason}`,
        openButton: "Upload New Repo",
        title: "Upload a Java or Python project",
        dragHint: "Drag a project folder here or click to select it",
        selectedFiles: (count) => `${count} project files selected`,
        showingFiles: (count) => `Showing first ${count} files`,
        reading: "Reading folder...",
        repositoryName: "Please enter repo's name",
    },
};

const normalizePath = (path) => (path || "").replace(/\\/g, "/");

const getRawPath = (file) => normalizePath(file.originFileObj?.webkitRelativePath || file.name);

const isSourceFile = (path, projectType) => {
    const lowerPath = normalizePath(path).toLowerCase();
    return projectType === "JAVA" ? lowerPath.endsWith(".java") : lowerPath.endsWith(".py");
};

const sharedTopLevel = (paths) => {
    if (!paths.length) return "";
    const first = paths[0].split("/")[0];
    if (!first) return "";
    return paths.every(path => path.split("/")[0] === first) ? first : "";
};

const stripTopLevel = (path, topLevel) => {
    if (!topLevel) return path;
    return path === topLevel ? "" : path.startsWith(`${topLevel}/`) ? path.substring(topLevel.length + 1) : path;
};

const normalizeProjectPath = (path, topLevel) => {
    return stripTopLevel(normalizePath(path), topLevel)
        .split("/")
        .filter(part => part && part !== "." && part !== "..")
        .join("/");
};

const detectProjectType = (paths) => {
    let javaScore = 0;
    let pythonScore = 0;

    paths.forEach((path) => {
        const lowerPath = normalizePath(path).toLowerCase();
        if (lowerPath.endsWith(".java")) javaScore += 3;
        if (lowerPath.includes("/src/main/java/") || lowerPath.startsWith("src/main/java/")) javaScore += 5;
        if (lowerPath.endsWith("pom.xml") || lowerPath.endsWith("build.gradle") || lowerPath.endsWith("build.gradle.kts")) javaScore += 2;

        if (lowerPath.endsWith(".py")) pythonScore += 3;
        if (lowerPath.endsWith("pyproject.toml") || lowerPath.endsWith("setup.py") || lowerPath.endsWith("requirements.txt")) pythonScore += 2;
    });

    if (javaScore === 0 && pythonScore === 0) return null;
    return javaScore >= pythonScore ? "JAVA" : "PYTHON";
};

const buildFileTree = (paths) => {
    const root = {};

    paths.forEach((path) => {
        const parts = normalizePath(path).split("/").filter(Boolean);
        let current = root;
        parts.forEach((part, index) => {
            if (!current[part]) {
                current[part] = index === parts.length - 1 ? null : {};
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
    const projectType = detectProjectType(rawPaths);

    if (!projectType) {
        return {
            projectType: null,
            sourceFiles: [],
            treeData: [],
            defaultFolderName: sharedTopLevel(rawPaths),
        };
    }

    const topLevel = sharedTopLevel(rawPaths);
    const projectFiles = files
        .map(file => ({
            file,
            path: normalizeProjectPath(getRawPath(file), topLevel),
        }))
        .filter(entry => entry.path);
    const sourceFiles = projectFiles
        .filter(({file}) => isSourceFile(getRawPath(file), projectType));

    return {
        projectType,
        projectFiles,
        sourceFiles,
        treeData: buildFileTree(projectFiles.slice(0, SOURCE_TREE_LIMIT).map(entry => entry.path)),
        defaultFolderName: topLevel,
    };
};

const FolderUploadModal = ({reloadGetProjectsInfo}) => {
    const {language} = useLanguage();
    const copy = UPLOAD_COPY[language];
    const [visible, setVisible] = useState(false);
    const [projectFiles, setProjectFiles] = useState([]);
    const [sourceFiles, setSourceFiles] = useState([]);
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
            setSourceFiles(analysis.sourceFiles);
            setTreeData(analysis.treeData);

            if (analysis.defaultFolderName) {
                setFolderName(currentName => currentName || analysis.defaultFolderName);
            }

            if (!analysis.projectType) {
                setStatus("unsupported");
                message.warning(copy.unsupported);
                return;
            }

            setStatus(analysis.sourceFiles.length > 0 ? "ready" : "unsupported");
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

        if (!projectFiles.length || !sourceFiles.length || !folderName || !projectType) {
            message.warning(copy.incomplete);
            return;
        }

        const projectData = new FormData();
        projectFiles.forEach(({file, path}) => {
            projectData.append("files", file.originFileObj);
            projectData.append("paths", path);
        });
        projectData.append("folderName", folderName);
        projectData.append("projectType", projectType);

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
        setSourceFiles([]);
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
                    onClick={() => setVisible(true)}>
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
                            <Tag color={projectType === "JAVA" ? "blue" : "green"}>{projectType}</Tag>
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
