package dev.daedalus.special;

import dev.daedalus.MethodContext;

public interface SpecialMethodProcessor {
    String preProcess(MethodContext context);

    void postProcess(MethodContext context);
}
