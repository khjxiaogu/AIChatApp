package com.khjxiaogu.aiwuxia.llm;

import java.io.IOException;

public interface ModelProvider {
    /**
     * 判断该 Provider 是否支持当前请求
     */
    boolean supports(AIRequest request);

    /**
     * 执行请求，返回统一输出
     * @throws IOException 
     */
    AIOutput execute(AIRequest request) throws IOException;
}