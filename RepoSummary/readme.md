# 代码仓库分析工具 (RepoSummary)

一个智能的Java代码仓库分析工具，能够自动分析项目结构、提取方法信息、进行聚类分析，并使用AI生成功能摘要。

## 功能特性

- **代码结构分析**: 自动解析Java文件，提取类、方法、依赖关系
- **智能聚类**: 基于相似度和调用关系对文件和函数进行聚类
- **AI摘要生成**: 使用OpenAI API生成自然语言的功能描述
- **结构化输出**: 生成CSV格式的结构化数据，便于后续分析

## 环境要求

- Python >= 3.8
- pip >= 20.0

## 安装依赖

```bash
pip install -r requirements.txt
```

## 配置环境变量

创建 `.env` 文件，包含以下配置：

```env
OPENAI_API_KEY=你的OpenAI API密钥
OPENAI_BASE_URL=你的OpenAI API基础URL
```

## 使用方法

### 命令行使用


## 项目结构

```
RepoSummary/
├── requirements.txt          # 项目依赖
├── README.md                # 项目说明
├── .env                     # 环境变量配置
├── data/                    # 示例数据目录
│   └── aurora/             # Java项目示例
└── src/                    # 源代码目录
    ├── main.py             # 主程序入口
    ├── models.py           # 数据模型定义
    ├── utils.py            # 工具函数
    ├── clustering.py       # 聚类算法
    ├── ai_summarizer.py    # AI摘要生成
    └── structure_analsis/  # 代码结构分析
        └── java/
            ├── java_import_analyzer.py
            └── java_method_analyzer.py
```

## 输出文件

分析完成后，会在输出目录生成以下文件：

- `file_adj_matrix.csv`: 文件依赖关系矩阵
- `method.csv`: 方法详细信息
- `method_adj_matrix.csv`: 方法调用关系矩阵
- `features_summary.csv`: 功能特性摘要

## 分析流程

1. **代码结构分析**: 解析Java文件，提取类和方法信息
2. **依赖关系分析**: 分析文件间的导入依赖和方法调用关系
3. **文件聚类**: 基于相似度对文件进行聚类
4. **方法聚类**: 将相关方法聚类为功能特性
5. **AI摘要生成**: 使用大语言模型生成功能描述
6. **结果输出**: 保存结构化数据到CSV文件

## 主要模块说明

### RepoAnalyzer (主分析器)
- 协调整个分析流程
- 管理数据流和状态
- 提供统一的接口

### JavaImportAnalyzer (导入分析器)
- 解析Java文件的import语句
- 构建文件间的依赖关系图
- 生成邻接矩阵

### JavaMethodAnalyzer (方法分析器)
- 提取方法的详细信息
- 分析方法间的调用关系
- 生成方法调用图

### AISummarizer (AI摘要器)
- 使用OpenAI API生成功能描述
- 将技术代码转换为用户故事
- 合并相关功能为模块

### 聚类算法
- 基于Leiden算法的社区发现
- 支持相似度和结构信息的融合
- 自动优化聚类参数

## 示例输出

功能特性摘要示例：

```csv
id,cluster_id,module_desc,desc,method_name,flow,notf
1,7,Website Management,As a website administrator, I want to manage website content...,com.aurora.controller.AuroraInfoController.updateWebsiteConfig,- Step 1: Controller receives POST request...,- Security: Role checks for admin access...
```

## 扩展功能

### 支持其他编程语言
可以通过扩展 `structure_analsis` 模块来支持其他编程语言：

1. 创建新的语言分析器（如 `python_analyzer.py`）
2. 实现相应的解析逻辑
3. 在主程序中集成

### 自定义聚类算法
可以修改 `clustering.py` 中的算法参数：

- 调整相似度权重
- 修改聚类分辨率参数
- 自定义评估指标

### 集成其他AI模型
可以扩展 `ai_summarizer.py` 来支持其他AI服务：

- 支持本地模型
- 集成其他云服务
- 自定义提示词模板

## 故障排除

### 常见问题

1. **OpenAI API错误**
   - 检查API密钥是否正确
   - 确认网络连接正常
   - 检查API配额是否充足

2. **Java解析错误**
   - 确保Java代码语法正确
   - 检查文件编码格式
   - 处理特殊字符和注释

3. **内存不足**
   - 对于大型项目，考虑分批处理
   - 调整聚类参数减少计算量
   - 使用更高效的算法

### 调试模式

启用详细日志输出：


## 贡献指南

欢迎提交Issue和Pull Request来改进这个项目！

### 开发环境设置

1. 克隆项目
2. 安装开发依赖
3. 运行测试
4. 提交代码

### 代码规范

- 遵循PEP 8代码风格
- 添加适当的文档字符串
- 编写单元测试
- 使用类型注解

## 许可证

本项目采用MIT许可证，详见LICENSE文件。

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交GitHub Issue
- 发送邮件至项目维护者
- 参与项目讨论

---

**注意**: 使用本工具时请确保遵守相关法律法规和API使用条款。