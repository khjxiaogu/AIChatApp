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

import java.util.Comparator;
import java.util.List;

public class DefaultModelRouter implements ModelRouter {
    private final List<ModelProvider> providers;

    public DefaultModelRouter(List<ModelProvider> providers) {
        this.providers = providers;
    }

    @Override
    public ModelProvider route(AIRequest request) {
        // 1. 明确指定了模型：优先按提示路由，此时忽略预算
        if (request.hasModelHint()) {
            ModelProvider hinted = providers.stream()
                    .filter(p -> p.supportsHinted(request))
                    .filter(p -> p.supports(request))
                    .findFirst()
                    .orElse(null);
            if (hinted != null) {
                return hinted;
            }
            // 提示对应的提供商未配置/不可用时，继续按预算或默认顺序回退
        }
        // 2. 没有模型提示（或提示未命中）：按预算选价格最接近的提供商
        if (request.budget >= 0) {
            return providers.stream()
                    .filter(p -> p.supports(request))
                    .min(Comparator.comparingDouble(p -> Math.abs(p.budget(request) - request.budget)))
                    .orElseThrow(() -> noProvider(request));
        }
        // 3. 默认按提供商的定义顺序取第一个可用的
        return providers.stream()
                .filter(p -> p.supports(request))
                .findFirst()
                .orElseThrow(() -> noProvider(request));
    }

    /** 没有可用提供商时的统一异常。 */
    private static ModelRouteException noProvider(AIRequest request) {
        return new ModelRouteException("No suitable model found for request: " + request.taskType);
    }
}
