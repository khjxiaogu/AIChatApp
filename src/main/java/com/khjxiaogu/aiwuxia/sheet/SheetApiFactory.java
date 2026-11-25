package com.khjxiaogu.aiwuxia.sheet;

import java.io.File;

import com.khjxiaogu.aiwuxia.sheet.feishu.FeishuBitableApi;
import com.khjxiaogu.aiwuxia.sheet.feishu.FeishuBitableConfig;
import com.khjxiaogu.aiwuxia.sheet.tencent.TencentSheetApi;
import com.khjxiaogu.aiwuxia.sheet.tencent.TencentSheetConfig;
import com.khjxiaogu.aiwuxia.utils.ConfigPaths;

/**
 * 按供应商创建电子表格 API。
 * <p>
 * 各供应商的凭据与连接配置：
 * </p>
 * <ul>
 * <li>腾讯文档：{@code save/config/tencent-docs.json}；</li>
 * <li>飞书多维表格：{@code save/config/feishu-bitable.json}（凭据 + 连接级 {@code app_token}）。</li>
 * </ul>
 * <p>
 * 腾讯文档的 {@code targetId} 是文档 {@code file_id}；飞书不需要目标参数，数据表 {@code table_id}
 * 由 {@code SheetApi} 的每次调用传入。
 * </p>
 */
public final class SheetApiFactory {

	private SheetApiFactory() {
	}

	/**
	 * 按供应商创建表格 API。
	 *
	 * @param dataFolder 数据目录（通常是 {@code save}）
	 * @param provider   供应商；{@code null} 按飞书多维表格处理
	 * @param targetId   腾讯文档的文档 {@code file_id}；飞书忽略
	 * @return 表格 API
	 * @throws SheetException 供应商配置不完整或凭据缺失
	 */
	public static SheetApi create(File dataFolder, SheetProvider provider, String targetId) throws SheetException {
		if (dataFolder == null) {
			throw new IllegalArgumentException("dataFolder 不能为 null");
		}
		SheetProvider selected = provider == null ? SheetProvider.FEISHU : provider;
		switch (selected) {
		case TENCENT:
			return createTencent(dataFolder, targetId);
		case FEISHU:
			return createFeishu(dataFolder);
		default:
			throw new SheetException("不支持的电子表格供应商：" + selected);
		}
	}

	/**
	 * 按配置字符串创建表格 API。
	 *
	 * @param dataFolder 数据目录（通常是 {@code save}）
	 * @param provider   供应商标识，例如 {@code tencent}、{@code feishu}；空值按飞书多维表格处理
	 * @param targetId   腾讯文档的文档 {@code file_id}；飞书忽略
	 * @return 表格 API
	 * @throws SheetException 供应商未知或配置不完整
	 */
	public static SheetApi create(File dataFolder, String provider, String targetId) throws SheetException {
		SheetProvider selected;
		try {
			selected = SheetProvider.parse(provider);
		} catch (IllegalArgumentException e) {
			throw new SheetException(e.getMessage(), e);
		}
		return create(dataFolder, selected, targetId);
	}

	private static SheetApi createTencent(File dataFolder, String targetId) throws SheetException {
		File configFile = ConfigPaths.tencentDocs(dataFolder);
		TencentSheetConfig credentials = TencentSheetConfig.load(configFile);
		String fileId = isBlank(targetId) ? credentials.getFileId() : targetId.trim();
		if (isBlank(fileId)) {
			throw new SheetException("未配置腾讯文档表格：请在 " + configFile.getPath()
					+ " 配置 file_id，或在调用方传入目标文档 ID");
		}
		TencentSheetApi api = new TencentSheetApi(credentials, fileId.trim());
		if (!api.isConfigured()) {
			throw new SheetException("腾讯文档凭据不完整：请在 " + configFile.getPath()
					+ " 配置 client_id，以及 access_token（或 client_secret + refresh_token）");
		}
		return api;
	}

	private static SheetApi createFeishu(File dataFolder) throws SheetException {
		File configFile = ConfigPaths.feishuBitable(dataFolder);
		FeishuBitableConfig credentials = FeishuBitableConfig.load(configFile);
		if (isBlank(credentials.getAppToken())) {
			throw new SheetException("未配置飞书多维表格：请在 " + configFile.getPath()
					+ " 配置 app_token（多维表格连接标识）");
		}
		if (!credentials.isConfigured()) {
			throw new SheetException("飞书凭据不完整：请在 " + configFile.getPath()
					+ " 配置 app_id + app_secret（或 access_token）");
		}
		return new FeishuBitableApi(credentials);
	}

	private static boolean isBlank(String value) {
		return value == null || value.trim().isEmpty();
	}
}
