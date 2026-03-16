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
