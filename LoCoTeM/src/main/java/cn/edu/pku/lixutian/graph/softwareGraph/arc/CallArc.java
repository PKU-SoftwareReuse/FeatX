package cn.edu.pku.lixutian.graph.softwareGraph.arc;

import com.github.javaparser.resolution.Resolvable;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;

public abstract class CallArc extends Arc{
    public CallArc(String label) {
        super(label);
    }

    public static class NormalCallArc extends CallArc {
        protected final Resolvable<? extends ResolvedMethodLikeDeclaration> call;

        public Resolvable<? extends ResolvedMethodLikeDeclaration> getCall() {
            return call;
        }

        public NormalCallArc(Resolvable<? extends ResolvedMethodLikeDeclaration> call) {
            super(call.toString());
            this.call = call;
        }
    }

    public static class InitializerCallArc extends CallArc {
        public InitializerCallArc() {
            super("InitializerCall");
        }
    }

}
