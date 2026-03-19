/*
 * MIT License
 *
 * Copyright (c) 2026 khjxiaogu
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
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