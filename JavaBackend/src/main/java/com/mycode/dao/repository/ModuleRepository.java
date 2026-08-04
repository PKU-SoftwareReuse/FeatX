package com.mycode.dao.repository;

import com.mycode.dao.Module;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModuleRepository extends JpaRepository<Module, Integer> {
    List<Module> findByRepo_Id(Integer repoId);
    void deleteById(Integer moduleId);
}
