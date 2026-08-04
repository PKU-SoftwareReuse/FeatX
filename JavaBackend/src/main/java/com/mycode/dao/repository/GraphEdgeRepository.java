package com.mycode.dao.repository;

import com.mycode.dao.GraphEdge;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GraphEdgeRepository extends JpaRepository<GraphEdge, Integer> {
    List<GraphEdge> findByRepo_Id(Integer repoId);

    boolean existsByRepo_IdAndSrcAndDest(Integer repoId, String src, String dest);

    default boolean existsByObject(GraphEdge graphEdge) {
        return existsByRepo_IdAndSrcAndDest(graphEdge.getRepo().getId(), graphEdge.getSrc(), graphEdge.getDest());
    }
}
