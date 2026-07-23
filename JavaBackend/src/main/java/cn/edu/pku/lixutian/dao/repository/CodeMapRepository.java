package cn.edu.pku.lixutian.dao.repository;

import cn.edu.pku.lixutian.dao.CodeMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeMapRepository extends JpaRepository<CodeMap, Integer> {
    List<CodeMap> findByFeature_Id(Integer featureId);
    List<CodeMap> findByMethodName(String methodName);
    void deleteByFeature_Id(Integer featureId);
    boolean existsByMethodName(String methodName);
    boolean existsByFeature_IdAndMethodName(Integer featureId, String methodName);
}
