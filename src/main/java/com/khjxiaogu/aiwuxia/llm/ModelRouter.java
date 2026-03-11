package com.khjxiaogu.aiwuxia.llm;

public interface ModelRouter {
    ModelProvider route(AIRequest request) throws ModelRouteException;
}