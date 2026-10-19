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
package com.khjxiaogu.aiwuxia.state.session;

import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import com.khjxiaogu.aiwuxia.state.Role;
import com.khjxiaogu.aiwuxia.state.history.HistoryItem;
import com.khjxiaogu.aiwuxia.state.history.HistoryWriteContext;
import com.khjxiaogu.aiwuxia.state.history.MutableHistoryItem;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContent;
import com.khjxiaogu.aiwuxia.state.history.message.MessageContents;
import com.khjxiaogu.aiwuxia.state.status.ApplicationState;

/**
 * {@link HistoryWriteContext} 的会话实现。
 * <p>
 * 两种工作方式：
 * </p>
 * <ul>
 *   <li><b>新记录</b>（{@code pending == true}）：直接写入 History 创建的未完成条目，
 *       提交时才进入历史，撤回即硬删除。</li>
 *   <li><b>续写记录</b>（{@code pending == false}）：目标本来就在历史里，
 *       写入通过 {@link com.khjxiaogu.aiwuxia.state.history.HistoryHolder} 的追加接口完成，
 *       这样对只暴露不可变条目的历史实现同样有效。</li>
 * </ul>
 * <p>
 * 每次写入都会同步通知会话（{@link AISession#postMessage(int, Role, MessageContents)}、
 * {@link AISession#appendMessage(int, MessageContent)}、
 * {@link AISession#onReasonerAppend(MessageContent)}），前端协议因此保持不变。
 * </p>
 * <p>
 * 正文由应用的命令线程写入，思维链则由后台的异步读流线程写入，两者可能同时改动同一条记录：
 * 所有写入与结束动作都在本对象的写入锁内串行执行；本记录定型（提交、撤回或关闭）之前会先等
 * 已登记的后台思维链写入收尾，保证记录结束之后不会再有后台写入。
 * </p>
 *
 * @see AISession#beginWrite(Role)
 * @see AISession#continueWrite(Role)
 * @see AISession#beginAsyncReasonerWrite(java.util.function.Supplier, HistoryWriteContext)
 */
class SessionWriteContext implements HistoryWriteContext {

	/** 所属会话，负责前端通知与历史容器访问 */
	private final AISession session;
	/** 本条记录，新记录时同时是可写入的未完成条目 */
	private final HistoryItem item;
	/** 是否为本次新建、尚未进入历史的记录 */
	private final boolean pending;
	/** 前端是否已经知道这条记录（新记录在首次写入时下发，续写的记录本来就已经下发过） */
	private boolean published;
	/** 是否已经提交或撤回 */
	private boolean resolved;
	/**
	 * 本条记录写入的串行锁。
	 * <p>
	 * 正文由应用的命令线程写入，思维链则可能由后台的异步读流线程写入，两者都会改动同一条记录，
	 * 因此所有写入与结束动作都在本锁内完成。
	 * </p>
	 */
	private final Object writeLock = new Object();
	/** 本条记录上还没有收尾的后台思维链写入 */
	private final Set<CompletableFuture<Void>> pendingReasonerWrites = ConcurrentHashMap.newKeySet();

	SessionWriteContext(AISession session, HistoryItem item, boolean pending) {
		this.session = session;
		this.item = item;
		this.pending = pending;
		// 续写的记录本来就在历史里，前端早就收到了它，只有新记录需要首次下发整条消息
		this.published = !pending;
	}

	// ── 写入目标 ──────────────────────────────────────────

	/**
	 * 取得本次写入对应的历史条目。
	 * <p>
	 * 会话用它实现"最后一个历史条目"的语义，取到的始终是真实条目而不是本包装对象，
	 * 这样才能继续用 {@link com.khjxiaogu.aiwuxia.state.history.HistoryHolder} 的条目级接口操作它。
	 * </p>
	 *
	 * @return 本次写入的历史条目
	 */
	HistoryItem item() {
		return item;
	}

	/**
	 * 取得可以写入的条目。
	 *
	 * @return 新记录对应的未完成条目
	 */
	private MutableHistoryItem mutable() {
		return (MutableHistoryItem) item;
	}

	/**
	 * 写入前的检查：上下文已经结束、且续写目标已经不是最后一条记录时不允许继续写入。
	 */
	private void checkWritable() {
		if (resolved)
			throw new IllegalStateException("本次历史写入已经结束");
		if (!pending && session.getHistory().peekLast() != item)
			throw new IllegalStateException("续写的记录已经不是最后一条，不能继续写入");
	}

	// ── 前端通知 ──────────────────────────────────────────

	/**
	 * 首次写入时下发整条消息，之后只下发增量。
	 *
	 * @param appended 本次追加的显示内容
	 */
	private void notifyAppend(MessageContent appended) {
		if (resolved)
			return;
		session.setUpdated();
		if (!published) {
			published = true;
			session.postMessage(item.getIdentifier(), item.getRole(), item.getDisplayContent());
		} else if (appended != null) {
			session.appendMessage(item.getIdentifier(), appended);
		}
	}

