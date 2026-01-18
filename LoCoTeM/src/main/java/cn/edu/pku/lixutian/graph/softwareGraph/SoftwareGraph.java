package cn.edu.pku.lixutian.graph.softwareGraph;

import cn.edu.pku.lixutian.graph.softwareGraph.arc.Arc;
import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import org.jgrapht.graph.DirectedPseudograph;

import java.util.HashSet;
import java.util.Set;

public abstract class SoftwareGraph<SubArc extends Arc> extends DirectedPseudograph<Vertex<?>, Arc> implements Buildable<NodeList<CompilationUnit>> {

    public SoftwareGraph() {
        super(null, null, false);
    }

    protected boolean built = false;

    @Override
    public boolean isBuilt() {
        return built;
    }

    /**
     * implements Buildable&lt;T&gt;
     */
    @Override
    public void build(NodeList<CompilationUnit> arg) {
        if (isBuilt())
            return;
        Set<String> unsolvedSymbols = new HashSet<>();
        buildVertices();
        buildEdges(arg, unsolvedSymbols);
        System.out.println("解析失败的符号，他们应该在库中： totally " + unsolvedSymbols.size());
        for (String symbol : unsolvedSymbols) {
            System.out.println(" * " + symbol);
        }
        built = true;
    }

    protected abstract void buildVertices();

    protected abstract void buildEdges(NodeList<CompilationUnit> arg, Set<String> unsolvedSymbols);
}
