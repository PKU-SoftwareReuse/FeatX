import React, {useState} from "react";
import {Button, Input, message, Modal, Spin, Tag, Tree} from "antd";
import {FileOutlined, GithubOutlined} from "@ant-design/icons";
import API from "../../api";
import styles from "../WelcomePage.module.css";
import {useLanguage} from "../../i18n/LanguageContext";

const GIT_COPY = {
    zh: {
        enterGit: "请输入 Git 仓库地址。",
        cloneFailed: "Git 地址无效。",
        previewFirst: "请先检查仓库地址并输入仓库名称。",
        imported: "项目已登记；请在项目设置中发起分析。",
        importFailed: "导入代码仓库失败。",
        openButton: "从 Git 登记项目",
        title: "登记 Git 项目（不限语言）",
        gitRepository: "Git 地址，例如 Naccl/NBlog、GitLab、GitLink 或 SSH 地址",
        commitId: "提交 ID（选填）",
        preview: "检查地址",
        treeReady: "源文件目录已就绪",
        noSourceFiles: "仅登记仓库地址，不克隆或分析源码",
        fetching: "正在检查仓库地址……",
        repositoryName: "请输入代码仓库名称",
    },
    en: {
        enterGit: "Please enter a Git repository URL.",
        cloneFailed: "Invalid Git URL.",
        previewFirst: "Check the URL and enter a repository name first.",
        imported: "Project registered; submit analysis from project settings.",
        importFailed: "Failed to import repository.",
        openButton: "Register from Git",
        title: "Register a Git project (any language)",
        gitRepository: "Git URL: GitHub shorthand, GitLab, GitLink, or SSH",
        commitId: "Commit id, optional",
        preview: "Check URL",
        treeReady: "Source tree ready",
        noSourceFiles: "Registration only; source cloning and analysis are not performed",
        fetching: "Checking repository URL...",
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
    if (error?.message) return error.message;

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
