import React, {useState, useEffect} from "react";
import {Modal, Button, Input, Upload, Tree, message} from "antd";
import {InboxOutlined, FileOutlined, UploadOutlined} from "@ant-design/icons";
import API from "../../API";
import styles from "../WelcomePage.module.css";


const {Dragger} = Upload;

const FolderUploadModal = ({reloadGetProjectsInfo}) => {
    const [visible, setVisible] = useState(false);
    const [folderFiles, setFolderFiles] = useState([]);
    const [folderName, setFolderName] = useState("");
    const [uploaded, setUploaded] = useState(false);
    const [treeData, setTreeData] = useState([]);


    const props = {
        multiple: true,
        directory: true,
        beforeUpload: () => false, // 阻止自动上传
        onChange(info) {
            if (uploaded) return; // 已上传就不再处理
            setFolderFiles(info.fileList);
            setUploaded(true);
        }

    };

    // 上传到后端
    const handleOk = async () => {
        if (!folderFiles.length || !folderName) {
            alert("请上传文件夹并输入名称");
            return;
        }
        if (folderFiles[0].originFileObj.webkitRelativePath.substring(0, 4) != "java") {
            alert("上传文件夹不合要求，请重新上传");
            setFolderFiles([])
            setUploaded(false)
            return;
        }

        const projectData = new FormData();
        folderFiles.forEach((file) => {
            projectData.append("files", file.originFileObj);
            projectData.append("paths", file.originFileObj.webkitRelativePath); // 👈 添加相对路径
        });
        projectData.append("folderName", folderName);

        API.uploadProject(projectData).then(response => {
            handleCancel()
            alert("上传成功")
            reloadGetProjectsInfo()
            // setProject("diy_" + folderName)
        }).catch(error => {
            alert("上传失败")
            console.log(error)
        })
    };

    const handleCancel = () => {
        setVisible(false);
        setUploaded(false);
        setFolderFiles([]);
        setTreeData([]);
        setFolderName(null);
    }

    // 构建文件树结构
    const buildFileTree = (files) => {
        const root = {};

        files.forEach((file) => {
            const path = file.originFileObj.webkitRelativePath || file.name;
            const parts = path.split("/");
            let current = root;
            for (let i = 0; i < parts.length; i++) {
                const part = parts[i];
                if (!current[part]) {
                    current[part] = i === parts.length - 1 ? null : {};
                }
                current = current[part];
            }
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

    // 更新文件树
    useEffect(() => {
        const tree = buildFileTree(folderFiles);
        setTreeData(tree);
    }, [folderFiles]);

    return (
        <>
            <Button icon={<UploadOutlined/>}
                    className={styles.confirmButton}
                    onClick={() => setVisible(true)}>
                Upload New Repo
            </Button>
            <Modal
                title="Upload the source code folder of the repository to be analyzed (XXX/src/main/java folder)"
                open={visible}
                onOk={handleOk}
                onCancel={handleCancel}
                width={600}
            >
                {/* 拖拽上传区域 */}
                {!uploaded && (
                    <Dragger
                        {...props}
                        showUploadList={false}
                        fileList={folderFiles}
                        disabled={uploaded}
                    >
                        <p className="ant-upload-drag-icon">
                            <InboxOutlined/>
                        </p>
                        <p className="ant-upload-text">
                            {uploaded ? "已上传文件夹" : "Drag the folder here or click to select to upload"}
                        </p>
                    </Dragger>
                )}
                {/* 文件树结构展示 */}
                <div style={{marginTop: 20, marginBottom: 20}}>
                    {treeData.length > 0 && (
                        <div>
                            <p style={{fontWeight: "bold"}}>📁 File structure: </p>
                            <Tree treeData={treeData} defaultExpandAll showIcon/>
                        </div>
                    )}
                </div>


                {/* 下方按钮和输入框 */}
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
