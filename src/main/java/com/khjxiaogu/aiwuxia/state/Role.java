package com.khjxiaogu.aiwuxia.state;

/**
 * 表示对话中参与方的角色枚举。
 * 每个角色都有对应的内部名称（小写形式）和显示名称（用于UI展示）。
 */
public enum Role {
    /** 助手角色（例如AI模型），显示名称为"系统" */
    ASSISTANT("系统"),
    /** 用户角色，显示名称为"你" */
    USER("你"),
    /** 系统角色，显示名称为"系统" */
    SYSTEM("系统"),
    /** 应用角色，应用需要自行决定显示名称 */
    APPLICATION("");

    /** 角色的小写名称（由枚举常量名转换而来），用于内部标识 */
    final String name;
    /** 角色的显示名称，用于UI展示 */
    final String gotName;

    /**
     * 构造一个角色实例。
     *
     * @param viewName 角色的显示名称
     */
    Role(String viewName) {
        gotName = viewName;
        name = name().toLowerCase();
    }

    /**
     * 获取角色的内部小写名称。
     *
     * @return 内部名称字符串
     */
    public String getRoleName() {
        return name;
    }

    /**
     * 获取角色的显示名称。
     *
     * @return 显示名称字符串
     */
    public String getName() {
        return gotName;
    }
}