	/**
	 * 首次写入时下发整条消息，之后只下发纯文本增量。
	 *
	 * @param appended 本次追加的纯文本内容
	 */
	private void notifyAppend(String appended) {
		if (resolved)
			return;
		session.setUpdated();
		if (!published) {
			published = true;
			session.postMessage(item.getIdentifier(), item.getRole(), item.getDisplayContent());
		} else if (appended != null) {
			session.appendMessage(item.getIdentifier(), appended);
		}
	}

	/**
	 * 首次写入时下发整条消息；已经下发过则不再重复（只写上下文内容时没有增量）。
	 */
	private void notifyWritten() {
		if (resolved)
			return;
		session.setUpdated();
		if (!published) {
			published = true;
			session.postMessage(item.getIdentifier(), item.getRole(), item.getDisplayContent());
		}
	}

	// ── 结束方式 ──────────────────────────────────────────

	/**
	 * 结束前的串行化处理：已经结束时返回 {@code false}，否则等本条记录上的后台思维链写入收尾，
	 * 再标记为已结束并返回 {@code true}。
	 * <p>
	 * 记录定型之后不能再有后台写入，否则内容会写进已经提交或撤回的条目；
	 * 同时这也让后续的提交、撤回动作独占这条记录。
	 * </p>
	 *
	 * @return 调用方可以继续结束流程时返回 {@code true}
	 */
	private boolean beginResolve() {
		synchronized (writeLock) {
			if (resolved)
				return false;
			awaitReasonerWrites();
			resolved = true;
			return true;
		}
	}

	/** 在锁内等待本条记录上的后台思维链写入收尾。 */
	private void awaitReasonerWrites() {
		for (CompletableFuture<Void> writeTask : pendingReasonerWrites) {
			try {
				writeTask.join();
			} catch (CompletionException | CancellationException e) {
				// 读流失败：思维链可能不完整，但记录本身照常结算，失败由持有该任务的调用方上报
			}
			// 后台任务收尾时会自行注销，这里只在它没来得及注销时兜底清理
			pendingReasonerWrites.remove(writeTask);
		}
	}

	@Override
	public void commit() {
		if (!beginResolve())
			return;
		synchronized (writeLock) {
			if (pending) {
				session.getHistory().commitPending(item);
				if (!published) {
					published = true;
					session.postMessage(item.getIdentifier(), item.getRole(), item.getDisplayContent());
				}
			}
		}
		session.onWriteResolved(this);
	}

	@Override
	public void rollback() {
		if (!beginResolve())
			return;
		synchronized (writeLock) {
			if (pending) {
				session.getHistory().rollbackPending(item);
			} else {
				session.getHistory().removeOf(item.getIdentifier());
			}
			if (published) {
				session.delMessage(item.getIdentifier());
			}
		}
		session.onWriteResolved(this);
	}

	@Override
	public void close() {
		if (!beginResolve())
			return;
		if (pending) {
			// 新记录按内容结算：写过显示内容或上下文内容就提交，只写了思维链的空记录撤回
			synchronized (writeLock) {
				MessageContents display = item.getDisplayContent();
				MessageContents context = item.getContextContent();
				if ((display != null && !display.isEmpty()) || (context != null && !context.isEmpty())) {
					if (!published) {
						published = true;
						session.postMessage(item.getIdentifier(), item.getRole(), item.getDisplayContent());
					}
					session.getHistory().commitPending(item);
				} else {
					session.getHistory().rollbackPending(item);
					if (published) {
						session.delMessage(item.getIdentifier());
					}
				}
			}
		}
		// 续写的记录本来就在历史里，没有需要提交或撤回的内容
		session.onWriteResolved(this);
	}

	@Override
	public boolean isResolved() {
		return resolved;
	}

	// ── HistoryItem 读取 ──────────────────────────────────

	@Override
	public MessageContents getContextContent() {
		return item.getContextContent();
	}

	@Override
	public MessageContents getDisplayContent() {
		return item.getDisplayContent();
	}

	@Override
	public Role getRole() {
		return item.getRole();
	}

	@Override
	public int getIdentifier() {
		return item.getIdentifier();
	}

	@Override
	public MessageContents getReasoningContent() {
		return item.getReasoningContent();
	}

	@Override
	public boolean isValidContext() {
		return item.isValidContext();
	}

	@Override
	public boolean isDeleted() {
		return item.isDeleted();
	}

	@Override
	public boolean isPending() {
		return item.isPending();
	}

	@Override
	public String getAudioId() {
		return item.getAudioId();
	}

	@Override
	public ApplicationState getLastState() {
		return item.getLastState();
	}

