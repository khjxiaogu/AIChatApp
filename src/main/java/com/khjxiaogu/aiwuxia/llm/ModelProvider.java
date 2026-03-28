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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * 模型提供者接口，用于统一封装对不同 AI 模型（如本地部署、云端 API 等）的调用。
 * 实现类负责根据请求类型判断是否支持，并执行具体的模型调用，返回输出结果。
 */
public interface ModelProvider {

    /**
     * 判断当前提供者是否支持处理给定的请求。
     * 可根据请求中的模型类别、多模态类型、推理强度等属性进行判断。
     *
     * @param request 待检查的 AI 请求对象
     * @return 如果支持该请求则返回 true，否则返回 false
     */
    boolean supports(AIRequest request);

    /**
     * 执行 AI 模型调用，并返回输出结果。
     * 该方法可能会阻塞直到模型响应完成或发生 I/O 错误。
     *
     * @param request 包含所有必要参数的 AI 请求对象
     * @return AI 模型的输出对象，包含推理内容和最终内容
     * @throws IOException 如果网络通信、数据读取或处理过程中发生 I/O 错误
     */
    AIOutput execute(ExecutorService executor,AIRequest request) throws IOException;
}