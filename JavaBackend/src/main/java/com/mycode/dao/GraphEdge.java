package com.mycode.dao;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "graph_edge")
@Getter
@Setter
public class GraphEdge {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo")
    private ProjectInfo repo;

    @Column(name = "src")
    private String src;

    @Column(name = "dest")
    private String dest;

}
