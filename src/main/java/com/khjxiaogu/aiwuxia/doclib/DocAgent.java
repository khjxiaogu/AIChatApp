package com.khjxiaogu.aiwuxia.doclib;

import java.util.ArrayList;
import java.util.List;

import com.khjxiaogu.aiwuxia.state.session.AIGroupSession;

/** 一个注册到文档库的 agent。 */
public final class DocAgent {

    public final String id;
    public final String displayName;
    public final List<String> listen;
    public final boolean wakeOnNotify;
    public final AIGroupSession session;
    /** 原始事件监听者（通常由 MCP 实现）；不直接注入 agent。 */
    public final DocEventListener listener;
    public final DocLibraryConfig config;

    public DocAgent(String id, String displayName, List<String> listen, boolean wakeOnNotify,
            AIGroupSession session, DocEventListener listener, DocLibraryConfig config) {
        this.id = id;
        this.displayName = displayName == null ? id : displayName;
        this.listen = listen == null ? new ArrayList<String>() : listen;
        this.wakeOnNotify = wakeOnNotify;
        this.session = session;
        this.listener = listener;
        this.config = config;
    }

    /** 是否监听某个文档；支持 `*`、`prefix/*` 和精确 id。 */
    public boolean listens(String docId) {
        for (String pattern : listen) {
            if (pattern == null) {
                continue;
            }
            if ("*".equals(pattern)) {
                return true;
            }
            if (pattern.endsWith("/*")) {
                if (docId.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
            } else if (pattern.endsWith("/")) {
                if (docId.startsWith(pattern)) {
                    return true;
                }
            } else if (pattern.equals(docId)) {
                return true;
            }
        }
        return false;
    }
}
