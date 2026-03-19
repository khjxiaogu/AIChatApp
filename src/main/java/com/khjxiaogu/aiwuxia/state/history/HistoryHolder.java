package com.khjxiaogu.aiwuxia.state.history;

import java.util.Iterator;

import com.khjxiaogu.aiwuxia.state.Role;

/**
 * 表示一个持有多个历史条目（{@link HistoryItem}）的容器。
 * 该接口扩展了 {@link Iterable}，允许通过迭代器遍历其中的条目。
 * 主要用于存储、管理和操作对话或上下文的历史记录。
 */
public interface HistoryHolder extends Iterable<HistoryItem> {

    /**
     * 判断该容器是否为空（即不包含任何历史条目）。
     *
     * @return 如果容器为空则返回 true，否则返回 false
     */
    boolean isEmpty();

    /**
     * 返回一个按添加顺序正向遍历所有历史条目的迭代器。
     *
     * @return 正向迭代器，类型为 {@link Iterator}&lt;{@link HistoryItem}&gt;
     */
    Iterator<HistoryItem> iterator();

    /**
     * 向容器中添加一个新的历史条目。
     *
     * @param role           条目的角色（如用户、助手、系统等），不能为 null
     * @param content        条目的显示内容（通常用于 UI 展示）
     * @param fullContent    条目的完整上下文内容（可能包含更多内部信息），可为 null
     * @param isValidContext 指示该条目是否可作为有效的上下文内容（例如用于后续对话）
     * @return 新创建并添加到容器中的 {@link HistoryItem} 对象
     */
    HistoryItem add(Role role, String content, String fullContent, boolean isValidContext);

    /**
     * 清空容器，移除所有历史条目。
     */
    void clear();

    /**
     * 返回一个按添加顺序逆向遍历所有历史条目的迭代器。
     * 可用于从最新到最旧的顺序访问条目。
     *
     * @return 反向迭代器，类型为 {@link Iterator}&lt;{@link HistoryItem}&gt;
     */
    Iterator<HistoryItem> reverseIterator();

    /**
     * 返回当前容器中历史条目的数量。
     *
     * @return 条目总数
     */
    int size();

    /**
     * 根据指定的唯一标识符移除对应的历史条目。
     *
     * @param identifier 要移除的条目的标识符（参见 {@link HistoryItem#getIdentifier()}）
     */
    void removeOf(int identifier);

    /**
     * 查看容器中的最后一个历史条目（即最新添加的条目），但不会将其移除。
     *
     * @return 最后一个 {@link HistoryItem}，如果容器为空则返回 null
     */
    default HistoryItem peekLast() {
		return reverseIterator().next();
	}

    /**
     * 返回一个迭代器，仅遍历那些被标记为“有效上下文”的历史条目。
     * 有效上下文的判断依据是添加条目时的 {@code isValidContext} 参数。
     *
     * @return 仅包含有效上下文条目的迭代器
     */
    Iterator<HistoryItem> validContextIterator();

    /**
     * 添加一个新的历史条目，使用默认的上下文内容为 null。
     * 这是一个便捷的默认方法，内部调用四参数 {@link #add(Role, String, String, boolean)} 方法，
     * 并将 {@code isValidContext} 设置为指定值。
     *
     * @param role           条目的角色
     * @param displayContent 显示内容
     * @param isValidContext 指示该条目是否可作为有效的上下文内容
     * @return 新创建并添加的 {@link HistoryItem} 对象
     */
    default HistoryItem add(Role role, String displayContent, boolean isValidContext) {
        return add(role, displayContent, null, isValidContext);
    }

    /**
     * 添加一个新的历史条目，使用默认的有效上下文标志为 true。
     * 这是一个便捷的默认方法，内部调用四参数 {@link #add(Role, String, String, boolean)} 方法，
     * 并将 {@code isValidContext} 设置为 true。
     *
     * @param role            条目的角色
     * @param displayContent  显示内容
     * @param contextContent  完整的上下文内容
     * @return 新创建并添加的 {@link HistoryItem} 对象
     */
    default HistoryItem add(Role role, String displayContent, String contextContent) {
        return add(role, displayContent, contextContent, true);
    }

    /**
     * 移除并返回容器中的最后一个历史条目（即最新添加的条目）。
     *
     * @return 被移除的最后一个 {@link HistoryItem}，如果容器为空则可能返回 null（具体取决于实现）
     */
    HistoryItem removeLast();

    /**
     * 撤回并返回容器中的最后一个历史条目（即最新添加的条目）。
     *
     * @return 被移除的最后一个 {@link HistoryItem}，如果容器为空则可能返回 null（具体取决于实现）
     */
    default HistoryItem deleteLast() {
    	HistoryItem hi=peekLast();
    	hi.setDeleted(true);
    	return hi;
    }
}