// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, message, Popconfirm, Spin, Tooltip,} from "antd";
import {DeleteOutlined, GithubOutlined, SyncOutlined} from '@ant-design/icons';
import API from "../API";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import GitDownModal from "./GitDownModal/GitDownModal";


const formatMetric = (value) => value === null || value === undefined ? "-" : value.toLocaleString();

const getGithubName = (project) => {
    if (!project.githubLink) return "未关联";
    if (!project.githubName || project.githubName === "Blank Git Link" || project.githubName === "Error in Extract Git Name") {
        return project.githubLink;
    }
    return project.githubName;
};

const getProjectStats = (project) => {
    if (project.projectType === "PYTHON") {
        return [
            ["编程语言", "Python"],
            ["代码行数", project.loc],
            ["Python 文件数", project.nof],
            ["函数 / 方法数", project.nom],
            ["类数量", project.noc],
        ];
    }

    return [
        ["编程语言", "Java"],
        ["代码行数", project.loc],
        ["类数量", project.noc],
        ["方法数量", project.nom],
        ["字段数量", project.nof],
    ];
};


const WelcomePage = () => {
    const navigate = useNavigate();

    const [loadingConnect, setLoadingConnect] = useState(false);
    const [loadingAnalyse, setLoadingAnalyse] = useState(false);
    const [projectOptions, setProjectOptions] = useState(null);


    useEffect(() => {
        testConnect();
        getProjects();
    }, [])

    const testConnect = () => {
        setLoadingConnect(true);
        API.testConnect().then(res => {
            setLoadingConnect(false);
        }).catch(err => {
            console.log(err);
            testConnect();
        })
    }

    const getProjects = () => {
        setLoadingConnect(true);
        API.getProjectsInfo().then(data => {
            setProjectOptions(data);
            setLoadingConnect(false);
        }).catch(err => {
            console.log(err);
            getProjects();
        })
    }

    const handleConfirmButton = (repoId) => {
        setLoadingAnalyse(true);

        API.postProjectPath(repoId).then(response => {
            navigate('/debloating')
            setLoadingAnalyse(false);
        }).catch(error => {
            console.log(error)
            setLoadingAnalyse(false);
        })
    }

    const handleResummaryButton = (repoId) => {
        API.postResummary(repoId).then(response => {
            getProjects()
        }).catch(error => {
            console.log(error)
        })
    }

    const handleDropButton = (repoId) => {
        setLoadingConnect(true);
        API.postDropRepo(repoId).then(() => {
            message.success("项目已删除。");
            getProjects();
        }).catch(error => {
            setLoadingConnect(false);
            message.error("项目删除失败。");
            console.log(error)
        })
    }

    return (
        <div className={styles.welcomePage}>

            {/* LOGO */}
            <h1 className={styles.logo1}>面向大语言模型编程的功能特征导向界面</h1>
            <h1 className={styles.logo2}>FeatX：通过编辑功能特征来编辑软件</h1>


            <Spin tip={"正在分析并处理所选代码仓库。"} size={"large"} spinning={loadingAnalyse}>
                <Spin tip={"正在连接……"} size={"large"} spinning={loadingConnect}>
                    <div className={styles.projectGrid}>
                        {projectOptions ? (
                            projectOptions.map((project, index) => (
                                <Card
                                    key={index}
                                    title={
                                        <div className={styles.cardTitle}>
                                            <Popconfirm
                                                title="确认删除项目"
                                                description="确定要删除此项目吗？删除后无法恢复。"
                                                onConfirm={() => {
                                                    handleDropButton(project.id);
                                                }}
                                                okText="删除"
                                                cancelText="取消"
                                            >
                                                <Button
                                                    type="text"
                                                    danger
                                                    icon={<DeleteOutlined/>}
                                                    className={styles.titleButtonLeft}
                                                />
                                            </Popconfirm>
                                            <span className={styles.cardTitleText}>{project.projectName}</span>
                                            <Popconfirm
                                                title="确认重新生成功能特征摘要"
                                                description="确定要重新生成功能特征摘要吗？此过程可能需要几分钟。"
                                                onConfirm={() => {
                                                    handleResummaryButton(project.id);
                                                }}
                                                okText="确认"
                                                cancelText="取消"
                                            >
                                                <Button
                                                    type="text"
                                                    icon={<SyncOutlined/>}
                                                    className={styles.titleButtonRight}
                                                    disabled={!project.summaryFlag || project.projectType === "PYTHON"}
                                                />
                                            </Popconfirm>
                                        </div>
                                    }
                                    hoverable
                                    className={styles.projectCard}
                                >
                                    <Descriptions column={1} size="small" layout="horizontal">
                                        {getProjectStats(project).map(([label, value]) => (
                                            <Descriptions.Item key={label} label={label}>
                                                {typeof value === "number" ? formatMetric(value) : value}
                                            </Descriptions.Item>
                                        ))}
                                        <Descriptions.Item label="GitHub 仓库">
                                            {project.githubLink ? (
                                                <a href={project.githubLink} target="_blank"
                                                   rel="noopener noreferrer">
                                                    <GithubOutlined style={{marginRight: 8}}/>
                                                    {getGithubName(project)}
                                                </a>
                                            ) : getGithubName(project)}
                                        </Descriptions.Item>
                                    </Descriptions>
                                    <p style={{textAlign: "center"}}>{project.description}</p>
                                    <Tooltip
                                        title={project.projectType === "PYTHON" ? "Python 分析功能将在后续版本中提供。" : (!project.summaryFlag ? "正在生成功能特征摘要……" : "")}
                                    >
                                        <div className={styles.buttonContainer}>
                                            <Button
                                                type="primary"
                                                onClick={() => {
                                                    handleConfirmButton(project.id);
                                                }}
                                                disabled={!project.summaryFlag || project.projectType === "PYTHON"}
                                            >
                                                打开
                                            </Button>
                                        </div>
                                    </Tooltip>
                                </Card>
                            ))) : null
                        }
                    </div>
                    <div className={styles.uploadModalWrapper}>
                        <FolderUploadModal reloadGetProjectsInfo={getProjects}/>
                        <GitDownModal reloadGetProjectsInfo={getProjects}/>
                    </div>
                </Spin>
            </Spin>

            {/* 介绍文本 */}
            <div className={styles.introText}>
                <p>FeatX 提供一体化环境，支持通过编辑功能特征来编辑软件。其工作流程如下：</p>
                <p>1. <strong>功能特征摘要。</strong>构建分层的功能特征列表，将代码仓库中的代码组织为功能主题及其下属功能特征。</p>
                <p>2. <strong>相关代码图谱构建。</strong>构建完整的相关代码图谱，涵盖每项功能特征的全部实现上下文。</p>
                <p>3. <strong>智能体生成。</strong>采用三阶段智能体流程，按照成熟的软件工程工作流生成一致的文件级修改。</p>
                <p>4. <strong>代码变更确认。</strong>用户审核生成的代码变更，并将确认后的内容应用回代码仓库。</p>
            </div>
        </div>


    );
}

export default WelcomePage;
