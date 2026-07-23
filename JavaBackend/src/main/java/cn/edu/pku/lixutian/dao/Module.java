package cn.edu.pku.lixutian.dao;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "modules")
@Getter
@Setter
public class Module {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo")
    private ProjectInfo repo;

    @Column(name = "cluster_id")
    private Integer clusterId;

    @Column(name = "module_desc", columnDefinition = "TEXT")
    private String moduleDesc;

    @Column(name = "module_desc_CN")
    private String moduleDescCN;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL)
    private List<Feature> featureList = new ArrayList<>();

}
