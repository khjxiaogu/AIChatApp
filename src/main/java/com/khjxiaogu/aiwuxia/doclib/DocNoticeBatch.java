package com.khjxiaogu.aiwuxia.doclib;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一批待注入给某个监听 agent 的文档变更通知。 */
public final class DocNoticeBatch {

    public static final class Item {
        public final String docId;
        public final String action;
        public final long revision;
        public final long fromRevision;
        public final String author;
        public final String summary;

        public Item(String docId, String action, long revision, long fromRevision, String author, String summary) {
            this.docId = docId;
            this.action = action;
            this.revision = revision;
            this.fromRevision = fromRevision;
            this.author = author;
            this.summary = summary == null ? "" : summary;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private long lastSeq;
    private boolean wake;
    private final long createdAt = System.currentTimeMillis();

    public synchronized void add(Item item, long seq) {
        if (item == null) {
            return;
        }
        // 同一批里同一文档只保留最新修订
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).docId.equals(item.docId)) {
                if (item.revision >= items.get(i).revision) {
                    items.set(i, item);
                }
                lastSeq = Math.max(lastSeq, seq);
                return;
            }
        }
        items.add(item);
        lastSeq = Math.max(lastSeq, seq);
    }

    public synchronized boolean isEmpty() {
        return items.isEmpty();
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized long lastSeq() {
        return lastSeq;
    }

    public synchronized boolean isWake() {
        return wake;
    }

    public synchronized void setWake(boolean wake) {
        this.wake = wake;
    }

    public synchronized List<Item> items() {
        return new ArrayList<>(items);
    }

    /** 把多个批次合并成一批，保留最大 seq。 */
    public static DocNoticeBatch merge(List<DocNoticeBatch> batches) {
        DocNoticeBatch out = new DocNoticeBatch();
        for (DocNoticeBatch batch : batches) {
            if (batch == null) {
                continue;
            }
            out.wake |= batch.wake;
            for (Item item : batch.items()) {
                out.add(item, batch.lastSeq);
            }
            out.lastSeq = Math.max(out.lastSeq, batch.lastSeq);
        }
        return out;
    }

    public synchronized String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("<doc-update library=\"").append(escape(libraryId)).append("\" count=\"")
                .append(items.size()).append("\" at=\"")
                .append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(createdAt)))
                .append("\">\n");
        for (Item item : items) {
            sb.append("  <doc id=\"").append(escape(item.docId))
                    .append("\" action=\"").append(escape(item.action))
                    .append("\" revision=\"").append(item.revision)
                    .append("\" from=\"").append(item.fromRevision)
                    .append("\" by=\"").append(escape(item.author)).append("\"");
            if (!item.summary.isEmpty()) {
                sb.append(" change=\"").append(escape(item.summary)).append("\"");
            }
            sb.append(" />\n");
        }
        sb.append("</doc-update>\n");
        sb.append("这些文档在你上次读取后发生了变化；它们的内容不会被自动注入。修改前请先 doc_read。");
        return sb.toString();
    }

    private String libraryId = "";

    public synchronized void setLibraryId(String libraryId) {
        this.libraryId = libraryId == null ? "" : libraryId;
    }

    /** 统一转义 XML 属性里的特殊字符。 */
    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 用于日志/调试：不依赖 libraryId 的条目数。 */
    public synchronized Map<String, Long> latestByDoc() {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Item item : items) {
            map.put(item.docId, item.revision);
        }
        return map;
    }
}
