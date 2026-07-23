package cn.edu.pku.lixutian.dao;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "code_map")
@Getter
@Setter
public class CodeMap {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feature")
    private Feature feature;

    @Column(name = "method_name", columnDefinition = "TEXT")
    private String methodName;


}
