package com.mycode.dao.repository;

import com.mycode.dao.ProjectInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProjectInfoRepository extends JpaRepository<ProjectInfo, Integer> {
    List<ProjectInfo> findAllByArchivedFalseOrderByIdAsc();
}
