package com.mycode.dao;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "features")
@Getter
@Setter
public class Feature {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module")
    private Module module;

    @Column(name = "feature_id")
    private Integer featureId;

    @Column(name = "feature_desc", columnDefinition = "TEXT")
    private String featureDesc;

    @Column(name = "feature_desc_CN", columnDefinition = "TEXT")
    private String featureDescCN;

    @OneToMany(mappedBy = "feature", cascade = CascadeType.ALL)
    private List<CodeMap> methodNameList = new ArrayList<>();
}
