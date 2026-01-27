package cn.edu.pku.lixutian.dao.repository;

import cn.edu.pku.lixutian.dao.Module;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModuleRepository extends JpaRepository<Module, Integer> {
    List<Module> findByRepo_Id(Integer repoId);
    void deleteById(Integer moduleId);
}
