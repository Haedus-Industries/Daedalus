package project.daedalus.special;

import project.daedalus.MethodContext;

public interface SpecialMethodProcessor {
    String preProcess(MethodContext context);

    void postProcess(MethodContext context);
}
