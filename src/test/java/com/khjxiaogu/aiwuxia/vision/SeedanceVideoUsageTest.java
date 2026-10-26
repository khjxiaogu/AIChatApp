package com.khjxiaogu.aiwuxia.vision;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Seedance 视频计费测试。
 * <p>
 * 公式：视频 token = 宽 × 高 × 帧率 × 时长 / 1024；官方价 70 元/百万视频 token。
 * 本站配额按 2 元/百万等效 token 折算，所以 quota 值 = 视频 token × 35。
 * </p>
 */
public class SeedanceVideoUsageTest {

	/**
	 * 运行用例。
	 *
	 * @param args 忽略
	 */
	public static void main(String[] args) {
		testFormula();
		testOfficialExample();
		testDimensions();
		testOverrides();
		testAccumulate();
		System.out.println("SeedanceVideoUsageTest: 全部用例通过");
	}

	/**
	 * 官方示例：480p、16:9、5秒 = 48037.5 视频 token（约 0.7 元/秒）。
	 * 480p 16:9 的输出尺寸应为 854×480，帧率 24。
	 */
	private static void testOfficialExample() {
		SeedanceVideoGenerator generator = generator("480p", "16:9", 24);
		check(generator.getWidth() == 854 && generator.getHeight() == 480,
				"480p 16:9 应为854×480，实际：" + generator.getWidth() + "×" + generator.getHeight());
		SeedanceVideoUsage usage = generator.createUsage(5);
		check(close(usage.getVideoTokens(), 48037.5d),
				"官方示例5秒应为48037.5视频token，实际：" + usage.getVideoTokens());
		check(close(usage.getPriceYuan(), 3.362625d),
				"官方示例金额应为3.362625元，实际：" + usage.getPriceYuan());
		check(close(usage.getPriceYuan() / 5d, 0.672525d),
				"每秒约0.7元，实际：" + (usage.getPriceYuan() / 5d));
	}

	/** 720p 9:16 8秒：720×1280×24×8/1024 = 172800 视频 token。 */
	private static void testFormula() {
		SeedanceVideoUsage usage = new SeedanceVideoUsage(720, 1280, 24, 8);
		check(close(usage.getVideoTokens(), 172800d), "视频token应为172800，实际：" + usage.getVideoTokens());
		check(close(usage.getEquivantTokens(), 172800d * 35d),
				"等效配额token应为视频token×35，实际：" + usage.getEquivantTokens());
		check(close(usage.getPriceYuan(), 12.096d), "价格应为12.096元，实际：" + usage.getPriceYuan());
		check(SeedanceVideoUsage.PRICE_PER_MILLION_TOKENS == 70d, "单价应为70元/百万token");
		check(SeedanceVideoUsage.EQUIVALENT_TOKEN_MULTIPLIER == 35d, "70/2倍率应为35");
	}

	/** 尺寸从分辨率与画幅推导。 */
	private static void testDimensions() {
		SeedanceVideoGenerator nineSixteen = generator("720p", "9:16", 24);
		check(nineSixteen.getWidth() == 720 && nineSixteen.getHeight() == 1280,
				"720p 9:16 应为720×1280，实际：" + nineSixteen.getWidth() + "×" + nineSixteen.getHeight());
		check(nineSixteen.createUsage(8).getVideoTokens() == 172800d, "720p 9:16 8秒token应一致");

		SeedanceVideoGenerator sixteenNine = generator("720p", "16:9", 24);
		check(sixteenNine.getWidth() == 1280 && sixteenNine.getHeight() == 720,
				"720p 16:9 应为1280×720，实际：" + sixteenNine.getWidth() + "×" + sixteenNine.getHeight());

		SeedanceVideoGenerator fullHd = generator("1080p", "9:16", 24);
		check(fullHd.getWidth() == 1080 && fullHd.getHeight() == 1920,
				"1080p 9:16 应为1080×1920，实际：" + fullHd.getWidth() + "×" + fullHd.getHeight());
	}

	/** videoWidth/videoHeight/videoFps 可覆盖推导值。 */
	private static void testOverrides() {
		SeedanceVideoGenerator generator = new SeedanceVideoGenerator(config("720p", "9:16", 30, 1000, 500));
		check(generator.getWidth() == 1000 && generator.getHeight() == 500,
				"宽高应被配置覆盖，实际：" + generator.getWidth() + "×" + generator.getHeight());
		SeedanceVideoUsage usage = generator.createUsage(8);
		check(close(usage.getVideoTokens(), 1000d * 500 * 30 * 8 / 1024d),
				"覆盖后的token计算错误，实际：" + usage.getVideoTokens());
	}

	/** 多次生成累加。 */
	private static void testAccumulate() {
		SeedanceVideoUsage total = new SeedanceVideoUsage(720, 1280, 24, 8);
		total.add(new SeedanceVideoUsage(720, 1280, 24, 4));
		check(close(total.getVideoTokens(), 172800d + 86400d), "累加token错误，实际：" + total.getVideoTokens());
		check(total.count == 2, "累加条数应为2，实际：" + total.count);
		SeedanceVideoUsage copy = new SeedanceVideoUsage();
		copy.set(total);
		check(copy.count == 2 && close(copy.getVideoTokens(), total.getVideoTokens()), "set复制错误");
	}

	private static SeedanceVideoGenerator generator(String resolution, String ratio, int fps) {
		return new SeedanceVideoGenerator(config(resolution, ratio, fps, 0, 0));
	}

	private static JsonObject config(String resolution, String ratio, int fps, int width, int height) {
		JsonObject config = JsonParser.parseString("{}").getAsJsonObject();
		config.addProperty("videoResolution", resolution);
		config.addProperty("videoRatio", ratio);
		config.addProperty("videoFps", fps);
		if (width > 0 && height > 0) {
			config.addProperty("videoWidth", width);
			config.addProperty("videoHeight", height);
		}
		return config;
	}

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) < 0.000001d;
	}

	private static void check(boolean ok, String message) {
		if (!ok) {
			throw new AssertionError(message);
		}
	}
}
