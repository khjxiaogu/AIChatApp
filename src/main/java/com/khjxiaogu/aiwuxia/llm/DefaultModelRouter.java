package com.khjxiaogu.aiwuxia.llm;

import java.util.List;

public class DefaultModelRouter implements ModelRouter {
    private final List<ModelProvider> providers;

    public DefaultModelRouter(List<ModelProvider> providers) {
        this.providers = providers;
    }

    @Override
    public ModelProvider route(AIRequest request) {
        // 简单策略：找第一个支持该请求的 Provider
        // 实际生产中可以做优先级排序、负载均衡、降级策略
        return providers.stream()
                .filter(p -> p.supports(request))
                .findFirst()
                .orElseThrow(() -> new ModelRouteException("No suitable model found for request: " + request.taskType));
    }
}
