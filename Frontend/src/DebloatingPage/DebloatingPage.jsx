// DebloatingPage.jsx

import styles from './DebloatingPage.module.css';
import React, {useEffect, useMemo, useRef, useState} from "react";
import {AutoComplete, Splitter, Collapse, Modal, Input, Card, List, Spin, Button, Tooltip, Popconfirm, Select} from "antd";
import {
    CloseOutlined,
    DeleteTwoTone,
    EditTwoTone,
    PlusSquareTwoTone,
    ExclamationCircleOutlined,
    SearchOutlined,
    SwapOutlined
} from '@ant-design/icons';
import classNames from "classnames";

import API from "../API";
import FeatureGraph from "../graph/featureGraph/FeatureGraph";
import CodeDiffComponent from "./CodeDiffComponent/CodeDiffComponent";
import MarkdownRendererComponent from "./MarkdownRenderComponent/MarkdownRenderComponent";
import {getLocalizedField, useLanguage} from "../i18n/LanguageContext";

const {Panel} = Collapse;
const {TextArea} = Input;

const DEBLOATING_COPY = {
    zh: {
        noAction: "当前没有可执行的操作",
        switchTitle: "确认切换操作",
        switchDescription: "确定要放弃当前修改吗？",
        discard: "放弃修改",
        cancel: "取消",
        newSubfeature: "新建子功能特征 ",
        completed: "操作已完成",
        applyingChanges: "正在应用代码变更……",
        featurePanel: "功能特征面板",
        searchFeatures: "搜索功能特征",
        noFeatureMatches: "未找到匹配的功能特征",
        fetchingFeatureSummary: "正在获取代码仓库的功能特征摘要。",
        pendingChanges: "请确认应用或放弃修改。",
        submit: "提交",
        agentPanel: "智能体生成",
        model: "模型",
        loadingModels: "正在获取模型……",
        modelUnavailable: "未获取到可用模型",
        graphPanel: "相关代码图谱",
        fetchingGraph: "正在获取相关代码图谱。",
        changesPanel: "代码变更",
        closeChangesPanel: "关闭代码变更",
        fetchingCode: "正在获取代码详情。",
        noSubmittedChanges: "您尚未提交任何修改，请先提交。",
        confirmAllChanges: "确认所有代码变更",
        reviewAllChanges: "您是否已检查全部代码变更（红色标记的节点）？",
        confirmApply: "确认应用",
        decline: "取消",
        applyChanges: "确认应用代码变更",
    },
    en: {
        noAction: "Maybe Not Todo",
        switchTitle: "You are trying to do another thing",
        switchDescription: "Do you want to give up your modification?",
        discard: "Yes, give up!",
        cancel: "Cancel",
        newSubfeature: "new SubFeature Item ",
        completed: "This is a [Fake] success message",
        applyingChanges: "Applying the Code Diff...",
        featurePanel: "Feature Panel",
        searchFeatures: "Search features",
        noFeatureMatches: "No matching features",
        fetchingFeatureSummary: "Fetching repo's feature summary.",
        pendingChanges: "You have made some modifications. Please confirm or drop it.",
        submit: "Submit",
        agentPanel: "Agent Panel",
        model: "Model",
        loadingModels: "Loading models...",
        modelUnavailable: "No models available",
        graphPanel: "CodeMap Panel",
        fetchingGraph: "fetching codeMap.",
        changesPanel: "Diff Panel",
        closeChangesPanel: "Close Diff Panel",
        fetchingCode: "Fetching code details.",
        noSubmittedChanges: "You haven't made any modifications. Please submit first.",
        confirmAllChanges: "Confirm All Code Diff",
        reviewAllChanges: "Have you read all the diff(the node marked with red)?",
        confirmApply: "Yes",
        decline: "No",
        applyChanges: "Confirm Apply the Diff",
    },
};

const getModuleDescription = (module, language) =>
    getLocalizedField(module, "moduleDesc", language);

const getFeatureDescription = (feature, language) =>
    getLocalizedField(feature, "featureDescription", language);

const getFeatureDisplayIndex = (featureList, item) => item.isNew || item.isNewGenerated
    ? 0
    : featureList
        .filter((feature) => !feature.isNew && !feature.isNewGenerated)
        .findIndex((feature) => feature === item) + 1;

