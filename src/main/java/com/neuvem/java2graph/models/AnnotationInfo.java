package com.neuvem.java2graph.models;

import java.util.HashMap;
import java.util.Map;

public class AnnotationInfo {
    public String name;
    public Map<String, String> attributes = new HashMap<>();

    public AnnotationInfo() {}

    public AnnotationInfo(String name) {
        this.name = name;
    }
}
