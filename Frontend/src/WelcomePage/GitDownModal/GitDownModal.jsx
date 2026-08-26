import React, {useState} from "react";
import {Button, Input, message, Modal, Spin, Tag, Tree} from "antd";
import {FileOutlined, GithubOutlined} from "@ant-design/icons";
import API from "../../api";
import styles from "../WelcomePage.module.css";
import {useLanguage} from "../../i18n/LanguageContext";

const GIT_COPY = {
    zh: {
        enterGit: "请输入 Git 仓库地址。",
        cloneFailed: "克隆代码仓库失败。",
        previewFirst: "请先预览代码仓库并输入仓库名称。",
        imported: "代码仓库已导入。",
        importFailed: "导入代码仓库失败。",
        openButton: "从 Git 仓库克隆",
        title: "从 Git 仓库克隆 Java 或 Python 项目",
        gitRepository: "Git 地址，例如 Naccl/NBlog、GitLab、GitLink 或 SSH 地址",
        commitId: "提交 ID（选填）",
        preview: "预览",
        treeReady: "源文件目录已就绪",
        noSourceFiles: "未找到源文件",
        fetching: "正在获取代码仓库……",
        repositoryName: "请输入代码仓库名称",
    },
    en: {
        enterGit: "Please enter a Git repository URL.",
        cloneFailed: "Failed to clone repository.",
        previewFirst: "Please preview the repository and enter a repo name first.",
        imported: "Repository imported.",
        importFailed: "Failed to import repository.",
        openButton: "Clone from Git",
        title: "Clone a Java or Python project from Git",
        gitRepository: "Git URL: GitHub shorthand, GitLab, GitLink, or SSH",
        commitId: "Commit id, optional",
        preview: "Preview",
        treeReady: "Source tree ready",
        noSourceFiles: "No source files",
        fetching: "Fetching repository...",
        repositoryName: "Please enter repo's name",
    },
};

const buildFileTree = (paths) => {
    const root = {};

    paths.forEach((path) => {
        const parts = (path || "").replace(/\\/g, "/").split("/").filter(Boolean);
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

const getErrorMessage = (error, fallback, language) => {
    if (language !== "en") return fallback;

    const data = error?.response?.data;
    if (typeof data === "string" && data.trim()) {
        return data;
    }
    return data?.detail || data?.message || data?.error || fallback;
};

const GitDownModal = ({reloadGetProjectsInfo}) => {
    const {language} = useLanguage();
    const copy = GIT_COPY[language];
    const [visible, setVisible] = useState(false);
    const [loadingPreview, setLoadingPreview] = useState(false);
    const [loadingImport, setLoadingImport] = useState(false);
    const [gitName, setGitName] = useState("");
    const [commitId, setCommitId] = useState("");
    const [folderName, setFolderName] = useState("");
    const [projectType, setProjectType] = useState(null);
    const [treeData, setTreeData] = useState([]);

    const reset = () => {
        setVisible(false);
        setLoadingPreview(false);
        setLoadingImport(false);
        setGitName("");
        setCommitId("");
        setFolderName("");
        setProjectType(null);
        setTreeData([]);
    };

    const handleCancel = () => {
        if (gitName) {
            API.gitClear(gitName, commitId).catch(error => console.log(error));
        }
        reset();
    };

    const handlePreview = () => {
        if (!gitName) {
            message.warning(copy.enterGit);
            return;
        }

        setLoadingPreview(true);
        API.gitDownRepo(gitName, commitId).then((data) => {
            setProjectType(data.projectType);
            setFolderName(data.repoName || "");
            setTreeData(buildFileTree(data.paths || []));
        }).catch(error => {
            message.error(getErrorMessage(error, copy.cloneFailed, language));
            console.log(error);
        }).finally(() => {
            setLoadingPreview(false);
        });
    };

    const handleImport = () => {
        if (!gitName || !folderName || !projectType) {
            message.warning(copy.previewFirst);
            return;
        }

        setLoadingImport(true);
        API.gitRepo(gitName, commitId, folderName).then(() => {
            message.success(copy.imported);
            reloadGetProjectsInfo();
            reset();
        }).catch(error => {
            message.error(getErrorMessage(error, copy.importFailed, language));
            console.log(error);
        }).finally(() => {
            setLoadingImport(false);
        });
    };

    return (
        <>
            <Button icon={<GithubOutlined/>}
                    className={styles.confirmButton}
                    onClick={() => setVisible(true)}>
                {copy.openButton}
            </Button>
            <Modal
                title={copy.title}
                open={visible}
                onOk={handleImport}
                onCancel={handleCancel}
                okButtonProps={{disabled: !projectType || loadingPreview}}
                confirmLoading={loadingImport}
                width={640}
                maskClosable={false}
            >
                <div style={{display: "flex", flexDirection: "column", gap: 10}}>
                    <Input
                        placeholder={copy.gitRepository}
                        value={gitName}
                        onChange={(e) => setGitName(e.target.value)}
                    />
                    <Input
                        placeholder={copy.commitId}
                        value={commitId}
                        onChange={(e) => setCommitId(e.target.value)}
                    />
                    <Button onClick={handlePreview} loading={loadingPreview}>
                        {copy.preview}
                    </Button>
                </div>

                <div style={{marginTop: 16, marginBottom: 16}}>
                    {projectType && (
                        <div style={{display: "flex", gap: 8, alignItems: "center", marginBottom: 12}}>
                            <Tag color={projectType === "JAVA" ? "blue" : "green"}>{projectType}</Tag>
                            <span>{treeData.length > 0 ? copy.treeReady : copy.noSourceFiles}</span>
                        </div>
                    )}
                    <Spin spinning={loadingPreview} tip={copy.fetching}>
                        {treeData.length > 0 && (
                            <Tree treeData={treeData} defaultExpandAll showIcon height={320}/>
                        )}
                    </Spin>
                </div>

                <Input
                    placeholder={copy.repositoryName}
                    value={folderName}
                    onChange={(e) => setFolderName(e.target.value)}
                />
            </Modal>
        </>
    );
};

export default GitDownModal;
