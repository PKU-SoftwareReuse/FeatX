package cn.edu.pku.lixutian.dao.repository;

import cn.edu.pku.lixutian.dao.ProjectInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProjectInfoRepository extends JpaRepository<ProjectInfo, Integer> {

}
