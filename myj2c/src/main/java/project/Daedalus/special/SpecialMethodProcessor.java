package project.Daedalus.special;

import project.Daedalus.MethodContext;

public interface SpecialMethodProcessor {
    String preProcess(MethodContext context);

    void postProcess(MethodContext context);
}