const FeatureListItem = ({
    item,
    moduleId,
    itemNumber,
    description,
    selectedType,
    selectedFeatureItem,
    submitEnabled,
    selectedModel,
    editedText,
    setEditedText,
    copy,
    onClickItem,
    submitEdit,
    handleDelete,
    handleEdit,
}) => {
    const descriptionRef = useRef(null);
    const [singleLine, setSingleLine] = useState(false);
    const isSelected = selectedFeatureItem != null
        && item.featureId === selectedFeatureItem.featureId;
    const isEditing = (selectedType === "edit" || selectedType === "add") && isSelected;

    useEffect(() => {
        if (isEditing || !descriptionRef.current) {
            setSingleLine(false);
            return undefined;
        }

        const descriptionElement = descriptionRef.current;
        const measureLineCount = () => {
            const computedStyle = window.getComputedStyle(descriptionElement);
            const parsedLineHeight = Number.parseFloat(computedStyle.lineHeight);
            const fontSize = Number.parseFloat(computedStyle.fontSize) || 14;
            const lineHeight = Number.isFinite(parsedLineHeight) ? parsedLineHeight : fontSize * 1.2;
            setSingleLine(descriptionElement.getBoundingClientRect().height <= lineHeight * 1.4);
        };

        const frameId = window.requestAnimationFrame(measureLineCount);
        if (typeof ResizeObserver === "undefined") {
            window.addEventListener("resize", measureLineCount);
            return () => {
                window.cancelAnimationFrame(frameId);
                window.removeEventListener("resize", measureLineCount);
            };
        }

        const observer = new ResizeObserver(measureLineCount);
        observer.observe(descriptionElement);
        return () => {
            window.cancelAnimationFrame(frameId);
            observer.disconnect();
        };
    }, [description, isEditing, itemNumber]);

    return (
        <List.Item
            onClick={() => onClickItem(item)}
            data-feature-id={String(item.featureId)}
            data-module-id={String(moduleId)}
            className={classNames({
                [styles.item]: true,
                [styles.selectedItem]: isSelected,
            })}
        >
            <div className={styles.featureContent}>
                {isEditing ? (
                    <Tooltip title={!submitEnabled ? copy.pendingChanges : ""}>
                        <div>
                            <span>{itemNumber} </span>
                            <TextArea
                                value={editedText}
                                onChange={(event) => setEditedText(event.target.value)}
                                onClick={(event) => event.stopPropagation()}
                                size="middle"
                                autoSize={{minRows: 2, maxRows: 5}}
                                disabled={!submitEnabled || !selectedModel}
                            />
                            <Button
                                type="primary"
                                onClick={() => submitEdit(item)}
                                disabled={!submitEnabled || !selectedModel}
                            >
                                {copy.submit}
                            </Button>
                        </div>
                    </Tooltip>
                ) : (
                    <div ref={descriptionRef} className={styles.featureDescription}>
                        {itemNumber} {description}
                    </div>
                )}
            </div>
            <div className={classNames(styles.featureActions, {
                [styles.featureActionsSingleLine]: singleLine,
            })}>
                <Button
                    type="text"
                    icon={<DeleteTwoTone twoToneColor="#F74E52"/>}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleDelete(item);
                    }}
                    className={classNames(styles.icon, {
                        [styles.selectedIcon]: isSelected && selectedType === "delete",
                    })}
                />
                <Button
                    type="text"
                    icon={<EditTwoTone twoToneColor="#EFA92B"/>}
                    onClick={(event) => {
                        event.stopPropagation();
                        handleEdit(item);
                    }}
                    className={classNames(styles.icon, {
                        [styles.selectedIcon]: isSelected && (selectedType === "edit" || selectedType === "add"),
                    })}
                />
            </div>
        </List.Item>
    );
};

