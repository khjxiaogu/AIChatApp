package com.khjxiaogu.aiwuxia.mcp;

/**
 * {@link SDXLMcp} 参考图解析的离线自检。
 * <p>
 * 图生图 / 分区图生图 / 反推提示词既接受裸对象存储 id，也接受工具结果里返回的
 * {@code /painter/image?image_id=...} 与 {@code /aichat/mcp/media?file_id=...} 链接；
 * 二进制连接器需要按这个解析结果把图片文件读出来内嵌进报文。
 * </p>
 * <p>
 * 不依赖 JUnit，直接用 {@code java} 运行本类的 {@link #main(String[])}。
 * </p>
 */
public class SDXLMcpReferenceTest {

	/** 测试用的对象存储 id（72 位十六进制内容哈希）。 */
	private static final String ID = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef01234567";

	/**
	 * 运行全部用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) {
		testRawId();
		testPainterLink();
		testMcpMediaLink();
		testUnrelated();
		System.out.println("SDXLMcpReferenceTest: 全部用例通过");
	}

	/** 裸 id（带不带扩展名）都原样识别。 */
	private static void testRawId() {
		check(ID.equals(SDXLMcp.storageIdOf(ID)), "裸 id 应原样返回");
		check((ID + ".png").equals(SDXLMcp.storageIdOf(ID + ".png")), "带扩展名的 id 应原样返回");
		check((ID + ".png").equals(SDXLMcp.storageIdOf("  " + ID + ".png  ")), "应忽略首尾空白");
	}

	/** 绘画会话的图片链接。 */
	private static void testPainterLink() {
		String link = "http://example.com/aichat/painter/image?image_id=" + ID;
		check(ID.equals(SDXLMcp.storageIdOf(link)), "绘画图片链接应取出 image_id，实际：" + SDXLMcp.storageIdOf(link));
		check(ID.equals(SDXLMcp.storageIdOf("/aichat/painter/image?image_id=" + ID + "&v=1")),
				"应截掉后续参数");
	}

	/** MCP 统一媒体入口的链接（id 会被 URL 编码）。 */
	private static void testMcpMediaLink() {
		String link = "http://example.com/aichat/mcp/media?file_id=" + ID + ".png";
		check((ID + ".png").equals(SDXLMcp.storageIdOf(link)), "MCP 媒体链接应取出 file_id");
	}

	/** 非图片引用（无法读取的内容）返回 null，由调用方回退到链接。 */
	private static void testUnrelated() {
		check(SDXLMcp.storageIdOf(null) == null, "null 应返回 null");
		check(SDXLMcp.storageIdOf("") == null, "空串应返回 null");
		check(SDXLMcp.storageIdOf("a photo of a cat") == null, "普通文本应返回 null");
		check(SDXLMcp.storageIdOf("http://example.com/aichat/painter/image") == null,
				"没有 id 参数的链接应返回 null");
		check(SDXLMcp.storageIdOf("data:image/png;base64,AAAA") == null, "data URL 交给调用方处理");
	}

	/** 断言；失败时抛出异常并打印原因。 */
	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
