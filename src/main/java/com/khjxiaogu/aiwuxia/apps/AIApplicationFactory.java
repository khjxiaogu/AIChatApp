package com.khjxiaogu.aiwuxia.apps;

import java.io.File;

import com.google.gson.JsonObject;

/**
 * AI 应用工厂接口，用于根据元数据和文件路径创建具体的 {@link AIApplication} 实例。
 * 实现类负责解析配置、加载资源，并返回一个配置好的 AI 应用对象。
 */
public interface AIApplicationFactory {

    /**
     * 创建一个 AI 应用实例。
     *
     * @param basePath    服务的基础路径（根目录），可能用于定位全局资源
     * @param localFolder 当前应用的安装文件夹，通常包含该应用特有的资源、提示词文件等
     * @param modelName   在用户界面上显示的用户友好名称，用于展示给用户
     * @param metaData    应用的配置数据，通常从 meta.json 文件中解析得到，包含启用状态、显示名称、公测标记等
     * @return 新创建的 {@link AIApplication} 实例
     * @throws Throwable 如果创建过程中发生任何错误（如文件读取失败、配置错误等）
     */
    AIApplication createInstance(File basePath, File localFolder, String modelName, JsonObject metaData) throws Throwable;
}