package com.misanthropy.splattered.api;

public interface TimeHook {

    default boolean slowed() {
        return false;
    }

    default int every(double x, double y, double z) {
        return 1;
    }

    default boolean skip(int every) {
        return false;
    }

    default float partial(int every, float partial) {
        return partial;
    }
}
