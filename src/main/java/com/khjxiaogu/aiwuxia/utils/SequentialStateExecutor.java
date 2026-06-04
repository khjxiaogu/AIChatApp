package com.khjxiaogu.aiwuxia.utils;


public class SequentialStateExecutor {
    private final Runnable[] tasks;
    private int current;

    /**
     * 构造一个顺序执行器。
     *
     * @param tasks Runnable 数组，可以为 null（视为空数组）
     */
    public SequentialStateExecutor(Runnable... tasks) {
        this.tasks =  tasks;
        this.current = 0;
    }

    /**
     * 获取当前值。
     *
     * @return 当前值
     */
    public int getValue() {
        return current;
    }
    public boolean isValue(int val) {
        return current==val;
    }
    /**
     * 尝试设置新值。
     * <ul>
     *   <li>若新值等于当前值，无操作直接返回。</li>
     *   <li>若新值小于当前值，抛出 IllegalArgumentException。</li>
     *   <li>若新值超出有效范围 [0, tasks.length]，抛出 IllegalArgumentException。</li>
     *   <li>否则，依次执行从 current 到 newValue-1 索引对应的非空 Runnable，最后更新 current 为新值。</li>
     * </ul>
     *
     * @param newValue 要设置的新值
     * @throws IllegalArgumentException 如果新值无效或小于当前值
     */
    public void setValue(int newValue) {
        if (newValue == current) {
            return;
        }
        if (newValue < current) {
            throw new IllegalArgumentException(
                "新值不能小于当前值。当前值: " + current + ", 新值: " + newValue);
        }
        if (newValue > tasks.length) {
            throw new IllegalArgumentException(
                "新值超出范围。允许的最大值: " + tasks.length + ", 实际: " + newValue);
        }

        // 执行从 current 到 newValue-1 的所有非空 Runnable
        for (int i = current; i < newValue; i++) {
        	if(tasks.length>i) {
	            Runnable task = tasks[i];
	            if (task != null) {
	                task.run();
	            }
        	}
        }
        current = newValue;
    }
}