const DebloatingPage = () => {
    const {language, apiLanguage} = useLanguage();
    const copy = DEBLOATING_COPY[language];

    const [loadingFeatureList, setLoadingFeatureList] = useState(false);
    const [loadingFeatureGraph, setLoadingFeatureGraph] = useState(false);
    const [loadingCode, setLoadingCode] = useState(false);
    const [models, setModels] = useState([]);
    const [selectedModel, setSelectedModel] = useState(null);
    const [loadingModels, setLoadingModels] = useState(true);

    useEffect(() => {
        let active = true;

        API.getLlmModels()
            .then((catalog) => {
                if (!active) return;
                const availableModels = Array.isArray(catalog.models) ? catalog.models : [];
                setModels(availableModels);
                setSelectedModel(
                    availableModels.includes(catalog.defaultModel)
                        ? catalog.defaultModel
                        : availableModels[0] || null
                );
            })
            .catch((error) => {
                if (!active) return;
                console.error("Error fetching LLM models:", error);
                setModels([]);
                setSelectedModel(null);
            })
            .finally(() => {
                if (active) setLoadingModels(false);
            });

        return () => {
            active = false;
        };
    }, []);

    useEffect(() => {
        const fetchData = async () => {
            const res = await getFeatureData()
            console.log(res)
            setActiveKey(res[0].moduleId)
            handleSelect(res[0].featureList[0])
        }
        fetchData()
    }, [])


    const [featureData, setFeatureData] = useState([]);
    const [selectedFeatureItem, setSelectedFeatureItem] = useState(null);
    const [selectedType, setSelectedType] = useState(null);

    const getFeatureData = async () => {
        setLoadingFeatureList(true)
        setLoadingFeatureGraph(true);
        setLoadingCode(true);
        try {
            const res = await API.getFeatures()
            setFeatureData(res)
            setLoadingFeatureList(false)
            return res
        } catch (error) {
            console.error(error)
            return []
        }
    }

    const [graphData, setGraphData] = useState({nodes: [], edges: []});

    const getFeatureGraphData = (featureId, selectedType) => {
        setLoadingFeatureGraph(true);
        setLoadingCode(true);
        if (selectedType === 'delete') {
            API.getMinGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
            })
        } else if (selectedType === 'edit' || selectedType === 'select') {
            API.getMaxGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
            })
        } else if (selectedType === 'add') {
            setGraphData(null)
            setLoadingFeatureGraph(false);
        } else if (selectedType === 'new') {
            API.getNewGraphData().then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
            })
        }

    }

    const featureGraphRef = useRef();

    useEffect(() => {
        if (graphData && graphData.nodes?.length > 0) {
            featureGraphRef.current?.selectRandomNode();
        }
    }, [graphData]);

    const [codeDiff, setCodeDiff] = useState('');
    const [diffDrawerOpen, setDiffDrawerOpen] = useState(false);
    const [selectedCodeNodeId, setSelectedCodeNodeId] = useState('');

    useEffect(() => {
        if (!diffDrawerOpen) return undefined;

        const handleEscape = (event) => {
            if (event.key === 'Escape') {
                setDiffDrawerOpen(false);
            }
        };

        window.addEventListener('keydown', handleEscape);
        return () => window.removeEventListener('keydown', handleEscape);
    }, [diffDrawerOpen]);

    const getCodeDiff = (classNodeId) => {
        setSelectedCodeNodeId(String(classNodeId));
        setDiffDrawerOpen(true);
        setLoadingCode(true)

        // 检查当前选中的feature是否是新生成的feature
        const isNewFeature = selectedFeatureItem && selectedFeatureItem.isNewGenerated;

        if (isNewFeature) {
            // 如果是新生成的feature，总是使用newFeatureCode接口
            console.log('Fetching new feature code for classId:', classNodeId);
            API.getNewFeatureCode(classNodeId).then((data) => {
                console.log('Received new feature code:', data);
                setCodeDiff(data);
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching new feature code:', error);
                setLoadingCode(false);
            });
        } else if (selectedType == "delete") {
            API.getDeleteDiffByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                // getCodeDiff(classNodeId)
            })
        } else if (selectedType == "edit" || selectedType === "add") {
            if (!confirmEnabled) {
                // 修改前显示Context
                API.getContextByClass(classNodeId).then((data) => {
                    setCodeDiff(data)
                    setLoadingCode(false);
                }).catch(error => {
                    console.error('Error fetching code diff:', error)
                    setLoadingCode(false);
                    // getCodeDiff(classNodeId)
                })
            } else {
                // 修改后显示 CodeDiff
                API.getNewDiffByClass(classNodeId).then((data) => {
                    setCodeDiff(data)
                    setLoadingCode(false);
                }).catch(error => {
                    console.error('Error fetching code diff:', error)
                    setLoadingCode(false);
                    // getCodeDiff(classNodeId)
                })
            }

        } else if (selectedType == "select") {
            // 修改前显示Context
            API.getContextByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                // getCodeDiff(classNodeId)
            })
        } else {
            setLoadingCode(false);
            setDiffDrawerOpen(false);
            alert(copy.noAction)
        }

    }

    const [modal, contextHolder] = Modal.useModal();

    const onClickItem = (item) => {
        if (selectedFeatureItem == null || item.featureId != selectedFeatureItem.featureId) {
            handleSelect(item)
        }
    }

    const handleSelect = (item, onSelected) => {
        if (selectedType == "add" || selectedType == "edit") {
            modal.confirm({
                title: copy.switchTitle,
                icon: <ExclamationCircleOutlined/>,
                content: copy.switchDescription,
                okText: copy.discard,
                cancelText: copy.cancel,
                onOk: async () => {
                    await getFeatureData()
                    goSelect(item)
                    onSelected?.()
                }
            });
        } else {
            goSelect(item)
            onSelected?.()
        }
    }

    const goSelect = (item) => {
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("select")
        getFeatureGraphData(item.featureId, "select")
        setConfirmEnabled(false)
    }

    const handleDelete = (item) => {
        if (selectedType == "add" || selectedType == "edit") {
            modal.confirm({
                title: copy.switchTitle,
                icon: <ExclamationCircleOutlined/>,
                content: copy.switchDescription,
                okText: copy.discard,
                cancelText: copy.cancel,
                onOk: async () => {
                    await getFeatureData()
                    goDelete(item)
                }
            });
        } else {
            goDelete(item)
        }
    }

    const goDelete = (item) => {
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("delete")
        getFeatureGraphData(item.featureId, "delete")
        setConfirmEnabled(true)
    }

    const handleEdit = (item) => {
        if (selectedType == "add" || (selectedType == "edit" && selectedFeatureItem != null && selectedFeatureItem.featureId != item.featureId)) {
            modal.confirm({
                title: copy.switchTitle,
                icon: <ExclamationCircleOutlined/>,
                content: copy.switchDescription,
                okText: copy.discard,
                cancelText: copy.cancel,
                onOk: async () => {
                    await getFeatureData()
                    goEdit(item)
                }
            });
        } else {
            goEdit(item)
        }
    }

    const goEdit = (item) => {
        setSubmitEnabled(true);
        setConfirmEnabled(false);
        setModeTrans(false);
        setChatMode(false);


        setSelectedFeatureItem(item)
        setSelectedType("edit")
        setEditedText(getFeatureDescription(item, language))
        getFeatureGraphData(item.featureId, "edit")
    }

    const [editedText, setEditedText] = useState('')
    const [activeKey, setActiveKey] = useState(null);
    const [featureSearchText, setFeatureSearchText] = useState('');
    const [featureScrollTarget, setFeatureScrollTarget] = useState(null);
    const featureScrollContainerRef = useRef(null);

    const featureSearchOptions = useMemo(() => {
        const query = featureSearchText.trim().toLocaleLowerCase();
        if (!query) return [];

        const matches = [];
        featureData.forEach((module, moduleIndex) => {
            const displayedModuleDescription = getModuleDescription(module, language);
            const searchableModuleDescriptions = [
                getModuleDescription(module, "zh"),
                getModuleDescription(module, "en"),
            ];

            module.featureList.forEach((item) => {
                const displayIndex = getFeatureDisplayIndex(module.featureList, item);
                const itemNumber = `${moduleIndex + 1}.${displayIndex}`;
                const displayedDescription = getFeatureDescription(item, language);
                const searchableText = [
                    itemNumber,
                    item.featureId,
                    getFeatureDescription(item, "zh"),
                    getFeatureDescription(item, "en"),
                    ...searchableModuleDescriptions,
                ].join(" ").toLocaleLowerCase();

                if (!searchableText.includes(query)) return;

                const selectionText = `${itemNumber} ${displayedDescription}`.trim();
                matches.push({
                    key: `${String(module.moduleId)}-${String(item.featureId)}`,
                    value: selectionText,
                    moduleId: String(module.moduleId),
                    featureId: String(item.featureId),
                    label: (
                        <div className={styles.featureSearchOption}>
                            <div className={styles.featureSearchOptionTitle}>{selectionText}</div>
                            <div className={styles.featureSearchOptionModule}>
                                {`${moduleIndex + 1}. ${displayedModuleDescription}`}
                            </div>
                        </div>
                    ),
                });
            });
        });

        return matches.slice(0, 50);
    }, [featureData, featureSearchText, language]);

    useEffect(() => {
        if (!featureScrollTarget || String(activeKey) !== featureScrollTarget.moduleId) {
            return undefined;
        }

        const timeoutId = window.setTimeout(() => {
            const targetElement = Array.from(
                featureScrollContainerRef.current?.querySelectorAll("[data-feature-id][data-module-id]") || []
            ).find((element) => (
                element.dataset.featureId === featureScrollTarget.featureId
                && element.dataset.moduleId === featureScrollTarget.moduleId
            ));

            if (!targetElement) return;

            const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
            targetElement.scrollIntoView({
                behavior: reduceMotion ? "auto" : "smooth",
                block: "center",
                inline: "nearest",
            });
            setFeatureScrollTarget(null);
        }, 250);

        return () => window.clearTimeout(timeoutId);
    }, [activeKey, featureData, featureScrollTarget]);

    const handleFeatureSearchSelect = (value) => {
        const selectedOption = featureSearchOptions.find((option) => option.value === value);
        if (!selectedOption) return;

        const targetModule = featureData.find(
            (module) => String(module.moduleId) === selectedOption.moduleId
        );
        const targetFeature = targetModule?.featureList.find(
            (feature) => String(feature.featureId) === selectedOption.featureId
        );
        if (!targetModule || !targetFeature) return;

        setFeatureSearchText(value);
        handleSelect(targetFeature, () => {
            setDiffDrawerOpen(false);
            setActiveKey(targetModule.moduleId);
            setFeatureScrollTarget({
                moduleId: selectedOption.moduleId,
                featureId: selectedOption.featureId,
            });
        });
    };

    const changeActiveKey = (newActiveKey) => {
        if (selectedType == "edit" || selectedType == "add") {
            modal.confirm({
                title: copy.switchTitle,
                icon: <ExclamationCircleOutlined/>,
                content: copy.switchDescription,
                okText: copy.discard,
                cancelText: copy.cancel,
                onOk: async () => {
                    await getFeatureData()
                    setSelectedType(null)
                    setSelectedFeatureItem(null)
                    setActiveKey(newActiveKey)
                }
            });
        } else {
            setActiveKey(newActiveKey)
            setSelectedFeatureItem(null)
        }
    }

    // 新增元素模板，根据需求修改
    const createNewFeature = (moduleId) => {
        let timestamp = Date.now();
        return {
            featureId: `new-${timestamp}`, // 唯一id
            featureDescription: `${copy.newSubfeature}${timestamp}`,
            isNew: true,
            moduleId: moduleId, // 添加moduleId
        }
    }

    const handleAdd = (module) => {
        if (selectedType == "edit" || (selectedType == "add" && activeKey != module.moduleId)) {
            modal.confirm({
                title: copy.switchTitle,
                icon: <ExclamationCircleOutlined/>,
                content: copy.switchDescription,
                okText: copy.discard,
                cancelText: copy.cancel,
                onOk: async () => {
                    await getFeatureData()
                    goAdd(module)
                }
            });
        } else if (selectedType == "add" && activeKey == module.moduleId) {
            return
        } else {
            goAdd(module)
        }
    }

    // Add 按钮事件
    const goAdd = (module) => {
        setSubmitEnabled(true)
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);


        setActiveKey(module.moduleId)
        setSelectedType("add")

        let newFeatureItem = createNewFeature(module.moduleId);

        setFeatureData((prev) =>
            prev.map((m) => {
                if (m.moduleId === module.moduleId) {
                    return {
                        ...m,
                        featureList: [newFeatureItem, ...m.featureList],
                    };
                }
                return m;
            })
        );
        setEditedText(newFeatureItem.featureDescription)
        setSelectedFeatureItem(newFeatureItem)
        getFeatureGraphData(null, "add")

    };

    const [chatMode, setChatMode] = useState(false)
    const [chatContent, setChatContent] = useState("")
    const containerRef = useRef(null);
    const [modeTrans, setModeTrans] = useState(false)

    useEffect(() => {
        if (containerRef.current) {
            requestAnimationFrame(() => {
                containerRef.current.scrollTop = containerRef.current.scrollHeight;
            });
        }
    }, [chatContent]);


    const handleChat = (eventSource) => {
        setChatContent("");
        setChatMode(true)
        setModeTrans(true)
        eventSource.onmessage = (event) => {
            const decoded = decodeURIComponent(escape(atob(event.data)));
            setChatContent(prev => prev + decoded);
        };
        eventSource.onerror = () => {

            eventSource.close(); // 关闭连接
            setLoadingFeatureList(false);
            setConfirmEnabled(true)

            setTimeout(() => {
                getFeatureGraphData(0, "new")
            }, 1500); // 延迟 3000 毫秒（3秒）

            setTimeout(() => {
                setChatMode(false);
            }, 3000); // 延迟 3000 毫秒（3秒）
        };
        return () => {
            eventSource.close();
        };
    }

    const [confirmEnabled, setConfirmEnabled] = useState(false)
    const [submitEnabled, setSubmitEnabled] = useState(false)

    const submitEdit = (item) => {
        if (!selectedModel) return;

        setLoadingFeatureList(true)
        setSubmitEnabled(false)

        if (selectedType == 'edit') {
            // console.log(editedText);
            API.modifyFeature(editedText, apiLanguage)
                .then((data) => {
                    handleChat(API.getLlmResponse(apiLanguage, selectedModel))
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                });
        } else if (selectedType == 'add') {
            // 构建请求参数，包含moduleId
            const requestData = {
                featureDescription: editedText,
                moduleId: item.moduleId || selectedFeatureItem.moduleId,
                language: apiLanguage,
            };

            API.addFeature(requestData)
                .then((data) => {
                    handleChat(API.getLlmResponse(apiLanguage, selectedModel))
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                });
        }
    }

    const [loadingConfirm, setLoadingConfirm] = useState(false);

    const confirmDiff = () => {
        if (selectedType == 'delete') {
            setLoadingConfirm(true);
            API.confirmDelete()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    console.log(activeKey);
                    // 在新的数据里找到当前 activeKey 对应的模块
                    const activeModule = res.find(m => m.moduleId == activeKey);
                    console.log(activeModule);
                    if (activeModule && activeModule.featureList.length > 0) {
                        handleSelect(activeModule.featureList[0]);
                    } else {
                        setActiveKey(res[0].moduleId)
                        handleSelect(res[0].featureList[0])
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Delete Feature:', error)
                });
        } else if (selectedType == 'edit') {
            setLoadingConfirm(true);
            API.confirmModify()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    // console.log(activeKey);
                    // 在 res 中找到指定 featureId 的对象
                    const featureId = selectedFeatureItem.featureId;
                    let foundFeature = null;

                    for (const module of res) {
                        const match = module.featureList.find(f => f.featureId === featureId);
                        if (match) {
                            foundFeature = match;
                            break; // 找到就退出
                        }
                    }

                    if (foundFeature) {
                        // 传递整个对象给 handleSelect
                        goSelect(foundFeature);
                    } else {
                        console.warn("未找到 featureId:", featureId);
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Delete Feature:', error)
                });
        }else if (selectedType == 'add') {
            setLoadingConfirm(true);
            API.confirmAdd()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    // console.log(activeKey);
                    // 在 res 中找到指定 featureId 的对象
                    const featureId = data;
                    let foundFeature = null;

                    for (const module of res) {
                        const match = module.featureList.find(f => f.featureId === featureId);
                        if (match) {
                            foundFeature = match;
                            break; // 找到就退出
                        }
                    }

                    if (foundFeature) {
                        // 传递整个对象给 handleSelect
                        goSelect(foundFeature);
                    } else {
                        console.warn("未找到 featureId:", featureId);
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Delete Feature:', error)
                });
        }else{
            alert(copy.completed)
        }

    }


    return (
        <>
            {contextHolder}
            <Spin
                wrapperClassName={styles.pageSpin}
                spinning={loadingConfirm}
                tip={copy.applyingChanges}
                size={"large"}
            >
                <div className={styles.debloatingPage}>
                    <main
                        className={classNames(styles.workspace, {
                            [styles.workspaceWithDrawer]: diffDrawerOpen,
                        })}
                        onClick={() => {
                            if (diffDrawerOpen) setDiffDrawerOpen(false);
                        }}
                    >
                        <Splitter className={styles.background_area}>
                    {/*左侧可滚动功能列表 */}
                    <Splitter.Panel
                        defaultSize="45%"
                        min="20%"
                        max="60%"
                        className={styles.main_area}
                    >
                        <Card
                            className={classNames(styles.panelCard, styles.featurePanelCard, {
                                [styles.featurePanelCardCompressed]: diffDrawerOpen,
                            })}
                            title={
                                <div className={styles.card_title} title={copy.featurePanel}>
                                    {copy.featurePanel}
                                </div>
                            }
                            extra={
                                <label className={styles.modelControl}>
                                    <span className={styles.modelLabel}>{copy.model}</span>
                                    <Select
                                        aria-label={copy.model}
                                        className={styles.modelSelect}
                                        value={selectedModel}
                                        options={models.map((model) => ({value: model, label: model}))}
                                        onChange={setSelectedModel}
                                        loading={loadingModels}
                                        disabled={loadingModels || models.length === 0 || loadingFeatureList}
                                        placeholder={loadingModels ? copy.loadingModels : copy.modelUnavailable}
                                        showSearch
                                        optionFilterProp="label"
                                        popupMatchSelectWidth={false}
                                        size="small"
                                    />
                                </label>
                            }
                            bordered={false}
                            bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                        >
                            <div className={styles.featurePanelBody}>
                                <AutoComplete
                                    className={styles.featureSearch}
                                    value={featureSearchText}
                                    options={featureSearchOptions}
                                    onChange={setFeatureSearchText}
                                    onSelect={handleFeatureSearchSelect}
                                    filterOption={false}
                                    notFoundContent={featureSearchText.trim() ? copy.noFeatureMatches : null}
                                    disabled={loadingFeatureList || featureData.length === 0}
                                >
                                    <Input
                                        allowClear
                                        prefix={<SearchOutlined/>}
                                        placeholder={copy.searchFeatures}
                                        aria-label={copy.searchFeatures}
                                    />
                                </AutoComplete>
                                <Spin
                                    wrapperClassName={classNames(styles.panelSpin, styles.featureListSpin)}
                                    spinning={loadingFeatureList}
                                    tip={copy.fetchingFeatureSummary}
                                    size="large"
                                >
                                <div ref={featureScrollContainerRef} className={styles.scrollContainer}>
                                    <Collapse
                                        accordion
                                        bordered={false}
                                        className={styles.moduleCollapse}
                                        activeKey={activeKey}
                                        onChange={(activeKey) => changeActiveKey(activeKey)}
                                    >
                                        {featureData.map((module, moduleIndex) => (
                                            <Panel
                                                header={
                                                    <div className={styles.moduleTitle}>
                                                        <div>{`${moduleIndex + 1}. ${getModuleDescription(module, language)}`}</div>
                                                        <div className={styles.iconContainer}>
                                                            <Button
                                                                type="text"
                                                                icon={<PlusSquareTwoTone twoToneColor="#13B54D"/>}
                                                                onClick={(e) => {
                                                                    e.stopPropagation()
                                                                    handleAdd(module)
                                                                }}
                                                                className={classNames(styles.icon, {
                                                                    [styles.selectedIcon]: module.moduleId === activeKey && (selectedType === 'add'),
                                                                })}
                                                            />
                                                        </div>
                                                    </div>
                                                }
                                                key={module.moduleId}
                                            >
                                                <List
                                                    dataSource={module.featureList}
                                                    renderItem={(item, featureIndex) => {
                                                        // // 调试信息：打印当前feature的信息
                                                        // if (item.isNewGenerated) {
                                                        //     console.log('Rendering modified feature:', item);
                                                        // }
                                                        const displayIndex = getFeatureDisplayIndex(module.featureList, item);
                                                        return (
                                                            <FeatureListItem
                                                                item={item}
                                                                moduleId={module.moduleId}
                                                                itemNumber={`${moduleIndex + 1}.${displayIndex}`}
                                                                description={getFeatureDescription(item, language)}
                                                                selectedType={selectedType}
                                                                selectedFeatureItem={selectedFeatureItem}
                                                                submitEnabled={submitEnabled}
                                                                selectedModel={selectedModel}
                                                                editedText={editedText}
                                                                setEditedText={setEditedText}
                                                                copy={copy}
                                                                onClickItem={onClickItem}
                                                                submitEdit={submitEdit}
                                                                handleDelete={handleDelete}
                                                                handleEdit={handleEdit}
                                                            />
                                                        );
                                                    }}
                                                />
                                            </Panel>
                                        ))}
                                    </Collapse>
                                </div>
                                </Spin>
                            </div>
                        </Card>
                    </Splitter.Panel>


                    {/*中间功能去臃肿详情*/}
                    <Splitter.Panel min="55%" className={styles.main_area}>
                        <div className={styles.panelShell}>
                            <Card
                                className={styles.panelCard}
                                title={
                                    <div className={styles.card_title}>
                                        <div>
                                            {chatMode ? copy.agentPanel : copy.graphPanel}
                                        </div>
                                        <div className={styles.iconContainer}>
                                            <Button
                                                type="text"
                                                icon={<SwapOutlined/>}
                                                onClick={(e) => {
                                                    setChatMode(!chatMode)
                                                }}
                                                className={styles.icon}
                                                disabled={!modeTrans}
                                            />
                                        </div>
                                    </div>
                                }
                                bordered={false}
                                bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                            >
                                <div className={classNames(styles.panelView, {
                                    [styles.panelViewHidden]: !chatMode,
                                })}>
                                    <div ref={containerRef} className={styles.scrollContainer}>
                                        <MarkdownRendererComponent content={chatContent}/>
                                    </div>
                                </div>
                                <div
                                    onClick={(event) => event.stopPropagation()}
                                    className={classNames(styles.panelView, {
                                        [styles.panelViewHidden]: chatMode,
                                    })}
                                >
                                    <Spin
                                        wrapperClassName={styles.panelSpin}
                                        spinning={loadingFeatureGraph}
                                        tip={copy.fetchingGraph}
                                        size={"large"}
                                    >
                                        <FeatureGraph
                                            ref={featureGraphRef}
                                            graphData={graphData}
                                            onNodeClick={getCodeDiff}
                                        />
                                    </Spin>
                                </div>
                            </Card>
                        </div>
                    </Splitter.Panel>

                        </Splitter>
                    </main>

                    <aside
                        className={classNames(styles.diffDrawer, {
                            [styles.diffDrawerOpen]: diffDrawerOpen,
                        })}
                        aria-hidden={!diffDrawerOpen}
                        aria-label={copy.changesPanel}
                    >
                        <header className={styles.diffDrawerHeader}>
                            <div className={styles.diffDrawerTitle}>
                                <h2>{copy.changesPanel}</h2>
                                {selectedCodeNodeId && <p>{selectedCodeNodeId}</p>}
                            </div>
                            <Button
                                type="text"
                                icon={<CloseOutlined/>}
                                aria-label={copy.closeChangesPanel}
                                className={styles.diffDrawerClose}
                                onClick={() => setDiffDrawerOpen(false)}
                            />
                        </header>
                        <div className={styles.diffDrawerBody}>
                            <Spin spinning={loadingCode} tip={copy.fetchingCode} size="large">
                                <CodeDiffComponent
                                    diffText={codeDiff}
                                    isPlainCode={false}
                                />
                                <Tooltip
                                    title={!confirmEnabled ? copy.noSubmittedChanges : ""}
                                >
                                    <Popconfirm title={copy.confirmAllChanges}
                                                description={copy.reviewAllChanges}
                                                onConfirm={confirmDiff}
                                                okText={copy.confirmApply}
                                                cancelText={copy.decline}
                                    >
                                        <div className={styles.centerButtonWrapper}>
                                            <Button
                                                type="primary"
                                                // onClick={confirmDiff}
                                                disabled={!confirmEnabled}
                                            >
                                                {copy.applyChanges}
                                            </Button>
                                        </div>
                                    </Popconfirm>
                                </Tooltip>
                            </Spin>
                        </div>
                    </aside>
                </div>
            </Spin>
        </>

    )
}

export default DebloatingPage;
