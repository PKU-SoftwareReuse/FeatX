import React, {useState} from "react";
import {Button, Input, message, Modal, Spin, Tag, Tree} from "antd";
import {FileOutlined, GithubOutlined} from "@ant-design/icons";
import API from "../../API";
import styles from "../WelcomePage.module.css";


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

const GitDownModal = ({reloadGetProjectsInfo}) => {
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
            message.warning("Please enter a GitHub repo name.");
            return;
        }

        setLoadingPreview(true);
        API.gitDownRepo(gitName, commitId).then((data) => {
            setProjectType(data.projectType);
            setFolderName(data.repoName || "");
            setTreeData(buildFileTree(data.paths || []));
        }).catch(error => {
            message.error("Failed to clone repository.");
            console.log(error);
        }).finally(() => {
            setLoadingPreview(false);
        });
    };

    const handleImport = () => {
        if (!gitName || !folderName || !projectType) {
            message.warning("Please preview the repository and enter a repo name first.");
            return;
        }

        setLoadingImport(true);
        API.gitRepo(gitName, commitId, folderName).then(() => {
            message.success("Repository imported.");
            reloadGetProjectsInfo();
            reset();
        }).catch(error => {
            message.error("Failed to import repository.");
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
                Clone from GitHub
            </Button>
            <Modal
                title="Clone a Java or Python project from GitHub"
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
                        placeholder="GitHub repo, e.g., Naccl/NBlog"
                        value={gitName}
                        onChange={(e) => setGitName(e.target.value)}
                    />
                    <Input
                        placeholder="Commit id, optional"
                        value={commitId}
                        onChange={(e) => setCommitId(e.target.value)}
                    />
                    <Button onClick={handlePreview} loading={loadingPreview}>
                        Preview
                    </Button>
                </div>

                <div style={{marginTop: 16, marginBottom: 16}}>
                    {projectType && (
                        <div style={{display: "flex", gap: 8, alignItems: "center", marginBottom: 12}}>
                            <Tag color={projectType === "JAVA" ? "blue" : "green"}>{projectType}</Tag>
                            <span>{treeData.length > 0 ? "Source tree ready" : "No source files"}</span>
                        </div>
                    )}
                    <Spin spinning={loadingPreview} tip="Fetching repository...">
                        {treeData.length > 0 && (
                            <Tree treeData={treeData} defaultExpandAll showIcon height={320}/>
                        )}
                    </Spin>
                </div>

                <Input
                    placeholder="Please enter repo's name"
                    value={folderName}
                    onChange={(e) => setFolderName(e.target.value)}
                />
            </Modal>
        </>
    );
};

export default GitDownModal;
