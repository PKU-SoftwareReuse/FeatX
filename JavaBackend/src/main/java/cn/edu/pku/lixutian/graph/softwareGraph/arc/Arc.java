package cn.edu.pku.lixutian.graph.softwareGraph.arc;

import cn.edu.pku.lixutian.graph.softwareGraph.vertex.Vertex;
import lombok.Getter;
import org.jgrapht.graph.DefaultEdge;

import java.util.*;

public abstract class Arc extends DefaultEdge {
    // Override DefaultEdge
    @Override
    public Vertex<?> getSource() {
        return (Vertex<?>) super.getSource();
    }

    @Override
    public Vertex<?> getTarget() {
        return (Vertex<?>) super.getTarget();
    }

    @Getter
    protected final String label;

    @Getter
    protected Set<Integer> clusterIds;

    public Arc(String label) {
        this.label = label;
        this.clusterIds = new HashSet<>();
    }

    // Override Object
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!obj.getClass().equals(this.getClass())) {
            return false;
        }
        return Objects.equals(getSource(), ((Arc) obj).getSource()) &&
                Objects.equals(getTarget(), ((Arc) obj).getTarget()) &&
                Objects.equals(getLabel(), ((Arc) obj).getLabel());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), getLabel(), getSource(), getTarget());
    }
}
