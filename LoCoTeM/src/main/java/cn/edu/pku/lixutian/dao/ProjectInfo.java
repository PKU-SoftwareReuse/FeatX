package cn.edu.pku.lixutian.dao;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "project_info")
@Getter
@Setter
public class ProjectInfo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "repo_name")
    private String repoName;

    @Column(name = "description")
    private String description;

    @Column(name = "git_link")
    private String gitLink;

    @Column(name = "loc")
    private Integer loc;

    @Column(name = "noc")
    private Integer noc;

    @Column(name = "nom")
    private Integer nom;

    @Column(name = "nof")
    private Integer nof;

    @Column(name = "summary_flag")
    private Boolean summaryFlag;

}
