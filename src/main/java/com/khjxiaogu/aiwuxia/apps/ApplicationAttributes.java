package com.khjxiaogu.aiwuxia.apps;

import java.util.ArrayList;
import java.util.List;

public class ApplicationAttributes {

	/** 应用可见性：不在任何列表中显示，也不允许新建对话（已存在的对话仍可按对话ID打开） */
	public static final String ACCESS_HIDDEN="hidden";
	/** 应用可见性：仅对权限表（permission.state='1'）中已授权的用户可见、可新建对话 */
	public static final String ACCESS_PERMISSION="permission";
	/** 应用可见性：对所有用户公开可见、可新建对话 */
	public static final String ACCESS_PUBLIC="public";

	public boolean paidOnly=false;
	public boolean freeNow=false;
	public List<String> models=new ArrayList<>();
	/** 应用默认模型标识，为空时回退到 {@link #models} 的第一项 */
	public String defaultModel=null;
	public String url=null;
	public AIApplication app;
	public String appid;
	public String ui;
	/**
	 * 应用可见性，取值见 {@link #ACCESS_HIDDEN}、{@link #ACCESS_PERMISSION}、{@link #ACCESS_PUBLIC}。
	 * <p>
	 * 由 meta.json 的 {@code access} 字段配置；为兼容旧配置，未配置时按旧的 {@code trial}
	 * 字段推断（trial 为真等价于公开可见，否则仅权限可见）。
	 * </p>
	 */
	public String access=ACCESS_PERMISSION;
	public ApplicationAttributes(AIApplication app,String appid) {
		this.app=app;
		this.appid=appid;
	}
	public String getName() {
		return app.getName();
	}

	/** 是否对所有用户公开可见 */
	public boolean isPublic() {
		return ACCESS_PUBLIC.equals(access);
	}

	/** 是否对所有用户不可见（仅保留已存在对话的按ID访问） */
	public boolean isHidden() {
		return ACCESS_HIDDEN.equals(access);
	}

	/** 是否仅对已授权用户可见 */
	public boolean isPermissionOnly() {
		return !isPublic()&&!isHidden();
	}

	/**
	 * 规范化 meta.json 中配置的可见性取值。
	 * <p>
	 * 接受 {@code hidden}/{@code permission}/{@code public} 及其常见同义写法（大小写不敏感），
	 * 无法识别时回退到 {@link #ACCESS_PERMISSION}。
	 * </p>
	 *
	 * @param raw 配置原文，可为 null
	 * @return 规范化后的可见性取值
	 */
	public static String normalizeAccess(String raw) {
		if(raw==null)
			return ACCESS_PERMISSION;
		switch(raw.trim().toLowerCase().replace('-','_')) {
		case ACCESS_HIDDEN:
		case "none":
		case "invisible":
		case "private":
			return ACCESS_HIDDEN;
		case ACCESS_PUBLIC:
		case "open":
		case "all":
		case "trial":
			return ACCESS_PUBLIC;
		case ACCESS_PERMISSION:
		case "limited":
		case "auth":
			return ACCESS_PERMISSION;
		default:
			return ACCESS_PERMISSION;
		}
	}

	/**
	 * 获取本应用的默认模型标识。
	 * <p>
	 * 未在 meta.json 中配置 {@code defaultModel} 时取 {@link #models} 的第一项，
	 * 即模型清单中排在最前面的模型。会话未显式指定模型时会使用该值。
	 * </p>
	 *
	 * @return 默认模型标识，没有可用模型时返回空字符串
	 */
	public String getDefaultModelHint() {
		if(defaultModel!=null&&!defaultModel.isEmpty())
			return defaultModel;
		return models==null||models.isEmpty()?"":models.get(0);
	}

}
