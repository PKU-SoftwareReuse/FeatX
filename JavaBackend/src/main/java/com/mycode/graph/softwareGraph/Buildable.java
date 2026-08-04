package com.mycode.graph.softwareGraph;

public interface Buildable<T> {
    /**
     * Complete this object by using the data from the argument.
     * This method should only be called once per instance.
     */
    void build(T arg);

    /**
     * Whether or not this object has been built.
     */
    boolean isBuilt();
}
