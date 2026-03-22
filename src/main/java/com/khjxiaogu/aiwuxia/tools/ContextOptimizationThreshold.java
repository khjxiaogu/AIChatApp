package com.khjxiaogu.aiwuxia.tools;
public class ContextOptimizationThreshold {
    public static void main(String[] args) {
        // 问题常量定义
        final int NEW_CONVO_TOKENS = 1000;          // 每次对话新增上下文
        final int OPTIMIZED_TOKENS = 9000;         // 优化后的固定上下文
        final int OPT_EXTRA_COST_TOKENS = 5000;    // 优化操作的额外token消耗
        final int MAX_T_ITERATIONS = 20;      // 防止死循环的安全上限
        final int PROMPT_TOKENS=5000;
        // 1. 计算优化前的最小对话次数（优化后必须比优化前短）
        int minPreOptConvos = (OPTIMIZED_TOKENS + NEW_CONVO_TOKENS - 1) / NEW_CONVO_TOKENS; // 向上取整
        // 2. 迭代寻找临界点
        double lastFeed=Double.NEGATIVE_INFINITY;
        for (int preOptConvos = minPreOptConvos; ; preOptConvos++) {
            double sumNoOpt=0;
            long preOptContext=PROMPT_TOKENS+(long) NEW_CONVO_TOKENS * preOptConvos;
            long sumNoOptContext=preOptContext;
            double sumOpt=OPT_EXTRA_COST_TOKENS+sumNoOptContext+0.9*OPTIMIZED_TOKENS;
            long sumOptContext=PROMPT_TOKENS+OPTIMIZED_TOKENS;
            // 迭代优化后的对话次数，寻找首次成本更低的点
            //for (int postOptConvos = 0; postOptConvos < preOptConvos; postOptConvos++) {

                // --------------------------
                // 计算不优化方案的累计费用（纯整数，避免浮点误差）
                // 公式：700*N + 35*N*(N-1)
                // 推导：每次新上下文全费，旧上下文每token0.1，求和化简后
                // --------------------------
                sumNoOpt=preOptConvos*0.1*NEW_CONVO_TOKENS*preOptConvos;
                
                // --------------------------
                // 计算优化方案的累计费用（纯整数）
                // 公式：700*m + 1600*t + 11100
                // 推导：优化费(m*700+3000) + 首次使用优化后(9000+700) + 后续(t-1)次(900+700)
                // (x-25)x=200
                // --------------------------
                sumOpt=OPT_EXTRA_COST_TOKENS+0.9*OPTIMIZED_TOKENS+NEW_CONVO_TOKENS*preOptConvos+OPTIMIZED_TOKENS*0.1*preOptConvos;
                // 检查是否达到临界点
                
           // }
            if (sumNoOpt-sumOpt<lastFeed&&sumNoOpt-sumOpt>0) {
                System.out.println("=============== 优化上下文临界点 ===============");
                System.out.printf("优化前未处理的上下文总量: %,d tokens%n", preOptContext);
                System.out.printf("优化前连续对话次数: %d 次%n", preOptConvos);
                System.out.printf("优化方案累计费用: %d 单位%n", (long)sumOpt);
                System.out.printf("不优化方案累计费用: %d 单位%n", (long)sumNoOpt);
                System.out.println("================================================");
                break;
            }
            System.out.println(preOptConvos+"="+(sumNoOpt-sumOpt));
            lastFeed=sumNoOpt-sumOpt;
        }
    }
}