	@Override
	public int getPrevIdentifier() {
		return item.getPrevIdentifier();
	}

	@Override
	public long getTokenLength() {
		return item.getTokenLength();
	}

	@Override
	public double getTokenCost() {
		return item.getTokenCost();
	}

	@Override
	public boolean maySendReasoner() {
		return item.maySendReasoner();
	}

	// ── MutableHistoryItem 写入 ───────────────────────────

	@Override
	public void appendLine(String content, boolean addToContext) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().appendLine(content, addToContext);
			} else {
				session.getHistory().appendLine(content, addToContext);
			}
			notifyAppend(content + "\n");
		}
	}

	@Override
	public void append(String content, boolean addToContext) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().append(content, addToContext);
			} else {
				session.getHistory().append(content, addToContext);
			}
			notifyAppend(content);
		}
	}

	@Override
	public void append(MessageContent content, boolean addToContext) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().append(content, addToContext);
			} else {
				session.getHistory().append(content, addToContext);
			}
			notifyAppend(content);
		}
	}

	@Override
	public void appendContext(String content) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().appendContext(content);
			} else {
				session.getHistory().appendContext(content);
			}
			notifyWritten();
		}
	}

	@Override
	public void setContextContent(String fullContent) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setContextContent(fullContent);
			}
			session.setUpdated();
		}
	}

	/**
	 * 追加一段思维链内容。
	 * <p>
	 * 本轮记录已经提交或撤回时直接返回，不把内容写到已经定型的记录上；
	 * 后台异步读流据此结束（写入上下文实现里的同名方法会返回本次是否真的写入）。
	 * </p>
	 *
	 * @param reasoner 本次追加的思维链内容
	 */
	@Override
	public void appendReasoner(MessageContent reasoner) {
		synchronized (writeLock) {
			if (resolved)
				return;
			checkWritable();
			if (pending) {
				mutable().appendReasoner(reasoner);
			} else {
				session.getHistory().appendReasoner(reasoner);
			}
			session.onReasonerAppend(reasoner);
			session.setUpdated();
		}
	}

	/**
	 * 追加一段思维链内容，并返回本次是否真的写入。
	 * <p>
	 * 后台异步读流使用本方法：本轮记录已经提交或撤回时返回 {@code false}，
	 * 读流据此收工，不会把内容写到已经定型的记录上。
	 * </p>
	 *
	 * @param reasoner 本次追加的思维链内容
	 * @return 已写入返回 {@code true}；记录已经结束、本次内容被丢弃时返回 {@code false}
	 */
	@Override
	public boolean appendReasonerIfOpen(MessageContent reasoner) {
		synchronized (writeLock) {
			if (resolved)
				return false;
			checkWritable();
			if (pending) {
				mutable().appendReasoner(reasoner);
			} else {
				session.getHistory().appendReasoner(reasoner);
			}
			session.onReasonerAppend(reasoner);
			session.setUpdated();
			return true;
		}
	}

	@Override
	public void setLastState(ApplicationState lastState) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setLastState(lastState);
			} else {
				session.getHistory().setLastState(item, lastState);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setPrevIdentifier(int prevIdentifier) {
		synchronized (writeLock) {
			checkWritable();
			if (!pending)
				throw new UnsupportedOperationException("续写的记录不支持修改前驱标识符");
			mutable().setPrevIdentifier(prevIdentifier);
			session.setUpdated();
		}
	}

	@Override
	public void setReasonContent(String reasonContent) {
		synchronized (writeLock) {
			checkWritable();
			if (!pending)
				throw new UnsupportedOperationException("续写的记录不支持替换推理内容");
			mutable().setReasonContent(reasonContent);
			session.setUpdated();
		}
	}

	@Override
	public void setTokenLength(long tokenLength) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setTokenLength(tokenLength);
			} else {
				session.getHistory().setTokenLength(item, tokenLength);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setTokenCost(double tokenCost) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setTokenCost(tokenCost);
			} else {
				session.getHistory().setTokenCost(item, tokenCost);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setValidContext(boolean validContext) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setValidContext(validContext);
			} else {
				session.getHistory().setValidContext(item, validContext);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setDeleted(boolean deleted) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setDeleted(deleted);
			} else {
				session.getHistory().setDeleted(item, deleted);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setAudioId(String audioId) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setAudioId(audioId);
			} else {
				session.getHistory().setAudioId(item, audioId);
			}
			session.setUpdated();
		}
	}

	@Override
	public void setSendReasoner(boolean sendReasoner) {
		synchronized (writeLock) {
			checkWritable();
			if (pending) {
				mutable().setSendReasoner(sendReasoner);
			} else {
				session.getHistory().setSendReasoner(item, sendReasoner);
			}
			session.setUpdated();
		}
	}
}
