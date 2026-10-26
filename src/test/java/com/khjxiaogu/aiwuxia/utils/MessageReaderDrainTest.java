package com.khjxiaogu.aiwuxia.utils;

import com.khjxiaogu.aiwuxia.state.history.message.MutableMessageContents;
import com.khjxiaogu.aiwuxia.state.history.message.PlainText;
import com.khjxiaogu.aiwuxia.subagent.HistoryCompacter;

/**
 * 读取已结束但有缓冲内容的 {@link MessageReader} 的回归测试。
 * <p>
 * 模型返回后 {@code StreamedAIOutput.endContent()} 会先把 reader 置为 ended，
 * 但消息段可能还在缓冲区里。旧实现用 {@code while (!reader.isEnded())} 控制循环，
 * 会在进入循环前就退出，导致正文被读成空字符串。本测试固定“先 put、再 setEnded”的顺序。
 * </p>
 */
public class MessageReaderDrainTest {

	/**
	 * 运行用例。
	 *
	 * @param args 忽略
	 * @throws Exception 读取失败时
	 */
	public static void main(String[] args) throws Exception {
		testFileUtilDrainsEndedReader();
		testHistoryCompacterDrainsEndedReader();
		System.out.println("MessageReaderDrainTest: 全部用例通过");
	}

	/** FileUtil 的收集方法必须读完 ended reader 的缓冲区。 */
	private static void testFileUtilDrainsEndedReader() throws Exception {
		MessageReader reader = new MessageReader();
		reader.putCh(new PlainText("第一段"));
		reader.putCh(new PlainText("第二段"));
		reader.setEnded();
		MutableMessageContents contents = FileUtil.printAndCollectContent(reader);
		check("第一段第二段".equals(contents.toText()), "FileUtil 应读完缓冲区，实际：" + contents.toText());
	}

	/** HistoryCompacter 的收集方法同样必须读完 ended reader 的缓冲区。 */
	private static void testHistoryCompacterDrainsEndedReader() throws Exception {
		MessageReader reader = new MessageReader();
		reader.putCh(new PlainText("A"));
		reader.putCh(new PlainText("B"));
		reader.setEnded();
		String text = HistoryCompacter.printAndCollectContent(reader);
		check("AB".equals(text), "HistoryCompacter 应读完缓冲区，实际：" + text);
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
