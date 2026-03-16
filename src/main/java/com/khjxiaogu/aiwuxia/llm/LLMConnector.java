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
package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.khjxiaogu.aiwuxia.llm.providers.DeepseekModelProvider;
import com.khjxiaogu.aiwuxia.llm.providers.VolcanoModelProvider;
/**
 * LLM（大语言模型）连接器，提供统一的静态入口来调用 AI 模型。
 * 该类通过 {@link ModelRouter} 路由请求到合适的 {@link ModelProvider}，
 * 并执行模型调用。初始化时需要根据系统属性配置可用的模型提供商。
 * 设计为工具类，禁止实例化。
 */
public class LLMConnector {

    /** 模型路由器实例，负责根据请求选择合适的模型提供商 */
    private static ModelRouter router;

    /**
     * 私有构造函数，防止外部实例化。
     */
    private LLMConnector() {}

    /**
     * 初始化默认的模型提供商和路由器。
     * 该方法检查系统属性，并根据属性是否存在添加对应的模型提供商：
     * <ul>
     *   <li>如果系统属性 "deepseektoken" 存在，则添加 {@link DeepseekModelProvider}。</li>
     *   <li>如果系统属性 "volcmodeltoken" 存在，则添加 {@link VolcanoModelProvider}。</li>
     * </ul>
     * 然后使用 {@link DefaultModelRouter} 包装这些提供商作为默认路由器。
     * 该方法应在应用启动时调用一次，以确保路由器已初始化。
     */
    public static void initDefault() {
        List<ModelProvider> providers = new ArrayList<>();
        if (System.getProperty("deepseektoken") != null)
            providers.add(new DeepseekModelProvider());
        if (System.getProperty("volcmodeltoken") != null)
            providers.add(new VolcanoModelProvider());
        router = new DefaultModelRouter(providers);
    }

    /**
     * 调用 AI 模型，返回输出结果。
     * 该方法首先通过路由器选择合适的模型提供商，然后调用其 {@code execute} 方法执行请求。
     *
     * @param request 包含所有必要参数的 AI 请求对象
     * @return AI 模型的输出对象 {@link AIOutput}
     * @throws ModelRouteException 如果没有找到能够处理该请求的模型提供商
     * @throws IOException 如果在执行模型调用过程中发生 I/O 错误
     */
    public static AIOutput call(AIRequest request) throws ModelRouteException, IOException {
        return router.route(request).execute(request);
    }
}