package com.khjxiaogu.aiwuxia.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SceneSelector {
	public static class Predicate{
		String key;
		List<String> values;
		public Predicate(String name, String value) {
			key=name;
			values=new ArrayList<>();
			values.add(value);
		}
		public Predicate(String name, String... values) {
			key=name;
			this.values=new ArrayList<>();
			for(String value:values)
				this.values.add(value);
		}
		public Predicate(String name, List<String> values) {
			key=name;
			this.values=values;
		}
		public boolean test(Map<String,String> sceneData) {
			return values.contains(sceneData.get(key));
		}
	}
	public List<Predicate> predicates;
	public List<SceneSelector> selectors;
	public String scene;
	public SceneSelector() {
		super();
		this.predicates = new ArrayList<>();
		this.selectors = new ArrayList<>();
	}
	public String getSceneData(Map<String,String> sceneData) {
		for(Predicate pred:predicates) {
			if(!pred.test(sceneData))
				return null;
		}
		for(SceneSelector filter:selectors) {
			String data=filter.getSceneData(sceneData);
			if(data!=null)
				return data;
		}
		return scene;
	}
}
