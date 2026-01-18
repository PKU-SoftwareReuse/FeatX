import styles from './Template.module.css';
import React, {useEffect, useState} from "react";
import API from "../API";
import {Button, Card, Layout, List, Spin, Typography} from "antd";
import classNames from "classnames";
import {Prism as SyntaxHighlighter} from "react-syntax-highlighter";
import {tomorrow} from "react-syntax-highlighter/dist/cjs/styles/prism";

const {Sider, Content} = Layout;
const {Title, Paragraph} = Typography;

const Template = () => {
    const [templateData, setTemplateData] = useState([]);
    const [selectedClass, setSelectedClass] = useState(null);
    const [staticLoading, setStaticLoading] = useState(false);
    const [llmLoading, setLlmLoading] = useState(false);
    const [response, setResponse] = useState("");
    const [summaryTitle, setSummaryTitle] = useState("");

    useEffect(() => {
        setStaticLoading(true);
        API.getTemplateStaticInfo()
            .then((data) => {
                setTemplateData(data)
                setStaticLoading(false);
                getCachedTemplateSummary()
            })
            .catch((error) => {
                console.error('Error fetching Template Static Info data:', error)
                setStaticLoading(false);
            });
    }, []);

    const getCachedTemplateSummary = () => {
        API.getCachedTemplateSummary()
            .then((data) => {
                if (data == null || data == "") {
                    handleChat(API.getTemplateSummaryInfo())
                } else {
                    setSummaryTitle(data.title)
                    setResponse(data.description);
                }
            })
            .catch((error) => {
                console.error('Error fetching Method Summary data:', error)
            });
    }

    const downloadJsonFile = () => {
        API.getTemplateJsonFile()
            .then(response => {
                const contentDisposition = response.headers.get("Content-Disposition");
                let filename = "Code Template by LoCoTeM.json"; // 默认文件名

                if (contentDisposition) {
                    const match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
                    if (match && match[1]) {
                        filename = decodeURIComponent(match[1]);// 从后端获取真实文件名
                    }
                }

                return response.blob().then(blob => ({ blob, filename }));
            })
            .then(({ blob, filename }) => {
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement("a");
                a.href = url;
                a.download = filename; // 使用后端提供的文件名
                document.body.appendChild(a);
                a.click();
                window.URL.revokeObjectURL(url); // 释放 URL
            })
            .catch((error) => {
                console.error('Error fetching Template LLM Info data:', error)
            });
    }

    const handleChat = (eventSource) => {
        setSummaryTitle("正在生成中")
        setResponse("");
        setLlmLoading(true);
        eventSource.onmessage = (event) => {
            if (event.data === "<<END>>") {
                console.log("后端数据全部发送完成，关闭连接");
                eventSource.close();
                setTimeout(()=>{
                    setLlmLoading(false);
                    getCachedTemplateSummary()
                    downloadJsonFile()
                },500)
            } else {
                setResponse(prev => prev + event.data);
            }
        };
        eventSource.onerror = () => {
            eventSource.close(); // 关闭连接
            setLlmLoading(false);
        };
    };

    return (
        <Layout style={{height: "100vh"}} className={styles.app}>
            <Spin tip={"正在生成中"} size={"large"} spinning={llmLoading}>
                <Content style={{height: "40vh", background: "#f0f2f5", padding: "16px"}}>
                    <Title level={2}>{summaryTitle}</Title>
                    <Paragraph>简要功能描述：{response}</Paragraph>
                    <Button type="primary" onClick={downloadJsonFile}>下载模板</Button>
                </Content>
            </Spin>
            <Content style={{height: "60vh"}}>
                <Layout style={{height: "100%"}}>
                    {/*左侧可滚动列表 */}
                    <Sider width={350}
                           style={{background: "#fff", padding: "16px", overflowY: "auto", height: "100%"}}>
                        <Title level={3}>类列表</Title>
                        <Spin tip={"正在加载列表"} size={"large"} spinning={staticLoading}>
                            <List
                                bordered
                                dataSource={[...templateData].sort((a, b) => {
                                    // 组内按 mapKey 字母顺序排序
                                    return a.typeDeclarationMapKey.localeCompare(b.typeDeclarationMapKey);
                                })}
                                renderItem={(item) => (
                                    <List.Item
                                        onClick={() => setSelectedClass(item)}
                                        className={classNames({
                                            [styles.item]: true,
                                            [styles.selected]: item == selectedClass,
                                        })}
                                    >
                                        {item.typeDeclarationMapKey}
                                    </List.Item>
                                )}

                            />
                        </Spin>
                    </Sider>

                    {/* 右侧详情 */}
                    <Layout style={{padding: "16px", background: "#fff", overflowY: "auto", height: "100%"}}>
                        <Content>
                            {selectedClass ? (
                                <Content>
                                    <Title level={3}>详情</Title>
                                    <Card title={selectedClass.typeDeclarationMapKey}
                                          style={{textAlign: "left"}}>
                                        <Paragraph>
                                            <strong>源代码：</strong>
                                            <SyntaxHighlighter
                                                language="java"
                                                style={tomorrow}
                                                showLineNumbers
                                                customStyle={{
                                                    borderRadius: "5px",
                                                    padding: "10px",
                                                    width: "100%",
                                                    overflowX: "auto",
                                                    fontSize: "14px",
                                                }}
                                            >
                                                {selectedClass.sourceCode}
                                            </SyntaxHighlighter>
                                        </Paragraph>
                                    </Card>
                                </Content>
                            ) : (
                                <Title level={3}>请选择查看详情</Title>
                            )}
                        </Content>
                    </Layout>
                </Layout>
            </Content>
        </Layout>
    )
}

export default Template;