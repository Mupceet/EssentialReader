# 更新文档与代码注释 Spec

## Why
当前 PRD.md 基于源码逆向分析编写，存在部分内容与实际代码不完全一致的问题。需要全面扫描代码库，以实际代码为准更新 PRD.md，同时为关键代码添加注释，并生成技术开发文档和数据库文档。

## What Changes
- 全面扫描 app/src/main/java/io/legado/app/ 下所有 Kotlin 源码，以实际代码为准修正 PRD.md
- 为关键模块添加代码注释（中文），包括数据实体、DAO、模型层、服务层核心类
- 生成技术开发文档 TECH_DOC.md（架构说明、模块职责、核心流程）
- 生成数据库文档 DB_DOC.md（完整的表结构、字段说明、索引、关系图）
- 所有新增文档使用中文编写

## Impact
- Affected specs: 本项目首次 spec
- Affected code: 
  - PRD.md（更新）
  - app/src/main/java/io/legado/app/data/entities/（添加注释）
  - app/src/main/java/io/legado/app/data/dao/（添加注释）
  - app/src/main/java/io/legado/app/model/（添加注释）
  - app/src/main/java/io/legado/app/service/（添加注释）
  - app/src/main/java/io/legado/app/help/（添加注释）
  - 新增 TECH_DOC.md
  - 新增 DB_DOC.md

## ADDED Requirements

### Requirement: 代码注释
系统 SHALL 为关键模块的 Kotlin 源码文件添加中文注释，说明类职责、关键方法和字段用途。

#### Scenario: 数据实体注释
- **WHEN** 开发者查看 entities 目录下的数据类
- **THEN** 每个类应有类级别注释说明其用途，关键字段应有字段注释

#### Scenario: DAO 层注释
- **WHEN** 开发者查看 dao 目录下的数据访问接口
- **THEN** 每个 DAO 接口应有注释说明其职责，关键查询方法应有说明

#### Scenario: 模型层注释
- **WHEN** 开发者查看 model 目录下的业务逻辑类
- **THEN** 每个核心类应有注释说明其业务职责和主要流程

#### Scenario: 服务层注释
- **WHEN** 开发者查看 service 目录下的后台服务
- **THEN** 每个服务类应有注释说明其启动条件和生命周期

### Requirement: 技术开发文档
系统 SHALL 生成 TECH_DOC.md 文件，包含以下内容：
- 项目技术栈概述
- 模块架构与职责说明
- 核心业务流程（书源解析、阅读渲染、缓存下载等）
- 关键设计模式与约定

#### Scenario: 技术文档可读性
- **WHEN** 新开发者阅读 TECH_DOC.md
- **THEN** 能够理解项目整体架构和各模块职责

### Requirement: 数据库文档
系统 SHALL 生成 DB_DOC.md 文件，包含以下内容：
- 数据库版本和基本信息
- 所有数据表的完整字段定义（字段名、类型、默认值、约束、说明）
- 索引定义
- 外键关系
- 实体关系图（Mermaid ER 图）
- 数据库迁移历史概要

#### Scenario: 数据库文档完整性
- **WHEN** 开发者查阅 DB_DOC.md
- **THEN** 能够了解所有表结构和字段含义，无需再查看源码

## MODIFIED Requirements

### Requirement: PRD 更新
系统 SHALL 基于实际代码扫描结果更新 PRD.md，确保：
- 模块架构与代码目录结构一致
- 功能描述与实际实现一致
- 数据模型字段定义与实际数据库 schema 一致

#### Scenario: PRD 准确性
- **WHEN** 对比 PRD.md 与源码
- **THEN** PRD.md 中的技术描述与代码实现无矛盾