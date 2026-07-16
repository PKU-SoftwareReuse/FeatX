import React, {useEffect, useRef, useState} from "react";
import {Modal, Button, Input, Upload, Tree, message, Tag} from "antd";
import {InboxOutlined, FileOutlined, UploadOutlined} from "@ant-design/icons";
import API from "../../API";
import styles from "../WelcomePage.module.css";


const {Dragger} = Upload;

const SOURCE_TREE_LIMIT = 1000;

const normalizePath = (path) => (path || "").replace(/\\/g, "/");

const getRawPath = (file) => normalizePath(file.originFileObj?.webkitRelativePath || file.name);

const isSourceFile = (path, projectType) => {
    const lowerPath = normalizePath(path).toLowerCase();
    return projectType === "JAVA" ? lowerPath.endsWith(".java") : lowerPath.endsWith(".py");
};

const stripThrough = (path, marker) => {
    const lowerPath = path.toLowerCase();
    const index = lowerPath.indexOf(marker);
    return index >= 0 ? path.substring(index + marker.length) : path;
};

const stripPythonSourceRoot = (path) => {
    const lowerPath = path.toLowerCase();
    if (lowerPath.startsWith("src/")) {
        return path.substring("src/".length);
    }

    const nestedSourceRootIndex = lowerPath.indexOf("/src/");
    if (nestedSourceRootIndex >= 0) {
        return path.substring(nestedSourceRootIndex + "/src/".length);
    }

    return path;
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

const normalizeSourcePath = (path, projectType, topLevel) => {
    let sourcePath = stripTopLevel(normalizePath(path), topLevel);
    if (projectType === "JAVA") {
        sourcePath = stripThrough(sourcePath, "src/main/java/");
        if (sourcePath.toLowerCase().startsWith("java/")) {
            sourcePath = sourcePath.substring("java/".length);
        }
    } else {
        sourcePath = stripThrough(sourcePath, "src/main/python/");
        sourcePath = stripPythonSourceRoot(sourcePath);
    }

    return sourcePath
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
    const sourceFiles = files
        .filter(file => isSourceFile(getRawPath(file), projectType))
        .map(file => ({
            file,
            path: normalizeSourcePath(getRawPath(file), projectType, topLevel),
        }))
        .filter(entry => entry.path);

    return {
        projectType,
        sourceFiles,
        treeData: buildFileTree(sourceFiles.slice(0, SOURCE_TREE_LIMIT).map(entry => entry.path)),
        defaultFolderName: topLevel,
    };
};

const FolderUploadModal = ({reloadGetProjectsInfo}) => {
    const [visible, setVisible] = useState(false);
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

    const props = {
        multiple: true,
        directory: true,
        beforeUpload: () => {
            if (!hasUploadedRef.current) {
                hasUploadedRef.current = true;
                setUploaded(true);
                setStatus("processing");
            }
            return false;
        },
        onChange(info) {
            if (debounceRef.current) {
                clearTimeout(debounceRef.current);
            }

            debounceRef.current = setTimeout(() => {
                const analysis = analyzeFiles(info.fileList);
                setProjectType(analysis.projectType);
                setSourceFiles(analysis.sourceFiles);
                setTreeData(analysis.treeData);

                if (analysis.defaultFolderName && !folderName) {
                    setFolderName(analysis.defaultFolderName);
                }

                if (!analysis.projectType) {
                    setStatus("unsupported");
                    message.warning("Only Java and Python projects are supported.");
                    return;
                }

                setStatus(analysis.sourceFiles.length > 0 ? "ready" : "unsupported");
            }, 300);
        },
    };

    const handleOk = async () => {
        if (uploading) {
            return;
        }

        if (!sourceFiles.length || !folderName || !projectType) {
            message.warning("Please select a Java or Python project and enter a repo name.");
            return;
        }

        const projectData = new FormData();
        sourceFiles.forEach(({file, path}) => {
            projectData.append("files", file.originFileObj);
            projectData.append("paths", path);
        });
        projectData.append("folderName", folderName);
        projectData.append("projectType", projectType);

        setUploading(true);
        API.uploadProject(projectData).then(() => {
            handleCancel();
            message.success("Upload succeeded.");
            reloadGetProjectsInfo();
        }).catch(error => {
            const statusCode = error.response?.status;
            if (statusCode === 413) {
                message.error("Upload failed: project is larger than the current server upload limit.");
            } else {
                const serverMessage = error.response?.data?.message || error.response?.data;
                message.error(serverMessage ? `Upload failed: ${serverMessage}` : "Upload failed.");
            }
            console.log(error);
        }).finally(() => {
            setUploading(false);
        });
    };

    const handleCancel = () => {
        setVisible(false);
        setUploaded(false);
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
                Upload New Repo
            </Button>
            <Modal
                title="Upload a Java or Python project"
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
                        fileList={[]}
                    >
                        <p className="ant-upload-drag-icon">
                            <InboxOutlined/>
                        </p>
                        <p className="ant-upload-text">Drag a project folder here or click to select it</p>
                    </Dragger>
                </div>

                <div style={{marginTop: 16, marginBottom: 16}}>
                    {projectType && (
                        <div style={{display: "flex", gap: 8, alignItems: "center", marginBottom: 12}}>
                            <Tag color={projectType === "JAVA" ? "blue" : "green"}>{projectType}</Tag>
                            <span>{sourceFiles.length.toLocaleString()} source files selected</span>
                            {sourceFiles.length > SOURCE_TREE_LIMIT && (
                                <span>Showing first {SOURCE_TREE_LIMIT.toLocaleString()} files</span>
                            )}
                        </div>
                    )}
                    {status === "processing" && <p>Reading folder...</p>}
                    {treeData.length > 0 && (
                        <Tree treeData={treeData} defaultExpandAll showIcon height={320}/>
                    )}
                </div>

                <div style={{display: "flex", flexDirection: "column", gap: "10px"}}>
                    <Input
                        placeholder="Please enter repo's name"
                        value={folderName}
                        onChange={(e) => setFolderName(e.target.value)}
                    />
                </div>
            </Modal>
        </>
    );
};

export default FolderUploadModal;
