package dev.lukebemish.multiloader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LoaderSet {
    private final List<String> parents = new ArrayList<>();
    private final List<String> parentsView = Collections.unmodifiableList(parents);
    private final String name;

    public LoaderSet(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void parent(String parent) {
        parents.add(parent);
    }

    public List<String> getParents() {
        return parentsView;
    }
}
